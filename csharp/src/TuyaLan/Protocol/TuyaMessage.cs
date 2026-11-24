using System;
using System.Text;

namespace TuyaLan.Protocol;

/// <summary>A decoded Tuya message: header fields plus the decrypted payload.</summary>
public sealed class TuyaMessage
{
    /// <summary>Frame prefix, <see cref="TuyaFrameCodec.Prefix55Aa"/> or <see cref="TuyaFrameCodec.Prefix6699"/>.</summary>
    public uint Prefix { get; init; }

    /// <summary>Sequence number from the frame header.</summary>
    public uint SequenceNumber { get; init; }

    /// <summary>Command identifier, see <see cref="TuyaCommand"/>.</summary>
    public uint Command { get; init; }

    /// <summary>Return code if the frame carried one, otherwise <c>null</c>.</summary>
    public uint? ReturnCode { get; init; }

    /// <summary>Decrypted payload (usually UTF-8 JSON).</summary>
    public byte[] Payload { get; init; } = Array.Empty<byte>();

    /// <summary>The 12-byte GCM nonce, for 3.5 frames only.</summary>
    public byte[]? InitializationVector { get; init; }

    /// <summary>Payload decoded as UTF-8.</summary>
    public string PayloadText => Encoding.UTF8.GetString(Payload);

    /// <summary>True when the return code, if present, indicates success.</summary>
    public bool IsSuccess => ReturnCode is null or 0;

    public override string ToString() =>
        $"cmd=0x{Command:X2} seq={SequenceNumber} retcode={(ReturnCode?.ToString() ?? "-")} " +
        $"payload={Truncate(PayloadText, 160)}";

    private static string Truncate(string value, int max) =>
        value.Length <= max ? value : value.Substring(0, max) + "...";
}

/// <summary>
/// A raw, still-encrypted frame lifted off the socket. Produced by
/// <see cref="TuyaFrameCodec.TryReadFrame"/> and decoded by
/// <see cref="TuyaSession.Decode(TuyaRawFrame)"/>.
/// </summary>
public readonly struct TuyaRawFrame
{
    public TuyaRawFrame(uint prefix, uint sequenceNumber, uint command, int declaredLength, byte[] bytes)
    {
        Prefix = prefix;
        SequenceNumber = sequenceNumber;
        Command = command;
        DeclaredLength = declaredLength;
        Bytes = bytes;
    }

    public uint Prefix { get; }
    public uint SequenceNumber { get; }
    public uint Command { get; }

    /// <summary>The value of the header length field (excludes the 4-byte footer on 3.5).</summary>
    public int DeclaredLength { get; }

    /// <summary>The complete frame, header through footer.</summary>
    public byte[] Bytes { get; }
}
