using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using TuyaLan.Protocol;

namespace TuyaLan.Devices;

/// <summary>A device found by <see cref="TuyaDiscovery"/>.</summary>
public sealed class TuyaDiscoveryResult
{
    /// <summary>IP address the announcement came from.</summary>
    public string IpAddress { get; init; } = string.Empty;

    /// <summary>Device id, also called <c>gwId</c>.</summary>
    public string DeviceId { get; init; } = string.Empty;

    /// <summary>Product key (productId) of the device model.</summary>
    public string? ProductKey { get; init; }

    /// <summary>Protocol revision the device reports.</summary>
    public TuyaProtocolVersion Version { get; init; }

    /// <summary>True when the announcement was encrypted (everything from 3.3 onwards).</summary>
    public bool Encrypted { get; init; }

    /// <summary>The decoded announcement JSON.</summary>
    public string RawJson { get; init; } = string.Empty;

    public override string ToString() => $"{IpAddress} {DeviceId} v{Version.ToLabel()} productKey={ProductKey}";
}

/// <summary>
/// Finds Tuya devices on the local network.
/// </summary>
/// <remarks>
/// <para>
/// Three announcement formats exist. 3.1 devices broadcast plain JSON on UDP
/// 6666. From 3.3 they broadcast an AES-encrypted blob on UDP 6667. 3.5 devices
/// use the 0x00006699 frame with AES-GCM. All encrypted forms use the same
/// well-known key, <c>MD5("yGAdlopoPVldABfn")</c>, which protects only the
/// announcement and never device control.
/// </para>
/// <para>
/// Many 3.5 devices no longer broadcast at all: they wait for a client to
/// announce itself on UDP 7000 and reply directly. <see cref="ScanAsync"/> sends
/// that solicitation by default.
/// </para>
/// </remarks>
public sealed class TuyaDiscovery
{
    /// <summary>Port for plaintext (3.1) announcements.</summary>
    public const int PlaintextPort = 6666;

    /// <summary>Port for encrypted (3.3+) announcements.</summary>
    public const int EncryptedPort = 6667;

    /// <summary>Port used for app-style discovery and 3.5 solicitation.</summary>
    public const int AppPort = 7000;

    /// <summary>The public discovery key: <c>MD5("yGAdlopoPVldABfn")</c>.</summary>
    public static readonly byte[] DiscoveryKey = TuyaCrypto.Md5(Encoding.ASCII.GetBytes("yGAdlopoPVldABfn"));

    /// <summary>
    /// Listens for announcements, optionally solicits them, and returns every
    /// distinct device seen within <paramref name="duration"/>.
    /// </summary>
    public static async Task<IReadOnlyList<TuyaDiscoveryResult>> ScanAsync(
        TimeSpan? duration = null,
        bool solicit = true,
        CancellationToken cancellationToken = default)
    {
        var window = duration ?? TimeSpan.FromSeconds(6);
        var results = new ConcurrentDictionary<string, TuyaDiscoveryResult>(StringComparer.OrdinalIgnoreCase);
        var listeners = new List<UdpClient>();
        var pumps = new List<Task>();

        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        lifetime.CancelAfter(window);

        try
        {
            foreach (int port in new[] { PlaintextPort, EncryptedPort, AppPort })
            {
                var listener = TryBind(port);
                if (listener is null) continue;
                listeners.Add(listener);
                pumps.Add(PumpAsync(listener, results, lifetime.Token));
            }

            if (listeners.Count == 0)
            {
                throw new TuyaException(
                    "could not bind any discovery port (6666/6667/7000). " +
                    "Another app such as the Smart Life client may be holding them.");
            }

            if (solicit) await SendSolicitationAsync(cancellationToken).ConfigureAwait(false);

            try
            {
                await Task.Delay(window, lifetime.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
            {
                // expected when the scan window closes
            }

            await Task.WhenAll(pumps).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // caller cancelled
        }
        finally
        {
            foreach (var listener in listeners) listener.Dispose();
        }

        return new List<TuyaDiscoveryResult>(results.Values);
    }

    private static UdpClient? TryBind(int port)
    {
        try
        {
            var client = new UdpClient();
            client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            client.Client.Bind(new IPEndPoint(IPAddress.Any, port));
            return client;
        }
        catch (SocketException)
        {
            return null;
        }
    }

    private static async Task PumpAsync(
        UdpClient listener,
        ConcurrentDictionary<string, TuyaDiscoveryResult> results,
        CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var received = await listener.ReceiveAsync(cancellationToken).ConfigureAwait(false);
                var result = Decode(received.Buffer, received.RemoteEndPoint.Address.ToString());
                if (result is not null) results[result.DeviceId] = result;
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                // a stray datagram must not kill the scan
            }
            catch (ObjectDisposedException)
            {
                return;
            }
        }
    }

