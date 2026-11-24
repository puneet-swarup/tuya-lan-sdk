using System.Collections.Generic;

namespace TuyaLan.Protocol;

/// <summary>
/// Command identifiers carried in the frame header. Values match the Tuya
/// embedded SDK's <c>lan_protocol.h</c>.
/// </summary>
public static class TuyaCommand
{
    public const uint ApConfig = 0x01;
    public const uint Active = 0x02;

    /// <summary>Session key negotiation, step 1 (client nonce). 3.4 / 3.5 only.</summary>
    public const uint SessionKeyNegStart = 0x03;

    /// <summary>Session key negotiation, step 2 (device nonce + HMAC).</summary>
    public const uint SessionKeyNegResp = 0x04;

    /// <summary>Session key negotiation, step 3 (HMAC of the device nonce).</summary>
    public const uint SessionKeyNegFinish = 0x05;

    public const uint Unbind = 0x06;

    /// <summary>Set data points. The workhorse command for control.</summary>
    public const uint Control = 0x07;

    /// <summary>Data point report, sent by the device unsolicited.</summary>
    public const uint Status = 0x08;

    public const uint HeartBeat = 0x09;

    /// <summary>Query current data points.</summary>
    public const uint DpQuery = 0x0A;

    public const uint QueryWifi = 0x0B;
    public const uint TokenBind = 0x0C;

    /// <summary>Control variant used by older 22-character gwId devices ("device22").</summary>
    public const uint ControlNew = 0x0D;

    public const uint EnableWifi = 0x0E;
    public const uint WifiInfo = 0x0F;

    /// <summary>DP query variant used by "device22" devices; also a common status reply command.</summary>
    public const uint DpQueryNew = 0x10;

    public const uint SceneExecute = 0x11;

    /// <summary>Ask the device to refresh and push its data points.</summary>
    public const uint UpdateDps = 0x12;

    /// <summary>Encrypted discovery broadcast (UDP 6667).</summary>
    public const uint UdpNew = 0x13;

    public const uint ApConfigNew = 0x14;

    /// <summary>Discovery broadcast used by 3.4 firmware.</summary>
    public const uint BroadcastLpv34 = 0x23;

    /// <summary>Discovery solicitation request (UDP 7000).</summary>
    public const uint RequestDeviceInfo = 0x25;

    /// <summary>Extended stream, used for gateway / sub-device operations.</summary>
    public const uint LanExtStream = 0x40;

    private static readonly HashSet<uint> NoVersionHeaderCommands = new()
    {
        DpQuery,
        DpQueryNew,
        UpdateDps,
        HeartBeat,
        SessionKeyNegStart,
        SessionKeyNegResp,
        SessionKeyNegFinish,
        LanExtStream,
    };

    /// <summary>
    /// True when the payload for this command carries the 15-byte "3.x" version
    /// header. Query, heartbeat and handshake commands do not.
    /// </summary>
    public static bool CarriesVersionHeader(uint command) => !NoVersionHeaderCommands.Contains(command);

    /// <summary>True when the command is one of the session-key negotiation steps.</summary>
    public static bool IsHandshake(uint command) =>
        command is SessionKeyNegStart or SessionKeyNegResp or SessionKeyNegFinish;

    /// <summary>True when the command carries data points (for correlating replies).</summary>
    public static bool CarriesDataPoints(uint command) =>
        command is Control or Status or DpQuery or DpQueryNew or ControlNew or UpdateDps;
}
