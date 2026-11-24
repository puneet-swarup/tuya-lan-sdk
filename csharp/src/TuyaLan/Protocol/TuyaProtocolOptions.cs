using System;

namespace TuyaLan.Protocol;

/// <summary>
/// How to decide whether a decrypted body starts with a 4-byte return code.
/// Device-to-client frames on 3.3/3.4 carry the return code in the clear,
/// outside the ciphertext; on 3.5 it is the first four bytes of the plaintext.
/// Some firmware omits it entirely, so <see cref="Auto"/> is the safe default.
/// </summary>
public enum RetcodePolicy
{
    /// <summary>Inspect the body and strip four bytes only if they look like a return code.</summary>
    Auto = 0,

    /// <summary>Always treat the first four bytes as a big-endian return code.</summary>
    Always = 1,

    /// <summary>Never treat the body as carrying a return code.</summary>
    Never = 2,
}

/// <summary>
/// Where the 15-byte version header sits for 3.3/3.4 payloads. 3.5 always
/// encrypts it. Older 3.3 firmware has been observed both ways, so this is
/// exposed as a knob; leave it on the default unless a device rejects control.
/// </summary>
public enum VersionHeaderMode
{
    /// <summary>The version header is part of the encrypted body (3.4/3.5 behaviour).</summary>
    InsideCiphertext = 0,

    /// <summary>The version header is sent in the clear before the ciphertext.</summary>
    Prepended = 1,
}

/// <summary>Whether a device needs the "device22" command variants.</summary>
public enum TuyaDeviceType
{
    /// <summary>Normal device: CONTROL (0x07) and DP_QUERY (0x0A).</summary>
    Standard = 0,

    /// <summary>Older 22-character gwId devices: CONTROL_NEW (0x0D) and DP_QUERY_NEW (0x10).</summary>
    Device22 = 1,
}
