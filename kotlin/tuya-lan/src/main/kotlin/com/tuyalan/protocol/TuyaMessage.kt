package com.tuyalan.protocol

/** A decoded Tuya message: header fields plus the decrypted payload. */
class TuyaMessage(
    /** Frame prefix, [TuyaFrameCodec.PREFIX_55AA] or [TuyaFrameCodec.PREFIX_6699]. */
    val prefix: Int,
    /** Sequence number from the frame header. */
    val sequenceNumber: Long,
    /** Command identifier, see [TuyaCommand]. */
    val command: Int,
    /** Return code if the frame carried one, otherwise null. */
    val returnCode: Long?,
    /** Decrypted payload (usually UTF-8 JSON). */
    val payload: ByteArray,
    /** The 12-byte GCM nonce, for 3.5 frames only. */
    val iv: ByteArray? = null,
) {
    /** Payload decoded as UTF-8. */
    val payloadText: String get() = String(payload, Charsets.UTF_8)

    /** True when the return code, if present, indicates success. */
    val isSuccess: Boolean get() = returnCode == null || returnCode == 0L

    override fun toString(): String {
        val text = payloadText
        val shown = if (text.length <= 160) text else text.take(160) + "..."
        return "cmd=0x%02X seq=%d retcode=%s payload=%s".format(command, sequenceNumber, returnCode?.toString() ?: "-", shown)
    }
}

/**
 * A raw, still-encrypted frame lifted off the socket. Produced by
 * [TuyaFrameCodec.tryReadFrame] and decoded by [TuyaSession.decode].
 */
class TuyaRawFrame(
    val prefix: Int,
    val sequenceNumber: Long,
    val command: Int,
    /** The value of the header length field (excludes the 4-byte footer on 3.5). */
    val declaredLength: Int,
    /** The complete frame, header through footer. */
    val bytes: ByteArray,
)
