using System;
using TuyaLan.Protocol;

namespace TuyaLan.Devices;

/// <summary>Everything needed to talk to one device on the LAN.</summary>
public sealed class TuyaDeviceOptions
{
    /// <summary>Device IP address, e.g. "192.168.1.42".</summary>
    public string Host { get; set; } = string.Empty;

    /// <summary>Device TCP port. Tuya devices listen on 6668.</summary>
    public int Port { get; set; } = 6668;

    /// <summary>The <c>gwId</c> / device id reported by discovery.</summary>
    public string DeviceId { get; set; } = string.Empty;

    /// <summary>The 16-character local key. Leave empty for discovery-only use.</summary>
    public string LocalKey { get; set; } = string.Empty;

    /// <summary>Optional exact 16-byte local key for callers that already decoded the key.</summary>
    /// <remarks>When supplied, this takes precedence over <see cref="LocalKey"/>.</remarks>
    public byte[]? LocalKeyBytes { get; set; }

    /// <summary>Protocol revision. Use the <c>version</c> field from discovery.</summary>
    public TuyaProtocolVersion Version { get; set; } = TuyaProtocolVersion.V35;

    /// <summary>Use the "device22" command variants for older 22-character gwId devices.</summary>
    public TuyaDeviceType DeviceType { get; set; } = TuyaDeviceType.Standard;

    /// <summary>Sub-device id for Zigbee devices behind a gateway.</summary>
    public string? Cid { get; set; }

    /// <summary>How long to wait for the TCP connection.</summary>
    public TimeSpan ConnectTimeout { get; set; } = TimeSpan.FromSeconds(5);

    /// <summary>How long to wait for a command reply.</summary>
    public TimeSpan CommandTimeout { get; set; } = TimeSpan.FromSeconds(5);

    /// <summary>How often to send a heartbeat. Set <see cref="EnableHeartbeat"/> to false to disable.</summary>
    public TimeSpan HeartbeatInterval { get; set; } = TimeSpan.FromSeconds(20);

    /// <summary>Keep the connection alive with periodic heartbeat frames.</summary>
    public bool EnableHeartbeat { get; set; } = true;

    /// <summary>Whether control commands should wait for the device to acknowledge.</summary>
    public bool WaitForControlAck { get; set; } = true;

    /// <summary>How to treat a leading return code when decoding replies.</summary>
    public RetcodePolicy RetcodePolicy { get; set; } = RetcodePolicy.Auto;

    /// <summary>Where the 15-byte version header goes for 3.3/3.4 frames.</summary>
    public VersionHeaderMode HeaderMode { get; set; } = VersionHeaderMode.InsideCiphertext;

    /// <summary>Throws when a required field is missing or malformed.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Host)) throw new ArgumentException("Host is required", nameof(Host));
        if (Port is <= 0 or > 65535) throw new ArgumentException($"Port {Port} is out of range", nameof(Port));
        if (string.IsNullOrWhiteSpace(DeviceId)) throw new ArgumentException("DeviceId is required", nameof(DeviceId));
        if (LocalKeyBytes is not null)
        {
            if (LocalKeyBytes.Length != TuyaCrypto.KeySize)
                throw new ArgumentException($"LocalKeyBytes must be exactly {TuyaCrypto.KeySize} bytes, got {LocalKeyBytes.Length}", nameof(LocalKeyBytes));
        }
        else
        {
            if (string.IsNullOrWhiteSpace(LocalKey)) throw new ArgumentException("LocalKey or LocalKeyBytes is required", nameof(LocalKey));
            if (LocalKey.Length != TuyaCrypto.KeySize)
                throw new ArgumentException($"LocalKey must be exactly {TuyaCrypto.KeySize} characters, got {LocalKey.Length}", nameof(LocalKey));
        }
    }
}
