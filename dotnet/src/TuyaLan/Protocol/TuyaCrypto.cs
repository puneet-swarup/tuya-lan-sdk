using System;
using System.Security.Cryptography;

namespace TuyaLan.Protocol;

/// <summary>
/// Cryptographic primitives used by the Tuya LAN protocol: AES-128 in ECB mode
/// (3.3 / 3.4 payloads), AES-128-GCM (3.5 payloads), MD5 and HMAC-SHA256.
/// </summary>
/// <remarks>
/// Everything here works on raw bytes. AES-ECB is unauthenticated by design in
/// this protocol; the integrity guarantee comes from the CRC-32 or HMAC-SHA256
/// trailer on 3.3/3.4 and from the GCM tag on 3.5.
/// </remarks>
public static class TuyaCrypto
{
    public const int BlockSize = 16;
    public const int KeySize = 16;
    public const int GcmNonceSize = 12;
    public const int GcmTagSize = 16;

    /// <summary>Throws unless <paramref name="key"/> is exactly 16 bytes.</summary>
    public static void ValidateKey(ReadOnlySpan<byte> key)
    {
        if (key.Length != KeySize)
        {
            throw new ArgumentException($"a Tuya key must be {KeySize} bytes, got {key.Length}", nameof(key));
        }
    }

    /// <summary>MD5 digest. Used to build the well-known discovery key.</summary>
    public static byte[] Md5(ReadOnlySpan<byte> data) => MD5.HashData(data);

    /// <summary>HMAC-SHA256, used by the 3.4 frame trailer and the handshake.</summary>
    public static byte[] HmacSha256(ReadOnlySpan<byte> key, ReadOnlySpan<byte> data) =>
        HMACSHA256.HashData(key, data);

    /// <summary>AES-128-ECB encryption, PKCS#7 padded to a 16-byte block.</summary>
    public static byte[] AesEcbEncrypt(ReadOnlySpan<byte> key, ReadOnlySpan<byte> plaintext, bool pad = true)
    {
        ValidateKey(key);
        var data = pad ? Pkcs7Pad(plaintext) : plaintext.ToArray();
        using var aes = Aes.Create();
        aes.Mode = CipherMode.ECB;
        aes.Padding = PaddingMode.None;
        aes.Key = key.ToArray();
        using var encryptor = aes.CreateEncryptor();
        return encryptor.TransformFinalBlock(data, 0, data.Length);
    }

    /// <summary>AES-128-ECB decryption with PKCS#7 unpadding.</summary>
    public static byte[] AesEcbDecrypt(ReadOnlySpan<byte> key, ReadOnlySpan<byte> ciphertext, bool unpad = true)
    {
        ValidateKey(key);
        if (ciphertext.Length == 0 || ciphertext.Length % BlockSize != 0)
        {
            throw new TuyaCryptoException($"ciphertext length {ciphertext.Length} is not a multiple of {BlockSize}");
        }

        using var aes = Aes.Create();
        aes.Mode = CipherMode.ECB;
        aes.Padding = PaddingMode.None;
        aes.Key = key.ToArray();
        using var decryptor = aes.CreateDecryptor();
        var plain = decryptor.TransformFinalBlock(ciphertext.ToArray(), 0, ciphertext.Length);
        return unpad ? Pkcs7Unpad(plain) : plain;
    }

    /// <summary>AES-128-GCM encryption. Writes ciphertext and tag into the supplied spans.</summary>
    public static void AesGcmEncrypt(
        ReadOnlySpan<byte> key,
        ReadOnlySpan<byte> nonce,
        ReadOnlySpan<byte> plaintext,
        Span<byte> ciphertext,
        Span<byte> tag,
        ReadOnlySpan<byte> associatedData)
    {
        ValidateKey(key);
        if (nonce.Length != GcmNonceSize) throw new ArgumentException($"GCM nonce must be {GcmNonceSize} bytes", nameof(nonce));
        if (tag.Length != GcmTagSize) throw new ArgumentException($"GCM tag must be {GcmTagSize} bytes", nameof(tag));
        if (ciphertext.Length != plaintext.Length) throw new ArgumentException("ciphertext span must match the plaintext length", nameof(ciphertext));

        using var gcm = new AesGcm(key);
        gcm.Encrypt(nonce, plaintext, ciphertext, tag, associatedData);
    }

    /// <summary>
    /// AES-128-GCM decryption. Throws <see cref="TuyaCryptoException"/> when the
    /// tag does not verify, which is how a wrong local key surfaces on 3.5.
    /// </summary>
    public static byte[] AesGcmDecrypt(
        ReadOnlySpan<byte> key,
        ReadOnlySpan<byte> nonce,
        ReadOnlySpan<byte> ciphertext,
        ReadOnlySpan<byte> tag,
        ReadOnlySpan<byte> associatedData)
    {
        ValidateKey(key);
        if (nonce.Length != GcmNonceSize) throw new ArgumentException($"GCM nonce must be {GcmNonceSize} bytes", nameof(nonce));
        if (tag.Length != GcmTagSize) throw new ArgumentException($"GCM tag must be {GcmTagSize} bytes", nameof(tag));

        var plaintext = new byte[ciphertext.Length];
        using var gcm = new AesGcm(key);
        try
        {
            gcm.Decrypt(nonce, ciphertext, tag, plaintext, associatedData);
        }
        catch (CryptographicException ex)
        {
            throw new TuyaCryptoException(
                "GCM tag verification failed - the local key or the session key is wrong, or the frame was tampered with",
                ex);
        }

        return plaintext;
    }

    /// <summary>PKCS#7 padding to a 16-byte block boundary.</summary>
    public static byte[] Pkcs7Pad(ReadOnlySpan<byte> data)
    {
        int padLength = BlockSize - data.Length % BlockSize;
        var padded = new byte[data.Length + padLength];
        data.CopyTo(padded);
        for (int i = data.Length; i < padded.Length; i++) padded[i] = (byte)padLength;
        return padded;
    }

    /// <summary>PKCS#7 unpadding with validation of every padding byte.</summary>
    public static byte[] Pkcs7Unpad(ReadOnlySpan<byte> data)
    {
        if (data.Length == 0 || data.Length % BlockSize != 0)
        {
            throw new TuyaCryptoException($"padded length {data.Length} is not a positive multiple of {BlockSize}");
        }

        int padLength = data[^1];
        if (padLength < 1 || padLength > BlockSize)
        {
            throw new TuyaCryptoException($"invalid padding length byte {padLength}");
        }

        for (int i = data.Length - padLength; i < data.Length; i++)
        {
            if (data[i] != padLength) throw new TuyaCryptoException("invalid padding data");
        }

        return data[..^padLength].ToArray();
    }

    /// <summary>Cryptographically strong random bytes.</summary>
    public static byte[] RandomBytes(int count) => RandomNumberGenerator.GetBytes(count);

    /// <summary>Lowercase hex, for logs and test vectors.</summary>
    public static string ToHex(ReadOnlySpan<byte> data) => Convert.ToHexString(data).ToLowerInvariant();

    /// <summary>Parses a hex string, ignoring spaces, dashes and colons.</summary>
    public static byte[] FromHex(string hex)
    {
        var cleaned = hex.Replace(" ", string.Empty).Replace("-", string.Empty).Replace(":", string.Empty).Trim();
        return Convert.FromHexString(cleaned);
    }
}
