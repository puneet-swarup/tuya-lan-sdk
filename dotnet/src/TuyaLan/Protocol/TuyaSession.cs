using System;
using System.Threading;

namespace TuyaLan.Protocol;

/// <summary>
/// Holds the state of one connection to a device: the protocol version, the
/// static local key, the negotiated session key and the sequence counter.
/// </summary>
/// <remarks>
/// On 3.4 and 3.5 every frame after the handshake is encrypted with a session
/// key derived from the local key and two random nonces, so the static local key
/// never travels and cannot be replayed. <see cref="ActiveKey"/> returns the
/// local key until <see cref="SetSessionKey"/> is called, which is exactly what
/// the handshake frames themselves need.
/// </remarks>
public sealed class TuyaSession
{
    private readonly byte[] _localKey;
    private byte[]? _sessionKey;
    private uint _sequenceNumber;

    public TuyaSession(TuyaProtocolVersion version, byte[] localKey)
    {
        TuyaCrypto.ValidateKey(localKey);
        Version = version;
        _localKey = (byte[])localKey.Clone();
    }

    /// <summary>Protocol revision for this connection.</summary>
    public TuyaProtocolVersion Version { get; }

    /// <summary>The 16-byte local key taken from the Tuya app or the cloud API.</summary>
    public ReadOnlyMemory<byte> LocalKey => _localKey;

    /// <summary>The key to encrypt and decrypt with: the session key once negotiated, otherwise the local key.</summary>
    public byte[] ActiveKey => _sessionKey ?? _localKey;

    /// <summary>True once the 3.4/3.5 handshake has completed.</summary>
    public bool HasSessionKey => _sessionKey is not null;

    /// <summary>Where the 15-byte version header goes for 3.3/3.4 frames.</summary>
    public VersionHeaderMode HeaderMode { get; set; } = VersionHeaderMode.InsideCiphertext;

    /// <summary>How to treat a leading return code when decoding.</summary>
    public RetcodePolicy RetcodePolicy { get; set; } = RetcodePolicy.Auto;

    /// <summary>Increments and returns the next sequence number.</summary>
    public uint NextSequenceNumber() => Interlocked.Increment(ref _sequenceNumber);

    /// <summary>Current sequence number without incrementing.</summary>
    public uint SequenceNumber => Volatile.Read(ref _sequenceNumber);

    /// <summary>Encrypts and frames one message.</summary>
    public byte[] Encode(uint command, ReadOnlySpan<byte> plaintext, uint? returnCode = null, byte[]? iv = null) =>
        TuyaFrameCodec.Pack(Version, ActiveKey, NextSequenceNumber(), command, plaintext, returnCode, iv, HeaderMode);

    /// <summary>Decrypts one raw frame.</summary>
    public TuyaMessage Decode(TuyaRawFrame frame) =>
        TuyaFrameCodec.Unpack(Version, ActiveKey, frame.Bytes, RetcodePolicy, HeaderMode);

    /// <summary>Decrypts one raw frame from its bytes.</summary>
    public TuyaMessage Decode(ReadOnlySpan<byte> frameBytes) =>
        TuyaFrameCodec.Unpack(Version, ActiveKey, frameBytes, RetcodePolicy, HeaderMode);

    /// <summary>Installs the session key produced by the handshake.</summary>
    public void SetSessionKey(byte[] sessionKey)
    {
        TuyaCrypto.ValidateKey(sessionKey);
        _sessionKey = (byte[])sessionKey.Clone();
    }

    /// <summary>Forgets the session key, e.g. after the socket drops.</summary>
    public void ClearSessionKey() => _sessionKey = null;

    /// <summary>A fresh 16-byte client nonce for the handshake.</summary>
    public static byte[] CreateNonce() => TuyaCrypto.RandomBytes(16);

    /// <summary>
    /// HMAC-SHA256 of a nonce, keyed with the <em>static local key</em>. Used to
    /// prove possession of the key in both directions of the handshake.
    /// </summary>
    public byte[] ComputeNonceHmac(ReadOnlySpan<byte> nonce) => TuyaCrypto.HmacSha256(_localKey, nonce);

    /// <summary>
    /// Derives the session key from the two nonces.
    /// </summary>
    /// <remarks>
    /// 3.4: <c>AES-128-ECB(localKey, clientNonce XOR deviceNonce)</c>.
    /// 3.5: GCM-encrypt the XOR with the local key and the first 12 bytes of the
    /// client nonce as the nonce, then keep the 16-byte ciphertext.
    /// </remarks>
    public byte[] DeriveSessionKey(ReadOnlySpan<byte> clientNonce, ReadOnlySpan<byte> deviceNonce)
    {
        if (clientNonce.Length != 16) throw new ArgumentException("client nonce must be 16 bytes", nameof(clientNonce));
        if (deviceNonce.Length != 16) throw new ArgumentException("device nonce must be 16 bytes", nameof(deviceNonce));

        var mixed = new byte[16];
        for (int i = 0; i < 16; i++) mixed[i] = (byte)(clientNonce[i] ^ deviceNonce[i]);

        if (Version.UsesGcm())
        {
            // The GCM output is iv(12) || ciphertext(16) || tag(16); the device
            // uses the ciphertext block as the session key.
            var nonce = clientNonce[..TuyaCrypto.GcmNonceSize].ToArray();
            var ciphertext = new byte[16];
            var tag = new byte[TuyaCrypto.GcmTagSize];
            TuyaCrypto.AesGcmEncrypt(_localKey, nonce, mixed, ciphertext, tag, ReadOnlySpan<byte>.Empty);
            return ciphertext;
        }

        return TuyaCrypto.AesEcbEncrypt(_localKey, mixed, pad: false);
    }
}