    /// <summary>Decodes one announcement datagram. Returns null if it is not a Tuya announcement.</summary>
    public static TuyaDiscoveryResult? Decode(byte[] datagram, string fallbackIp)
    {
        if (datagram.Length < 4) return null;

        if (datagram.AsSpan(0, 4).SequenceEqual(new byte[] { 0x00, 0x00, 0x66, 0x99 }))
        {
            return DecodeGcm(datagram, fallbackIp);
        }

        if (datagram.AsSpan(0, 4).SequenceEqual(new byte[] { 0x00, 0x00, 0x55, 0xAA }))
        {
            return DecodeLegacyFrame(datagram, fallbackIp);
        }

        // 3.1 firmware broadcasts bare JSON, or a raw AES-ECB blob with no frame.
        if (datagram[0] == (byte)'{') return ParseJson(Encoding.UTF8.GetString(datagram), fallbackIp, false);
        try
        {
            var plain = TuyaCrypto.AesEcbDecrypt(DiscoveryKey, datagram);
            return ParseJson(Encoding.UTF8.GetString(plain), fallbackIp, true);
        }
        catch (TuyaException)
        {
            return null;
        }
    }

    private static TuyaDiscoveryResult? DecodeGcm(byte[] datagram, string fallbackIp)
    {
        try
        {
            var message = TuyaFrameCodec.Unpack(TuyaProtocolVersion.V35, DiscoveryKey, datagram, RetcodePolicy.Auto);
            var json = message.PayloadText.TrimEnd('\0');
            return ParseJson(json, fallbackIp, true);
        }
        catch (TuyaException)
        {
            return null;
        }
    }

    private static TuyaDiscoveryResult? DecodeLegacyFrame(byte[] datagram, string fallbackIp)
    {
        try
        {
            var message = TuyaFrameCodec.Unpack(TuyaProtocolVersion.V33, DiscoveryKey, datagram, RetcodePolicy.Never);
            var payload = message.Payload;
            if (payload.Length > 0 && payload[0] == (byte)'{')
            {
                return ParseJson(Encoding.UTF8.GetString(payload), fallbackIp, false);
            }

            var plain = TuyaCrypto.AesEcbDecrypt(DiscoveryKey, payload);
            return ParseJson(Encoding.UTF8.GetString(plain), fallbackIp, true);
        }
        catch (TuyaException)
        {
            return null;
        }
    }

    private static TuyaDiscoveryResult? ParseJson(string json, string fallbackIp, bool encrypted)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;

            string? deviceId = GetString(root, "gwId") ?? GetString(root, "devId") ?? GetString(root, "id");
            if (string.IsNullOrEmpty(deviceId)) return null;

            string ip = GetString(root, "ip") ?? fallbackIp;
            if (string.IsNullOrWhiteSpace(ip) || ip == "0.0.0.0") ip = fallbackIp;

            return new TuyaDiscoveryResult
            {
                IpAddress = ip,
                DeviceId = deviceId,
                ProductKey = GetString(root, "productKey") ?? GetString(root, "productId"),
                Version = TuyaProtocolVersionExtensions.ParseLabel(GetString(root, "version")),
                Encrypted = encrypted,
                RawJson = json,
            };
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string? GetString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    /// <summary>
    /// Broadcasts the 3.5 solicitation payload
    /// <c>{"from":"app","ip":"&lt;this host&gt;"}</c> to UDP 7000 so silent
    /// devices answer directly.
    /// </summary>
    private static async Task SendSolicitationAsync(CancellationToken cancellationToken)
    {
        foreach (var (broadcast, local) in GetIpv4Networks())
        {
            var payload = Encoding.UTF8.GetBytes($"{{\"from\":\"app\",\"ip\":\"{local}\"}}");
            var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, DiscoveryKey, 1, TuyaCommand.RequestDeviceInfo, payload);

            try
            {
                using var sender = new UdpClient();
                sender.EnableBroadcast = true;
                await sender.SendAsync(frame, new IPEndPoint(broadcast, AppPort), cancellationToken).ConfigureAwait(false);
            }
            catch (SocketException)
            {
                // no route on this interface; try the next one
            }
            catch (ObjectDisposedException)
            {
                return;
            }
        }
    }

    private static IEnumerable<(IPAddress Broadcast, IPAddress Local)> GetIpv4Networks()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var mask = unicast.IPv4Mask;
                if (mask is null) continue;

                var address = unicast.Address.GetAddressBytes();
                var maskBytes = mask.GetAddressBytes();
                if (address.Length != 4 || maskBytes.Length != 4) continue;

                var broadcast = new byte[4];
                for (int i = 0; i < 4; i++) broadcast[i] = (byte)(address[i] | ~maskBytes[i]);
                yield return (new IPAddress(broadcast), unicast.Address);
            }
        }
    }
}
