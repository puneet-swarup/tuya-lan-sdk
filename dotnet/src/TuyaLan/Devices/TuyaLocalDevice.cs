using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using TuyaLan.Protocol;

namespace TuyaLan.Devices;

/// <summary>Raised when the device reports data points, either on request or unsolicited.</summary>
public sealed class TuyaStatusEventArgs : EventArgs
{
    public TuyaStatusEventArgs(TuyaStatus status, TuyaMessage message)
    {
        Status = status;
        Message = message;
    }

    public TuyaStatus Status { get; }

    /// <summary>The raw decoded message that carried the update.</summary>
    public TuyaMessage Message { get; }
}

/// <summary>Metadata for a successfully decoded device message.</summary>
public sealed class TuyaMessageReceivedEventArgs : EventArgs
{
    public TuyaMessageReceivedEventArgs(TuyaMessage message)
    {
        Command = message.Command;
        SequenceNumber = message.SequenceNumber;
        PayloadLength = message.Payload.Length;
    }

    public uint Command { get; }
    public uint SequenceNumber { get; }
    public int PayloadLength { get; }
}

/// <summary>Raised when a frame could not be decoded. The connection stays open.</summary>
public sealed class TuyaProtocolErrorEventArgs : EventArgs
{
    public TuyaProtocolErrorEventArgs(TuyaException error) => Error = error;

    public TuyaException Error { get; }
}

/// <summary>
/// A client for one Tuya device over the local network (TCP 6668).
/// </summary>
/// <remarks>
/// <para>
/// The lifecycle is: construct with <see cref="TuyaDeviceOptions"/>, call
/// <see cref="ConnectAsync"/> (which performs the 3.4/3.5 session-key handshake),
/// then issue commands. A background loop decodes frames and dispatches
/// unsolicited status pushes to <see cref="StatusUpdated"/>.
/// </para>
/// <para>
/// Tuya devices accept one request at a time, so commands are serialised behind
/// a semaphore. Control commands frequently get no explicit reply - the device
/// simply pushes a status frame - so <see cref="TuyaStatus"/> pushes are also
/// accepted as the acknowledgement.
/// </para>
/// </remarks>
/// <example>
/// <code>
/// await using var device = new TuyaLocalDevice(new TuyaDeviceOptions
/// {
///     Host = "192.168.1.42",
///     DeviceId = "eb0123456789abcdefghij",
///     LocalKey = "0123456789abcdef",
///     Version = TuyaProtocolVersion.V35,
/// });
///
/// device.StatusUpdated += (_, e) => Console.WriteLine(e.Status);
/// await device.ConnectAsync();
/// var status = await device.GetStatusAsync();
/// await device.SetDpAsync("1", true);
/// </code>
/// </example>
public sealed class TuyaLocalDevice : IAsyncDisposable
{
    private readonly TuyaDeviceOptions _options;
    private readonly TuyaSession _session;
    private readonly SemaphoreSlim _requestGate = new(1, 1);
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private readonly byte[] _readBuffer = new byte[16 * 1024];

    private TcpClient? _client;
    private NetworkStream? _stream;
    private CancellationTokenSource? _lifetime;
    private Task? _receiveLoop;
    private Task? _heartbeatLoop;
    private volatile PendingRequest? _pending;
    private int _readLength;
    private volatile bool _disposed;

    /// <summary>Set when the receive loop or the heartbeat finds the socket gone.</summary>
    private volatile bool _disconnected;

    public TuyaLocalDevice(TuyaDeviceOptions options)
    {
        _options = options ?? throw new ArgumentNullException(nameof(options));
        _options.Validate();
        _session = new TuyaSession(options.Version, options.LocalKeyBytes?.ToArray() ?? Encoding.UTF8.GetBytes(options.LocalKey))
        {
            HeaderMode = options.HeaderMode,
            RetcodePolicy = options.RetcodePolicy,
        };
    }

    /// <summary>Raised for every status frame, solicited or not.</summary>
    public event EventHandler<TuyaStatusEventArgs>? StatusUpdated;

    /// <summary>Raised for every successfully decoded message; exposes metadata only.</summary>
    public event EventHandler<TuyaMessageReceivedEventArgs>? MessageReceived;

    /// <summary>Raised when a frame cannot be decoded.</summary>
    public event EventHandler<TuyaProtocolErrorEventArgs>? ProtocolError;

