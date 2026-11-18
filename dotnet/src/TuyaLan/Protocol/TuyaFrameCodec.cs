using System;
using System.Buffers.Binary;

namespace TuyaLan.Protocol;

/// <summary>
/// Packs and unpacks Tuya LAN frames.
/// </summary>
/// <remarks>
/// <para><b>0x000055AA frame (3.3 / 3.4)</b></para>
/// <code>
/// 000055aa | seq(4) | cmd(4) | length(4) | [retcode(4)] | ciphertext | trailer | 0000aa55
/// </code>
/// <para>
/// <c>length</c> counts everything after the header field, including the
/// trailer: CRC-32 (4 bytes) + footer (4 bytes) on 3.3, or HMAC-SHA256
/// (32 bytes) + footer (4 bytes) on 3.4. The return code is only present on
/// device-to-client frames and sits in the clear.
/// </para>
/// <para><b>0x00006699 frame (3.5)</b></para>
/// <code>
/// 00006699 | reserved(2) | seq(4) | cmd(4) | length(4) | iv(12) | ciphertext | tag(16) | 00009966
/// </code>
/// <para>
/// <c>length</c> counts the IV through the end of the tag and excludes the
/// footer, so <c>length = 12 + ciphertext.Length + 16</c>. The associated data
/// authenticated by GCM is the 14 bytes after the prefix (reserved, seq, cmd,
/// length). The return code, when present, is the first four decrypted bytes.
/// </para>
/// </remarks>
public static class TuyaFrameCodec
{
    public const uint Prefix55Aa = 0x000055AA;
    public const uint Suffix55Aa = 0x0000AA55;
    public const uint Prefix6699 = 0x00006699;
    public const uint Suffix6699 = 0x00009966;

    public const int HeaderLength55Aa = 16;
    public const int HeaderLength6699 = 18;

    /// <summary>Trailer length for a 3.3 frame: CRC-32 plus footer.</summary>
    public const int TrailerLength55Aa = 8;

    /// <summary>Trailer length for a 3.4 frame: HMAC-SHA256 plus footer.</summary>
    public const int TrailerLengthHmac = 36;

    /// <summary>Trailer length for a 3.5 frame: GCM tag plus footer.</summary>
    public const int TrailerLength6699 = 20;

    /// <summary>
    /// Upper bound on a declared length. Anything larger is treated as a
    /// desynchronised stream rather than a real frame.
    /// </summary>
    public const int MaxPayloadLength = 4096;

    /// <summary>Builds a complete frame for <paramref name="version"/>.</summary>
    /// <param name="version">Protocol revision, which selects frame and crypto.</param>
    /// <param name="key">Static local key, or the session key once negotiated.</param>
    /// <param name="sequenceNumber">Sequence number to put in the header.</param>
    /// <param name="command">Command identifier, see <see cref="TuyaCommand"/>.</param>
    /// <param name="plaintext">Payload before encryption (usually UTF-8 JSON).</param>
    /// <param name="returnCode">Return code, only for device-to-client frames. Normally <c>null</c>.</param>
    /// <param name="iv">12-byte GCM nonce; a random one is generated when omitted. Ignored below 3.5.</param>
    /// <param name="headerMode">Where the 15-byte version header goes (3.3/3.4 only).</param>
    public static byte[] Pack(
        TuyaProtocolVersion version,
        ReadOnlySpan<byte> key,
        uint sequenceNumber,
        uint command,
        ReadOnlySpan<byte> plaintext,
        uint? returnCode = null,
        byte[]? iv = null,
        VersionHeaderMode headerMode = VersionHeaderMode.InsideCiphertext)
    {
        TuyaCrypto.ValidateKey(key);
        return version.UsesGcm()
            ? Pack6699(key, sequenceNumber, command, plaintext, returnCode, iv)
            : Pack55Aa(version, key, sequenceNumber, command, plaintext, returnCode, headerMode);
    }

    /// <summary>Decodes a complete frame.</summary>
    public static TuyaMessage Unpack(
        TuyaProtocolVersion version,
        ReadOnlySpan<byte> key,
        ReadOnlySpan<byte> frame,
        RetcodePolicy retcodePolicy = RetcodePolicy.Auto,
        VersionHeaderMode headerMode = VersionHeaderMode.InsideCiphertext)
    {
        TuyaCrypto.ValidateKey(key);
        if (frame.Length < 4) throw new TuyaProtocolException("frame is shorter than a prefix");
        uint prefix = BinaryPrimitives.ReadUInt32BigEndian(frame);
        return prefix switch
        {
            Prefix6699 => Unpack6699(key, frame, retcodePolicy),
            Prefix55Aa => Unpack55Aa(version, key, frame, retcodePolicy, headerMode),
            _ => throw new TuyaProtocolException($"unknown frame prefix 0x{prefix:X8}"),
        };
    }

