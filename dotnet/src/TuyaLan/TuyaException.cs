using System;

namespace TuyaLan;

/// <summary>Base type for every error raised by this library.</summary>
public class TuyaException : Exception
{
    public TuyaException(string message) : base(message) { }
    public TuyaException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>A frame could not be parsed, or a header field was out of range.</summary>
public sealed class TuyaProtocolException : TuyaException
{
    public TuyaProtocolException(string message) : base(message) { }
    public TuyaProtocolException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>Decryption, signature or padding verification failed.</summary>
public sealed class TuyaCryptoException : TuyaException
{
    public TuyaCryptoException(string message) : base(message) { }
    public TuyaCryptoException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>
/// The session key handshake failed. Usually means the local key is wrong or the
/// device is not the protocol version you configured.
/// </summary>
public sealed class TuyaHandshakeException : TuyaException
{
    public TuyaHandshakeException(string message) : base(message) { }
    public TuyaHandshakeException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>The device did not answer within the configured timeout.</summary>
public sealed class TuyaTimeoutException : TuyaException
{
    public TuyaTimeoutException(string message) : base(message) { }
}

/// <summary>The cloud API returned <c>success: false</c>, or an HTTP error.</summary>
public sealed class TuyaCloudException : TuyaException
{
    public TuyaCloudException(string message, string? code = null, int? httpStatus = null, string? body = null)
        : base(message)
    {
        Code = code;
        HttpStatus = httpStatus;
        Body = body;
    }

    /// <summary>Tuya error code such as "1010" or "TOKEN_INVALID".</summary>
    public string? Code { get; }

    /// <summary>HTTP status code, when the failure came from the transport layer.</summary>
    public int? HttpStatus { get; }

    /// <summary>Raw response body, useful when debugging signature problems.</summary>
    public string? Body { get; }
}