    /// <summary>Raised when the connection drops unexpectedly.</summary>
    public event EventHandler<Exception>? Disconnected;

    /// <summary>Protocol revision in use.</summary>
    public TuyaProtocolVersion Version => _session.Version;

    /// <summary>
    /// True while the socket is usable.
    /// </summary>
    /// <remarks>
    /// This used to be "the stream object exists", which stayed true after the
    /// socket died until somebody called <see cref="DisconnectAsync"/>. A host
    /// that caches the device and checks this - the obvious thing to do - then
    /// reused a dead connection for ever: every command timed out and nothing
    /// reconnected. The receive loop and the heartbeat already notice the death;
    /// they now record it here, so the property answers the question it asks.
    /// </remarks>
    public bool IsConnected => _stream is not null && !_disconnected;

    /// <summary>True once the 3.4/3.5 handshake has completed. Never log the key itself.</summary>
    public bool HasSessionKey => _session.HasSessionKey;

    /// <summary>Opens the TCP connection and negotiates a session key when the version needs one.</summary>
    public async Task ConnectAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (IsConnected) throw new InvalidOperationException("already connected");

        // A previous connection that died leaves its stream behind. Drop it rather
        // than refuse to connect because of a socket nobody can use.
        if (_stream is not null) await DisconnectAsync().ConfigureAwait(false);

        _client = new TcpClient { NoDelay = true };
        using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
        {
            timeout.CancelAfter(_options.ConnectTimeout);
            try
            {
                await _client.ConnectAsync(_options.Host, _options.Port, timeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                _client.Dispose();
                _client = null;
                throw new TuyaTimeoutException(
                    $"could not connect to {_options.Host}:{_options.Port} within {_options.ConnectTimeout.TotalSeconds:0.#}s");
            }
        }

        _stream = _client.GetStream();
        _readLength = 0;

        try
        {
            if (_options.Version.UsesSessionKey())
            {
                await NegotiateSessionKeyAsync(cancellationToken).ConfigureAwait(false);
            }
        }
        catch
        {
            // A failed handshake (wrong local key, wrong protocol version, a silent
            // device) must not leave a half-open socket behind: IsConnected would
            // still report true and every later ConnectAsync would throw
            // "already connected" against a socket nobody can use.
            _stream?.Dispose();
            _stream = null;
            _client?.Dispose();
            _client = null;
            _session.ClearSessionKey();
            _readLength = 0;
            throw;
        }

        _disconnected = false;
        _lifetime = new CancellationTokenSource();
        _receiveLoop = Task.Run(() => ReceiveLoopAsync(_lifetime.Token), CancellationToken.None);
        if (_options.EnableHeartbeat)
        {
            _heartbeatLoop = Task.Run(() => HeartbeatLoopAsync(_lifetime.Token), CancellationToken.None);
        }
    }

    /// <summary>Closes the connection and stops the background loops.</summary>
    /// <summary>
    /// Connects only if the connection is not usable, so callers never have to
    /// cache the device and guess at its state.
    /// </summary>
    /// <remarks>
    /// This is the call a host should make before every operation. Caching a
    /// <see cref="TuyaLocalDevice"/> and checking <see cref="IsConnected"/> by
    /// hand is what produced the dead-socket bug this method exists to remove.
    /// </remarks>
    public async Task EnsureConnectedAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (IsConnected) return;
        await ConnectAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task DisconnectAsync()
    {
        if (_lifetime is not null)
        {
            await _lifetime.CancelAsync().ConfigureAwait(false);
        }

        _stream?.Dispose();
        _stream = null;
        _client?.Dispose();
        _client = null;
        _session.ClearSessionKey();

        foreach (var loop in new[] { _receiveLoop, _heartbeatLoop })
        {
            if (loop is null) continue;
            try
            {
                await loop.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // expected on shutdown
            }
        }

        _receiveLoop = null;
        _heartbeatLoop = null;
        _lifetime?.Dispose();
        _lifetime = null;
    }

