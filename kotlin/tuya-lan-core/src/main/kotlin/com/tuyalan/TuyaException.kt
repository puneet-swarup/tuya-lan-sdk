package com.tuyalan

/** Base type for every error raised by this library. */
open class TuyaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A frame could not be parsed, or a header field was out of range. */
class TuyaProtocolException(message: String, cause: Throwable? = null) : TuyaException(message, cause)

/** Decryption, signature or padding verification failed. */
class TuyaCryptoException(message: String, cause: Throwable? = null) : TuyaException(message, cause)

/**
 * The session key handshake failed. Usually means the local key is wrong or the
 * device is not the protocol version you configured.
 */
class TuyaHandshakeException(message: String, cause: Throwable? = null) : TuyaException(message, cause)

/** The device did not answer within the configured timeout. */
class TuyaTimeoutException(message: String) : TuyaException(message)

/** The cloud API returned `success: false`, or an HTTP error. */
class TuyaCloudException(
    message: String,
    /** Tuya error code such as "1010" or "TOKEN_INVALID". */
    val code: String? = null,
    /** HTTP status code, when the failure came from the transport layer. */
    val httpStatus: Int? = null,
    /** Raw response body, useful when debugging signature problems. */
    val body: String? = null,
) : TuyaException(message)
