using System;
using System.Text;

namespace TuyaLan.Protocol;

/// <summary>
/// LAN protocol revision a device speaks. Reported in the discovery broadcast as
/// the <c>version</c> field ("3.3", "3.4", "3.5").
/// </summary>
public enum TuyaProtocolVersion
{
    /// <summary>Legacy: 0x000055AA frame, AES-128-ECB with the static local key, CRC-32.</summary>
    V33 = 33,

    /// <summary>0x000055AA frame, per-connection session key, AES-128-ECB + HMAC-SHA256.</summary>
    V34 = 34,

    /// <summary>0x00006699 frame, per-connection session key, AES-128-GCM (default for current firmware).</summary>
    V35 = 35,
}

public static class TuyaProtocolVersionExtensions
{
    /// <summary>The "3.x" label used on the wire and in discovery JSON.</summary>
    public static string ToLabel(this TuyaProtocolVersion version) => version switch
    {
        TuyaProtocolVersion.V33 => "3.3",
        TuyaProtocolVersion.V34 => "3.4",
        TuyaProtocolVersion.V35 => "3.5",
        _ => throw new ArgumentOutOfRangeException(nameof(version), version, "unsupported protocol version"),
    };

    /// <summary>
    /// The 15-byte version header that precedes most payloads: 3 ASCII bytes
    /// ("3.5") followed by 12 zero bytes.
    /// </summary>
    public static byte[] BuildVersionHeader(this TuyaProtocolVersion version)
    {
        var header = new byte[15];
        Encoding.ASCII.GetBytes(version.ToLabel()).CopyTo(header, 0);
        return header;
    }

    /// <summary>True for 3.5, which uses AES-GCM and the 0x00006699 frame.</summary>
    public static bool UsesGcm(this TuyaProtocolVersion version) => version == TuyaProtocolVersion.V35;

    /// <summary>True for 3.4 and 3.5, which negotiate a per-connection session key.</summary>
    public static bool UsesSessionKey(this TuyaProtocolVersion version) => version >= TuyaProtocolVersion.V34;

    /// <summary>True for 3.4 and 3.5, which authenticate the frame with HMAC-SHA256 or a GCM tag.</summary>
    public static bool UsesHmac(this TuyaProtocolVersion version) => version >= TuyaProtocolVersion.V34;

    /// <summary>Parses a discovery <c>version</c> string. Unknown values fall back to 3.3.</summary>
    public static TuyaProtocolVersion ParseLabel(string? label) => label?.Trim() switch
    {
        "3.5" => TuyaProtocolVersion.V35,
        "3.4" => TuyaProtocolVersion.V34,
        "3.3" => TuyaProtocolVersion.V33,
        _ => TuyaProtocolVersion.V33,
    };
}