    /// <summary>Queries the current data points.</summary>
    public async Task<TuyaStatus> GetStatusAsync(CancellationToken cancellationToken = default)
    {
        EnsureConnected();

        var reply = await SendRequestAsync(
            QueryCommand,
            BuildQueryPayload(),
            new[] { QueryCommand, TuyaCommand.Status, TuyaCommand.DpQueryNew, TuyaCommand.Control, TuyaCommand.ControlNew },
            cancellationToken).ConfigureAwait(false);

        var status = TuyaStatus.Parse(reply.Payload);
        if (status.HasData) return status;

        // Some firmware answers the standard query with "json obj data unvalid".
        // Retrying with the other query command usually works.
        var alternate = QueryCommand == TuyaCommand.DpQuery ? TuyaCommand.DpQueryNew : TuyaCommand.DpQuery;
        try
        {
            var retry = await SendRequestAsync(
                alternate,
                BuildQueryPayload(),
                new[] { TuyaCommand.Status, TuyaCommand.DpQueryNew, TuyaCommand.Control },
                cancellationToken).ConfigureAwait(false);

            var retried = TuyaStatus.Parse(retry.Payload);
            if (retried.HasData) return retried;
        }
        catch (TuyaTimeoutException)
        {
            // fall through and return the empty status
        }

        return status;
    }

    /// <summary>Sends one or more data points and waits for the device to acknowledge.</summary>
    public async Task SetDpsAsync(IEnumerable<KeyValuePair<string, JsonNode?>> dps, CancellationToken cancellationToken = default)
    {
        EnsureConnected();
        ArgumentNullException.ThrowIfNull(dps);

        var payload = BuildControlPayload(dps);
        var reply = await SendRequestAsync(
            ControlCommand,
            payload,
            new[] { ControlCommand, TuyaCommand.Control, TuyaCommand.ControlNew, TuyaCommand.Status, TuyaCommand.DpQueryNew },
            cancellationToken,
            tolerateTimeout: !_options.WaitForControlAck).ConfigureAwait(false);

        ThrowIfErrorReply(reply);
    }

    /// <summary>
    /// Tuya firmware answers a frame it cannot use with a short plain-text
    /// complaint inside an otherwise normal reply - "data format error",
    /// "json obj data unvalid". Without this check a rejected control looks
    /// exactly like a successful one: the call returns, nothing changes.
    /// </summary>
    private static void ThrowIfErrorReply(TuyaMessage message)
    {
        if (message.Payload.Length is 0 or > 64) return;

        string text = Encoding.UTF8.GetString(message.Payload);
        if (text.TrimStart().StartsWith('{')) return;

        foreach (var marker in new[] { "error", "unvalid", "invalid", "fail", "not support" })
        {
            if (text.Contains(marker, StringComparison.OrdinalIgnoreCase))
            {
                throw new TuyaProtocolException($"the device rejected the control frame: {text}");
            }
        }
    }

    /// <summary>Sets a single boolean data point, e.g. a switch.</summary>
    public Task SetDpAsync(string dpId, bool value, CancellationToken cancellationToken = default) =>
        SetDpsAsync(new TuyaDps().Set(dpId, value), cancellationToken);

    /// <summary>Sets a single numeric data point, e.g. brightness.</summary>
    public Task SetDpAsync(string dpId, int value, CancellationToken cancellationToken = default) =>
        SetDpsAsync(new TuyaDps().Set(dpId, value), cancellationToken);

    /// <summary>Sets a single string data point, e.g. a work mode.</summary>
    public Task SetDpAsync(string dpId, string value, CancellationToken cancellationToken = default) =>
        SetDpsAsync(new TuyaDps().Set(dpId, value), cancellationToken);