    /// <summary>
    /// Lifts one complete frame off the front of <paramref name="buffer"/>.
    /// Returns <c>false</c> when more bytes are needed; throws when the stream
    /// cannot be a Tuya frame at all.
    /// </summary>
    public static bool TryReadFrame(ReadOnlySpan<byte> buffer, out TuyaRawFrame frame)
    {
        frame = default;
        if (buffer.Length < 4) return false;

        uint prefix = BinaryPrimitives.ReadUInt32BigEndian(buffer);
        if (prefix == Prefix55Aa)
        {
            if (buffer.Length < HeaderLength55Aa) return false;
            uint sequenceNumber = BinaryPrimitives.ReadUInt32BigEndian(buffer[4..]);
            uint command = BinaryPrimitives.ReadUInt32BigEndian(buffer[8..]);
            int declared = checked((int)BinaryPrimitives.ReadUInt32BigEndian(buffer[12..]));
            ValidateDeclaredLength(declared);
            int total = HeaderLength55Aa + declared;
            if (buffer.Length < total) return false;
            frame = new TuyaRawFrame(prefix, sequenceNumber, command, declared, buffer[..total].ToArray());
            return true;
        }

        if (prefix == Prefix6699)
        {
            if (buffer.Length < HeaderLength6699) return false;
            uint sequenceNumber = BinaryPrimitives.ReadUInt32BigEndian(buffer[6..]);
            uint command = BinaryPrimitives.ReadUInt32BigEndian(buffer[10..]);
            int declared = checked((int)BinaryPrimitives.ReadUInt32BigEndian(buffer[14..]));
            ValidateDeclaredLength(declared);
            int total = HeaderLength6699 + declared + 4;
            if (buffer.Length < total) return false;
            frame = new TuyaRawFrame(prefix, sequenceNumber, command, declared, buffer[..total].ToArray());
            return true;
        }

        throw new TuyaProtocolException(
            $"stream is not a Tuya frame: prefix 0x{prefix:X8} (expected 0x{Prefix55Aa:X8} or 0x{Prefix6699:X8})");
    }

    private static void ValidateDeclaredLength(int declared)
    {
        if (declared < 0 || declared > MaxPayloadLength)
        {
            throw new TuyaProtocolException($"declared frame length {declared} is out of range; the stream is probably corrupt");
        }
    }

    // ---------------------------------------------------------------- 3.5

