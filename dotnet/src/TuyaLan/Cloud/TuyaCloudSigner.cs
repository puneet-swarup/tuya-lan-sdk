using System;
using System.Collections.Generic;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace TuyaLan.Cloud;

/// <summary>
/// Builds Tuya cloud API request signatures.
/// </summary>
/// <remarks>
/// <para>
/// The signature is HMAC-SHA256, hex encoded and upper-cased. What gets hashed
/// differs between the two API families:
/// </para>
/// <list type="bullet">
///   <item><description>Token APIs: <c>client_id + t + nonce + stringToSign</c></description></item>
///   <item><description>Business APIs: <c>client_id + access_token + t + nonce + stringToSign</c></description></item>
/// </list>
/// <para>
/// and <c>stringToSign</c> is
/// <c>METHOD \n SHA256hex(body) \n key:value lines \n URL</c>. When no custom
/// signature headers are used the key-value section is empty, which leaves a
/// blank line between the content hash and the URL.
/// </para>
/// </remarks>
public static class TuyaCloudSigner
{
    /// <summary>
    /// Builds the path-plus-query string with parameters sorted by key, as the
    /// signature requires. Null values are dropped.
    /// </summary>
    public static string BuildUrl(string path, IEnumerable<KeyValuePair<string, string?>>? query)
    {
        if (query is null) return path;

        var sorted = new List<KeyValuePair<string, string>>();
        foreach (var pair in query)
        {
            if (pair.Value is null) continue;
            sorted.Add(new KeyValuePair<string, string>(pair.Key, pair.Value));
        }

        if (sorted.Count == 0) return path;
        sorted.Sort((a, b) => string.CompareOrdinal(a.Key, b.Key));

        var builder = new StringBuilder(path);
        builder.Append('?');
        for (int i = 0; i < sorted.Count; i++)
        {
            if (i > 0) builder.Append('&');
            builder.Append(sorted[i].Key).Append('=').Append(sorted[i].Value);
        }

        return builder.ToString();
    }

    /// <summary>Builds <c>stringToSign</c> from the four documented components.</summary>
    public static string BuildStringToSign(
        string method,
        byte[]? body,
        IReadOnlyList<KeyValuePair<string, string>>? signatureHeaders,
        string url)
    {
        var optional = new StringBuilder();
        if (signatureHeaders is not null)
        {
            foreach (var header in signatureHeaders)
            {
                optional.Append(header.Key).Append(':').Append(header.Value).Append('\n');
            }
        }

        string contentHash = Convert.ToHexString(SHA256.HashData(body ?? Array.Empty<byte>())).ToLowerInvariant();
        return string.Concat(method.ToUpperInvariant(), "\n", contentHash, "\n", optional.ToString(), "\n", url);
    }

    /// <summary>Signs a token management request.</summary>
    public static string SignToken(string clientId, string secret, long timestamp, string nonce, string stringToSign) =>
        Hmac(string.Concat(clientId, timestamp.ToString(CultureInfo.InvariantCulture), nonce, stringToSign), secret);

    /// <summary>Signs a business request, which additionally binds the access token.</summary>
    public static string SignBusiness(
        string clientId,
        string accessToken,
        string secret,
        long timestamp,
        string nonce,
        string stringToSign) =>
        Hmac(string.Concat(clientId, accessToken, timestamp.ToString(CultureInfo.InvariantCulture), nonce, stringToSign), secret);

    private static string Hmac(string payload, string secret) =>
        Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), Encoding.UTF8.GetBytes(payload)));

    /// <summary>Current 13-digit Unix timestamp in milliseconds.</summary>
    public static long CurrentTimestamp() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    /// <summary>A random UUID suitable for the <c>nonce</c> header.</summary>
    public static string CreateNonce() => Guid.NewGuid().ToString("N");
}