    /// <summary>Sends a heartbeat and returns true when the device answers.</summary>
    public async Task<bool> PingAsync(CancellationToken cancellationToken = default)
    {
        EnsureConnected();
        try
        {
            await SendRequestAsync(
                TuyaCommand.HeartBeat,
                Array.Empty<byte>(),
                new[] { TuyaCommand.HeartBeat, TuyaCommand.Status },
                cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (TuyaTimeoutException)
        {
            return false;
        }
    }

    /// <summary>
    /// Sends an arbitrary command with a raw payload and returns the reply.
    /// Use this for commands this library does not model.
    /// </summary>
    public async Task<TuyaMessage> SendRawCommandAsync(
        uint command,
        byte[] payload,
        uint[]? acceptedReplies = null,
        CancellationToken cancellationToken = default)
    {
        EnsureConnected();
        return await SendRequestAsync(
            command,
            payload,
            acceptedReplies ?? new[] { command, TuyaCommand.Status },
            cancellationToken).ConfigureAwait(false);
    }

    /// <inheritdoc />
    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        await DisconnectAsync().ConfigureAwait(false);
        _requestGate.Dispose();
        _writeGate.Dispose();
    }

    // ------------------------------------------------------------ internals

    private uint QueryCommand => _options.DeviceType == TuyaDeviceType.Device22 ? TuyaCommand.DpQueryNew : TuyaCommand.DpQuery;

    private uint ControlCommand => _options.DeviceType == TuyaDeviceType.Device22 ? TuyaCommand.ControlNew : TuyaCommand.Control;

    private void EnsureConnected()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_stream is null) throw new InvalidOperationException("the device is not connected; call ConnectAsync first");
    }

    private async Task NegotiateSessionKeyAsync(CancellationToken cancellationToken)
    {
        var clientNonce = TuyaSession.CreateNonce();
        await WriteAsync(_session.Encode(TuyaCommand.SessionKeyNegStart, clientNonce), cancellationToken).ConfigureAwait(false);

        TuyaRawFrame raw;
        using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
        {
            timeout.CancelAfter(_options.CommandTimeout);
            try
            {
                raw = await ReadFrameAsync(timeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                throw new TuyaHandshakeException("the device did not answer the session key request");
            }
        }

        if (raw.Command != TuyaCommand.SessionKeyNegResp)
        {
            throw new TuyaHandshakeException(
                $"expected a session key response (0x04), got command 0x{raw.Command:X2}. " +
                "The device probably speaks a different protocol version.");
        }

        var response = _session.Decode(raw);

        // The body is device_nonce(16) || HMAC(32). Some firmware prefixes a
        // 4-byte return code even here, which makes it 52 bytes; the retcode
        // auto-detection cannot see that because the first byte is random, so
        // accept both shapes explicitly.
        var body = response.Payload;
        if (body.Length == 52) body = body[4..];
        if (body.Length != 48)
        {
            throw new TuyaHandshakeException($"session key response was {response.Payload.Length} bytes, expected 48 or 52");
        }

        var deviceNonce = body.AsSpan(0, 16).ToArray();
        var expected = _session.ComputeNonceHmac(clientNonce);
        var actual = body.AsSpan(16, 32);

        if (!System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(expected, actual))
        {
            throw new TuyaHandshakeException(
                "the device rejected the local key (nonce HMAC mismatch). Check the 16-character local key.");
        }

        var finish = _session.ComputeNonceHmac(deviceNonce);
        await WriteAsync(_session.Encode(TuyaCommand.SessionKeyNegFinish, finish), cancellationToken).ConfigureAwait(false);

        _session.SetSessionKey(_session.DeriveSessionKey(clientNonce, deviceNonce));
    }

    private async Task<TuyaMessage> SendRequestAsync(
        uint command,
        byte[] payload,
        uint[] acceptedReplies,
        CancellationToken cancellationToken,
        bool tolerateTimeout = false)
    {
        await _requestGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var pending = new PendingRequest(acceptedReplies);
            _pending = pending;
            try
            {
                await WriteAsync(_session.Encode(command, payload), cancellationToken).ConfigureAwait(false);

                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                timeout.CancelAfter(_options.CommandTimeout);
                try
                {
                    return await pending.Completion.Task.WaitAsync(timeout.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                {
                    if (tolerateTimeout) return new TuyaMessage { Command = command };
                    throw new TuyaTimeoutException(
                        $"no reply to command 0x{command:X2} within {_options.CommandTimeout.TotalSeconds:0.#}s");
                }
            }
            finally
            {
                _pending = null;
            }
        }
        finally
        {
            _requestGate.Release();
        }
    }

    private async Task WriteAsync(byte[] frame, CancellationToken cancellationToken)
    {
        // All writes are serialised: the heartbeat loop writes outside _requestGate,
        // and NetworkStream gives no atomicity guarantee for concurrent writes, so
        // two frames could otherwise interleave on the wire and corrupt both.
        await _writeGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var stream = _stream ?? throw new InvalidOperationException("the device is not connected");
            await stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _writeGate.Release();
        }
    }

    private async Task<TuyaRawFrame> ReadFrameAsync(CancellationToken cancellationToken)
    {
        var stream = _stream ?? throw new InvalidOperationException("the device is not connected");
        while (true)
        {
            if (TuyaFrameCodec.TryReadFrame(_readBuffer.AsSpan(0, _readLength), out var frame))
            {
                int consumed = frame.Bytes.Length;
                Buffer.BlockCopy(_readBuffer, consumed, _readBuffer, 0, _readLength - consumed);
                _readLength -= consumed;
                return frame;
            }

            if (_readLength == _readBuffer.Length)
            {
                throw new TuyaProtocolException("receive buffer overflow; the stream is not a Tuya frame");
            }

            int read = await stream.ReadAsync(_readBuffer.AsMemory(_readLength), cancellationToken).ConfigureAwait(false);
            if (read == 0) throw new IOException("the device closed the connection");
            _readLength += read;
        }
    }

    private async Task ReceiveLoopAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var raw = await ReadFrameAsync(cancellationToken).ConfigureAwait(false);
                Dispatch(raw);
            }
        }
        catch (OperationCanceledException)
        {
            // normal shutdown
        }
        catch (Exception ex)
        {
            if (cancellationToken.IsCancellationRequested) return;
            _disconnected = true;
            Disconnected?.Invoke(this, ex);
        }
    }

    private async Task HeartbeatLoopAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                await Task.Delay(_options.HeartbeatInterval, cancellationToken).ConfigureAwait(false);
                try
                {
                    var frame = _session.Encode(TuyaCommand.HeartBeat, ReadOnlySpan<byte>.Empty);
                    await WriteAsync(frame, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception) when (cancellationToken.IsCancellationRequested)
                {
                    return;
                }
                catch (Exception ex)
                {
                    _disconnected = true;
                    Disconnected?.Invoke(this, ex);
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // normal shutdown
        }
    }

    private void Dispatch(TuyaRawFrame raw)
    {
        TuyaMessage message;
        try
        {
            message = _session.Decode(raw);
        }
        catch (TuyaException ex)
        {
            ProtocolError?.Invoke(this, new TuyaProtocolErrorEventArgs(ex));
            return;
        }

        MessageReceived?.Invoke(this, new TuyaMessageReceivedEventArgs(message));

        var pending = _pending;
        if (pending is not null && pending.Accepts(message.Command))
        {
            pending.Completion.TrySetResult(message);
            return;
        }

        if (TuyaCommand.CarriesDataPoints(message.Command) && message.Payload.Length > 0)
        {
            var status = TuyaStatus.Parse(message.Payload);
            if (status.HasData) StatusUpdated?.Invoke(this, new TuyaStatusEventArgs(status, message));
        }
    }

    private byte[] BuildQueryPayload()
    {
        var payload = new JsonObject
        {
            ["gwId"] = _options.DeviceId,
            ["devId"] = _options.DeviceId,
            ["uid"] = _options.DeviceId,
            ["t"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
        };

        if (!string.IsNullOrEmpty(_options.Cid)) payload["cid"] = _options.Cid;
        return JsonSerializer.SerializeToUtf8Bytes(payload);
    }

    private byte[] BuildControlPayload(IEnumerable<KeyValuePair<string, JsonNode?>> dps)
    {
        var dpsObject = new JsonObject();
        foreach (var pair in dps)
        {
            dpsObject[pair.Key] = pair.Value?.DeepClone();
        }

        var payload = new JsonObject();
        long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

        if (_options.Version.UsesSessionKey())
        {
            // 3.4/3.5: {"protocol":N,"t":...,"data":{"dps":{...}}}.
            // The legacy {devId,uid,t,dps} body is answered with
            // "data format error" and the data point never changes - verified
            // against 3.5 hardware, where queries kept working throughout and
            // made the rejection look like a dead control path.
            payload["protocol"] = _options.ProtocolNumber;
            payload["t"] = now;
            payload["data"] = new JsonObject { ["dps"] = dpsObject };
        }
        else
        {
            payload["devId"] = _options.DeviceId;
            payload["uid"] = _options.DeviceId;
            payload["t"] = now;
            payload["dps"] = dpsObject;
        }

        if (!string.IsNullOrEmpty(_options.Cid)) payload["cid"] = _options.Cid;
        return JsonSerializer.SerializeToUtf8Bytes(payload);
    }

    private sealed class PendingRequest
    {
        private readonly HashSet<uint> _accepted;

        public PendingRequest(uint[] accepted) => _accepted = new HashSet<uint>(accepted);

        public TaskCompletionSource<TuyaMessage> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public bool Accepts(uint command) => _accepted.Contains(command);
    }
}