    private static byte[] Pack6699(
        ReadOnlySpan<byte> key,
        uint sequenceNumber,
        uint command,
        ReadOnlySpan<byte> plaintext,
        uint? returnCode,
        byte[]? iv)
    {
        int rawLength = plaintext.Length + (returnCode.HasValue ? 4 : 0);
        var raw = new byte[rawLength];
        int offset = 0;
        if (returnCode.HasValue)
        {
            BinaryPrimitives.WriteUInt32BigEndian(raw, returnCode.Value);
            offset = 4;
        }

        plaintext.CopyTo(raw.AsSpan(offset));

        int length = TuyaCrypto.GcmNonceSize + rawLength + TuyaCrypto.GcmTagSize;
        var frame = new byte[HeaderLength6699 + length + 4];

        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(0), Prefix6699);
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(4), 0); // reserved
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(6), sequenceNumber);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(10), command);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(14), (uint)length);

        var nonce = iv ?? TuyaCrypto.RandomBytes(TuyaCrypto.GcmNonceSize);
        if (nonce.Length != TuyaCrypto.GcmNonceSize)
        {
            throw new ArgumentException($"GCM nonce must be {TuyaCrypto.GcmNonceSize} bytes", nameof(iv));
        }

        nonce.CopyTo(frame.AsSpan(HeaderLength6699));

        var associatedData = frame.AsSpan(4, HeaderLength6699 - 4);
        var ciphertext = frame.AsSpan(HeaderLength6699 + TuyaCrypto.GcmNonceSize, rawLength);
        var tag = frame.AsSpan(HeaderLength6699 + TuyaCrypto.GcmNonceSize + rawLength, TuyaCrypto.GcmTagSize);

        TuyaCrypto.AesGcmEncrypt(key, nonce, raw, ciphertext, tag, associatedData);

        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(frame.Length - 4), Suffix6699);
        return frame;
    }

    private static TuyaMessage Unpack6699(ReadOnlySpan<byte> key, ReadOnlySpan<byte> frame, RetcodePolicy retcodePolicy)
    {
        if (frame.Length < HeaderLength6699) throw new TuyaProtocolException("frame is shorter than a 6699 header");

        uint sequenceNumber = BinaryPrimitives.ReadUInt32BigEndian(frame[6..]);
        uint command = BinaryPrimitives.ReadUInt32BigEndian(frame[10..]);
        int length = checked((int)BinaryPrimitives.ReadUInt32BigEndian(frame[14..]));
        int total = HeaderLength6699 + length + 4;

        if (length < TuyaCrypto.GcmNonceSize + TuyaCrypto.GcmTagSize)
        {
            throw new TuyaProtocolException($"6699 length field {length} is too small");
        }

        if (frame.Length < total) throw new TuyaProtocolException($"truncated 6699 frame: need {total} bytes, have {frame.Length}");

        if (BinaryPrimitives.ReadUInt32BigEndian(frame[(total - 4)..]) != Suffix6699)
        {
            throw new TuyaProtocolException("bad 6699 footer (expected 00009966)");
        }

        var associatedData = frame.Slice(4, HeaderLength6699 - 4);
        var nonce = frame.Slice(HeaderLength6699, TuyaCrypto.GcmNonceSize);
        int ciphertextLength = length - TuyaCrypto.GcmNonceSize - TuyaCrypto.GcmTagSize;
        var ciphertext = frame.Slice(HeaderLength6699 + TuyaCrypto.GcmNonceSize, ciphertextLength);
        var tag = frame.Slice(HeaderLength6699 + length - TuyaCrypto.GcmTagSize, TuyaCrypto.GcmTagSize);

        var raw = TuyaCrypto.AesGcmDecrypt(key, nonce, ciphertext, tag, associatedData);
        var (returnCode, payload) = SplitReturnCode(raw, retcodePolicy);

        return new TuyaMessage
        {
            Prefix = Prefix6699,
            SequenceNumber = sequenceNumber,
            Command = command,
            ReturnCode = returnCode,
            Payload = payload,
            InitializationVector = nonce.ToArray(),
        };
    }

    // ---------------------------------------------------------------- 3.3 / 3.4

    private static byte[] Pack55Aa(
        TuyaProtocolVersion version,
        ReadOnlySpan<byte> key,
        uint sequenceNumber,
        uint command,
        ReadOnlySpan<byte> plaintext,
        uint? returnCode,
        VersionHeaderMode headerMode)
    {
        bool carriesHeader = TuyaCommand.CarriesVersionHeader(command);
        var versionHeader = version.BuildVersionHeader();

        byte[] body;
        byte[] clearHeader = Array.Empty<byte>();
        if (carriesHeader && headerMode == VersionHeaderMode.Prepended)
        {
            body = plaintext.ToArray();
            clearHeader = versionHeader;
        }
        else if (carriesHeader)
        {
            body = new byte[versionHeader.Length + plaintext.Length];
            versionHeader.CopyTo(body, 0);
            plaintext.CopyTo(body.AsSpan(versionHeader.Length));
        }
        else
        {
            body = plaintext.ToArray();
        }

        var ciphertext = TuyaCrypto.AesEcbEncrypt(key, body);
        bool hmac = version.UsesHmac();
        int trailerLength = hmac ? TrailerLengthHmac : TrailerLength55Aa;
        int contentLength = (returnCode.HasValue ? 4 : 0) + clearHeader.Length + ciphertext.Length;
        int length = contentLength + trailerLength;

        var frame = new byte[HeaderLength55Aa + length];
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(0), Prefix55Aa);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(4), sequenceNumber);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(8), command);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(12), (uint)length);

        int cursor = HeaderLength55Aa;
        if (returnCode.HasValue)
        {
            BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(cursor), returnCode.Value);
            cursor += 4;
        }

        clearHeader.CopyTo(frame.AsSpan(cursor));
        cursor += clearHeader.Length;
        ciphertext.CopyTo(frame.AsSpan(cursor));
        cursor += ciphertext.Length;

        if (hmac)
        {
            var digest = TuyaCrypto.HmacSha256(key, frame.AsSpan(0, cursor));
            digest.CopyTo(frame.AsSpan(cursor));
            cursor += digest.Length;
        }
        else
        {
            BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(cursor), TuyaCrc32.Compute(frame.AsSpan(0, cursor)));
            cursor += 4;
        }

        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(cursor), Suffix55Aa);
        return frame;
    }

    private static TuyaMessage Unpack55Aa(
        TuyaProtocolVersion version,
        ReadOnlySpan<byte> key,
        ReadOnlySpan<byte> frame,
        RetcodePolicy retcodePolicy,
        VersionHeaderMode headerMode)
    {
        if (frame.Length < HeaderLength55Aa) throw new TuyaProtocolException("frame is shorter than a 55AA header");

        uint sequenceNumber = BinaryPrimitives.ReadUInt32BigEndian(frame[4..]);
        uint command = BinaryPrimitives.ReadUInt32BigEndian(frame[8..]);
        int length = checked((int)BinaryPrimitives.ReadUInt32BigEndian(frame[12..]));
        int total = HeaderLength55Aa + length;
        if (length <= 0 || total > frame.Length) throw new TuyaProtocolException($"truncated 55AA frame: need {total} bytes, have {frame.Length}");

        bool hmac = version.UsesHmac();
        int trailerLength = hmac ? TrailerLengthHmac : TrailerLength55Aa;
        int contentLength = length - trailerLength;
        if (contentLength < 0) throw new TuyaProtocolException("55AA length field is smaller than the trailer");

        bool carriesHeader = TuyaCommand.CarriesVersionHeader(command);
        bool clearVersionHeader = carriesHeader && headerMode == VersionHeaderMode.Prepended;
        bool encryptedVersionHeader = carriesHeader && headerMode == VersionHeaderMode.InsideCiphertext;
        int clearPrefix = clearVersionHeader ? 15 : 0;

        // On 3.3/3.4 the ciphertext is always a whole number of 16-byte blocks,
        // so the two candidate layouts - with and without a 4-byte return code -
        // can be told apart arithmetically rather than guessed.
        int available = contentLength - clearPrefix;
        bool fitsWithout = available > 0 && available % TuyaCrypto.BlockSize == 0;
        bool fitsWith = available >= 4 && (available - 4) % TuyaCrypto.BlockSize == 0;
        bool hasRetcode = retcodePolicy switch
        {
            RetcodePolicy.Always => true,
            RetcodePolicy.Never => false,
            _ => fitsWith && !fitsWithout,
        };

        int bodyStart = HeaderLength55Aa + (hasRetcode ? 4 : 0) + clearPrefix;
        int bodyEnd = total - trailerLength;
        if (bodyEnd - bodyStart <= 0) throw new TuyaProtocolException("55AA frame carries no ciphertext");

        if (hmac)
        {
            var expected = TuyaCrypto.HmacSha256(key, frame[..bodyEnd]);
            var actual = frame.Slice(bodyEnd, 32);
            if (!System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(expected, actual))
            {
                throw new TuyaCryptoException("HMAC-SHA256 mismatch - wrong key or a corrupt frame");
            }
        }
        else
        {
            uint expected = BinaryPrimitives.ReadUInt32BigEndian(frame[bodyEnd..]);
            uint actual = TuyaCrc32.Compute(frame[..bodyEnd]);
            if (expected != actual)
            {
                throw new TuyaCryptoException($"CRC-32 mismatch: frame says 0x{expected:X8}, computed 0x{actual:X8}");
            }
        }

        if (BinaryPrimitives.ReadUInt32BigEndian(frame[(total - 4)..]) != Suffix55Aa)
        {
            throw new TuyaProtocolException("bad 55AA footer (expected 0000aa55)");
        }

        var plaintext = TuyaCrypto.AesEcbDecrypt(key, frame[bodyStart..bodyEnd]);

        if (encryptedVersionHeader && plaintext.Length > 15 && LooksLikeVersionHeader(plaintext.AsSpan(0, 3)))
        {
            plaintext = plaintext[15..];
        }

        uint? returnCode = hasRetcode
            ? BinaryPrimitives.ReadUInt32BigEndian(frame.Slice(HeaderLength55Aa, 4))
            : null;

        return new TuyaMessage
        {
            Prefix = Prefix55Aa,
            SequenceNumber = sequenceNumber,
            Command = command,
            ReturnCode = returnCode,
            Payload = plaintext,
        };
    }

    private static bool LooksLikeVersionHeader(ReadOnlySpan<byte> firstThree) =>
        firstThree.Length == 3 && firstThree[0] == (byte)'3' && firstThree[1] == (byte)'.' &&
        firstThree[2] >= (byte)'0' && firstThree[2] <= (byte)'9';

    private static (uint? ReturnCode, byte[] Payload) SplitReturnCode(byte[] raw, RetcodePolicy policy)
    {
        if (policy == RetcodePolicy.Always)
        {
            if (raw.Length < 4) throw new TuyaProtocolException("body is too short to hold a return code");
            return (BinaryPrimitives.ReadUInt32BigEndian(raw), raw[4..]);
        }

        if (policy == RetcodePolicy.Never || raw.Length == 0) return (null, raw);

        if (raw[0] == (byte)'{' || raw[0] == (byte)'[') return (null, raw);

        if (raw.Length > 4 && (raw[4] == (byte)'{' || raw[4] == (byte)'['))
        {
            return (BinaryPrimitives.ReadUInt32BigEndian(raw), raw[4..]);
        }

        // A version header ("3.5" + 12 zero bytes) also follows the return code.
        if (raw.Length > 8 && LooksLikeVersionHeader(raw.AsSpan(4, 3)))
        {
            return (BinaryPrimitives.ReadUInt32BigEndian(raw), raw[4..]);
        }

        return (null, raw);
    }
}
