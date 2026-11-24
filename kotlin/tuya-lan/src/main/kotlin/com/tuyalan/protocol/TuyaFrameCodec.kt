package com.tuyalan.protocol

import com.tuyalan.TuyaCryptoException
import com.tuyalan.TuyaProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Packs and unpacks Tuya LAN frames.
 *
 * **0x000055AA frame (3.3 / 3.4)**
 * ```
 * 000055aa | seq(4) | cmd(4) | length(4) | [retcode(4)] | ciphertext | trailer | 0000aa55
 * ```
 * `length` counts everything after the header field, including the trailer:
 * CRC-32 (4) + footer (4) on 3.3, or HMAC-SHA256 (32) + footer (4) on 3.4. The
 * return code is only present on device-to-client frames and sits in the clear.
 *
 * **0x00006699 frame (3.5)**
 * ```
 * 00006699 | reserved(2) | seq(4) | cmd(4) | length(4) | iv(12) | ciphertext | tag(16) | 00009966
 * ```
 * `length` counts the IV through the end of the tag and excludes the footer, so
 * `length = 12 + ciphertext.size + 16`. The associated data authenticated by GCM
 * is the 14 bytes after the prefix (reserved, seq, cmd, length). The return code,
 * when present, is the first four decrypted bytes.
 */
object TuyaFrameCodec {
    const val PREFIX_55AA = 0x000055AA
    const val SUFFIX_55AA = 0x0000AA55
    const val PREFIX_6699 = 0x00006699
    const val SUFFIX_6699 = 0x00009966

    const val HEADER_55AA = 16
    const val HEADER_6699 = 18

    /** Trailer length for a 3.3 frame: CRC-32 plus footer. */
    const val TRAILER_55AA = 8

    /** Trailer length for a 3.4 frame: HMAC-SHA256 plus footer. */
    const val TRAILER_HMAC = 36

    /** Trailer length for a 3.5 frame: GCM tag plus footer. */
    const val TRAILER_6699 = 20

    /**
     * Upper bound on a declared length. Anything larger is treated as a
     * desynchronised stream rather than a real frame.
     */
    const val MAX_PAYLOAD_LENGTH = 4096

    /** Builds a complete frame for [version]. */
    @JvmStatic
    @JvmOverloads
    fun pack(
        version: TuyaProtocolVersion,
        key: ByteArray,
        sequenceNumber: Long,
        command: Int,
        plaintext: ByteArray,
        returnCode: Long? = null,
        iv: ByteArray? = null,
        headerMode: VersionHeaderMode = VersionHeaderMode.INSIDE_CIPHERTEXT,
    ): ByteArray {
        TuyaCrypto.validateKey(key)
        return if (version.usesGcm) {
            pack6699(version, key, sequenceNumber, command, plaintext, returnCode, iv)
        } else {
            pack55aa(version, key, sequenceNumber, command, plaintext, returnCode, headerMode)
        }
    }

    /** Decodes a complete frame. */
    @JvmStatic
    @JvmOverloads
    fun unpack(
        version: TuyaProtocolVersion,
        key: ByteArray,
        frame: ByteArray,
        retcodePolicy: RetcodePolicy = RetcodePolicy.AUTO,
        headerMode: VersionHeaderMode = VersionHeaderMode.INSIDE_CIPHERTEXT,
    ): TuyaMessage {
        TuyaCrypto.validateKey(key)
        if (frame.size < 4) throw TuyaProtocolException("frame is shorter than a prefix")
        return when (val prefix = readInt(frame, 0)) {
            PREFIX_6699 -> unpack6699(key, frame, retcodePolicy)
            PREFIX_55AA -> unpack55aa(version, key, frame, retcodePolicy, headerMode)
            else -> throw TuyaProtocolException("unknown frame prefix 0x%08X".format(prefix))
        }
    }

    /**
     * Lifts one complete frame off the front of `buffer[offset until offset+length]`.
     * Returns null when more bytes are needed; throws when the stream cannot be a
     * Tuya frame at all.
     */
    @JvmStatic
    @JvmOverloads
    fun tryReadFrame(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): TuyaRawFrame? {
        if (length < 4) return null

        when (val prefix = readInt(buffer, offset)) {
            PREFIX_55AA -> {
                if (length < HEADER_55AA) return null
                val sequenceNumber = readUInt(buffer, offset + 4)
                val command = readInt(buffer, offset + 8)
                val declared = readInt(buffer, offset + 12)
                validateDeclaredLength(declared)
                val total = HEADER_55AA + declared
                if (length < total) return null
                return TuyaRawFrame(prefix, sequenceNumber, command, declared, buffer.copyOfRange(offset, offset + total))
            }

            PREFIX_6699 -> {
                if (length < HEADER_6699) return null
                val sequenceNumber = readUInt(buffer, offset + 6)
                val command = readInt(buffer, offset + 10)
                val declared = readInt(buffer, offset + 14)
                validateDeclaredLength(declared)
                val total = HEADER_6699 + declared + 4
                if (length < total) return null
                return TuyaRawFrame(prefix, sequenceNumber, command, declared, buffer.copyOfRange(offset, offset + total))
            }

            else -> throw TuyaProtocolException(
                "stream is not a Tuya frame: prefix 0x%08X (expected 0x%08X or 0x%08X)"
                    .format(prefix, PREFIX_55AA, PREFIX_6699),
            )
        }
    }

    private fun validateDeclaredLength(declared: Int) {
        if (declared < 0 || declared > MAX_PAYLOAD_LENGTH) {
            throw TuyaProtocolException("declared frame length $declared is out of range; the stream is probably corrupt")
        }
    }

    // ------------------------------------------------------------------ 3.5

    private fun pack6699(
        version: TuyaProtocolVersion,
        key: ByteArray,
        sequenceNumber: Long,
        command: Int,
        plaintext: ByteArray,
        returnCode: Long?,
        iv: ByteArray?,
    ): ByteArray {
        val carriesHeader = TuyaCommand.carriesVersionHeader(command)
        val versionHeader = version.versionHeader
        val body = if (carriesHeader) versionHeader + plaintext else plaintext
        val raw = if (returnCode != null) {
            val withReturnCode = ByteArray(4 + body.size)
            ByteBuffer.wrap(withReturnCode).order(ByteOrder.BIG_ENDIAN).putInt(returnCode.toInt())
            body.copyInto(withReturnCode, 4)
            withReturnCode
        } else {
            body
        }

        val length = TuyaCrypto.GCM_NONCE_SIZE + raw.size + TuyaCrypto.GCM_TAG_SIZE
        val frame = ByteArray(HEADER_6699 + length + 4)

        val header = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        header.putInt(PREFIX_6699)
        header.putShort(0.toShort()) // reserved
        header.putInt(sequenceNumber.toInt())
        header.putInt(command)
        header.putInt(length)

        val nonce = iv ?: TuyaCrypto.randomBytes(TuyaCrypto.GCM_NONCE_SIZE)
        require(nonce.size == TuyaCrypto.GCM_NONCE_SIZE) { "GCM nonce must be ${TuyaCrypto.GCM_NONCE_SIZE} bytes" }
        nonce.copyInto(frame, HEADER_6699)

        val associatedData = frame.copyOfRange(4, HEADER_6699)
        val (ciphertext, tag) = TuyaCrypto.aesGcmEncrypt(key, nonce, raw, associatedData)
        ciphertext.copyInto(frame, HEADER_6699 + TuyaCrypto.GCM_NONCE_SIZE)
        tag.copyInto(frame, HEADER_6699 + TuyaCrypto.GCM_NONCE_SIZE + ciphertext.size)

        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(frame.size - 4, SUFFIX_6699)
        return frame
    }

    private fun unpack6699(key: ByteArray, frame: ByteArray, retcodePolicy: RetcodePolicy): TuyaMessage {
        if (frame.size < HEADER_6699) throw TuyaProtocolException("frame is shorter than a 6699 header")

        val sequenceNumber = readUInt(frame, 6)
        val command = readInt(frame, 10)
        val length = readInt(frame, 14)
        val total = HEADER_6699 + length + 4

        if (length < TuyaCrypto.GCM_NONCE_SIZE + TuyaCrypto.GCM_TAG_SIZE) {
            throw TuyaProtocolException("6699 length field $length is too small")
        }
        if (frame.size < total) {
            throw TuyaProtocolException("truncated 6699 frame: need $total bytes, have ${frame.size}")
        }
        if (readInt(frame, total - 4) != SUFFIX_6699) {
            throw TuyaProtocolException("bad 6699 footer (expected 00009966)")
        }

        val associatedData = frame.copyOfRange(4, HEADER_6699)
        val nonce = frame.copyOfRange(HEADER_6699, HEADER_6699 + TuyaCrypto.GCM_NONCE_SIZE)
        val ciphertextLength = length - TuyaCrypto.GCM_NONCE_SIZE - TuyaCrypto.GCM_TAG_SIZE
        val ciphertext = frame.copyOfRange(HEADER_6699 + TuyaCrypto.GCM_NONCE_SIZE, HEADER_6699 + TuyaCrypto.GCM_NONCE_SIZE + ciphertextLength)
        val tag = frame.copyOfRange(HEADER_6699 + length - TuyaCrypto.GCM_TAG_SIZE, HEADER_6699 + length)

        val raw = TuyaCrypto.aesGcmDecrypt(key, nonce, ciphertext, tag, associatedData)
        val split = splitReturnCode(raw, retcodePolicy)
        val expectedHeader = TuyaProtocolVersion.V35.versionHeader
        val payload = if (TuyaCommand.carriesVersionHeader(command) && split.second.size >= expectedHeader.size && split.second.copyOfRange(0, expectedHeader.size).contentEquals(expectedHeader)) {
            split.second.copyOfRange(expectedHeader.size, split.second.size)
        } else split.second

        return TuyaMessage(
            prefix = PREFIX_6699,
            sequenceNumber = sequenceNumber,
            command = command,
            returnCode = split.first,
            payload = payload,
            iv = nonce,
        )
    }

    // ------------------------------------------------------------ 3.3 / 3.4

    private fun pack55aa(
        version: TuyaProtocolVersion,
        key: ByteArray,
        sequenceNumber: Long,
        command: Int,
        plaintext: ByteArray,
        returnCode: Long?,
        headerMode: VersionHeaderMode,
    ): ByteArray {
        val carriesHeader = TuyaCommand.carriesVersionHeader(command)
        val versionHeader = version.versionHeader

        val body: ByteArray
        val clearHeader: ByteArray
        when {
            carriesHeader && headerMode == VersionHeaderMode.PREPENDED -> {
                body = plaintext
                clearHeader = versionHeader
            }

            carriesHeader -> {
                body = ByteArray(versionHeader.size + plaintext.size)
                versionHeader.copyInto(body, 0)
                plaintext.copyInto(body, versionHeader.size)
                clearHeader = ByteArray(0)
            }

            else -> {
                body = plaintext
                clearHeader = ByteArray(0)
            }
        }

        val ciphertext = TuyaCrypto.aesEcbEncrypt(key, body)
        val hmac = version.usesHmac
        val trailerLength = if (hmac) TRAILER_HMAC else TRAILER_55AA
        val contentLength = (if (returnCode != null) 4 else 0) + clearHeader.size + ciphertext.size
        val length = contentLength + trailerLength

        val frame = ByteArray(HEADER_55AA + length)
        val header = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        header.putInt(PREFIX_55AA)
        header.putInt(sequenceNumber.toInt())
        header.putInt(command)
        header.putInt(length)

        var cursor = HEADER_55AA
        if (returnCode != null) {
            ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(cursor, returnCode.toInt())
            cursor += 4
        }
        clearHeader.copyInto(frame, cursor)
        cursor += clearHeader.size
        ciphertext.copyInto(frame, cursor)
        cursor += ciphertext.size

        val bigEndian = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (hmac) {
            val digest = TuyaCrypto.hmacSha256(key, frame.copyOfRange(0, cursor))
            digest.copyInto(frame, cursor)
            cursor += digest.size
        } else {
            bigEndian.putInt(cursor, TuyaCrypto.crc32(frame.copyOfRange(0, cursor)).toInt())
            cursor += 4
        }

        bigEndian.putInt(cursor, SUFFIX_55AA)
        return frame
    }

    private fun unpack55aa(
        version: TuyaProtocolVersion,
        key: ByteArray,
        frame: ByteArray,
        retcodePolicy: RetcodePolicy,
        headerMode: VersionHeaderMode,
    ): TuyaMessage {
        if (frame.size < HEADER_55AA) throw TuyaProtocolException("frame is shorter than a 55AA header")

        val sequenceNumber = readUInt(frame, 4)
        val command = readInt(frame, 8)
        val length = readInt(frame, 12)
        val total = HEADER_55AA + length
        if (length <= 0 || total > frame.size) {
            throw TuyaProtocolException("truncated 55AA frame: need $total bytes, have ${frame.size}")
        }

        val hmac = version.usesHmac
        val trailerLength = if (hmac) TRAILER_HMAC else TRAILER_55AA
        val contentLength = length - trailerLength
        if (contentLength < 0) throw TuyaProtocolException("55AA length field is smaller than the trailer")

        val carriesHeader = TuyaCommand.carriesVersionHeader(command)
        val clearVersionHeader = carriesHeader && headerMode == VersionHeaderMode.PREPENDED
        val encryptedVersionHeader = carriesHeader && headerMode == VersionHeaderMode.INSIDE_CIPHERTEXT
        val clearPrefix = if (clearVersionHeader) 15 else 0

        // On 3.3/3.4 the ciphertext is always a whole number of 16-byte blocks,
        // so the two candidate layouts - with and without a 4-byte return code -
        // can be told apart arithmetically rather than guessed.
        val available = contentLength - clearPrefix
        val fitsWithout = available > 0 && available % TuyaCrypto.BLOCK_SIZE == 0
        val fitsWith = available >= 4 && (available - 4) % TuyaCrypto.BLOCK_SIZE == 0
        val hasRetcode = when (retcodePolicy) {
            RetcodePolicy.ALWAYS -> true
            RetcodePolicy.NEVER -> false
            RetcodePolicy.AUTO -> fitsWith && !fitsWithout
        }

        val bodyStart = HEADER_55AA + (if (hasRetcode) 4 else 0) + clearPrefix
        val bodyEnd = total - trailerLength
        if (bodyEnd - bodyStart <= 0) throw TuyaProtocolException("55AA frame carries no ciphertext")

        if (hmac) {
            val expected = TuyaCrypto.hmacSha256(key, frame.copyOfRange(0, bodyEnd))
            val actual = frame.copyOfRange(bodyEnd, bodyEnd + 32)
            if (!MessageDigest.isEqual(expected, actual)) {
                throw TuyaCryptoException("HMAC-SHA256 mismatch - wrong key or a corrupt frame")
            }
        } else {
            val expected = readUInt(frame, bodyEnd)
            val actual = TuyaCrypto.crc32(frame.copyOfRange(0, bodyEnd))
            if (expected != actual) {
                throw TuyaCryptoException("CRC-32 mismatch: frame says 0x%08X, computed 0x%08X".format(expected, actual))
            }
        }

        if (readInt(frame, total - 4) != SUFFIX_55AA) {
            throw TuyaProtocolException("bad 55AA footer (expected 0000aa55)")
        }

        var plaintext = TuyaCrypto.aesEcbDecrypt(key, frame.copyOfRange(bodyStart, bodyEnd))
        if (encryptedVersionHeader && plaintext.size > 15 && looksLikeVersionHeader(plaintext)) {
            plaintext = plaintext.copyOfRange(15, plaintext.size)
        }

        val returnCode = if (hasRetcode) readUInt(frame, HEADER_55AA) else null

        return TuyaMessage(
            prefix = PREFIX_55AA,
            sequenceNumber = sequenceNumber,
            command = command,
            returnCode = returnCode,
            payload = plaintext,
        )
    }

    private fun looksLikeVersionHeader(data: ByteArray): Boolean =
        data.size >= 3 && data[0] == '3'.code.toByte() && data[1] == '.'.code.toByte() &&
            data[2] >= '0'.code.toByte() && data[2] <= '9'.code.toByte()

    private fun splitReturnCode(raw: ByteArray, policy: RetcodePolicy): Pair<Long?, ByteArray> {
        when (policy) {
            RetcodePolicy.ALWAYS -> {
                if (raw.size < 4) throw TuyaProtocolException("body is too short to hold a return code")
                return readUInt(raw, 0) to raw.copyOfRange(4, raw.size)
            }

            RetcodePolicy.NEVER -> return null to raw
            RetcodePolicy.AUTO -> Unit
        }

        if (raw.isEmpty()) return null to raw
        if (raw[0] == '{'.code.toByte() || raw[0] == '['.code.toByte()) return null to raw

        if (raw.size > 4 && (raw[4] == '{'.code.toByte() || raw[4] == '['.code.toByte())) {
            return readUInt(raw, 0) to raw.copyOfRange(4, raw.size)
        }

        // A version header ("3.5" + 12 zero bytes) also follows the return code.
        if (raw.size > 8 && looksLikeVersionHeader(raw.copyOfRange(4, raw.size))) {
            return readUInt(raw, 0) to raw.copyOfRange(4, raw.size)
        }

        return null to raw
    }

    // ------------------------------------------------------------- helpers

    /** Big-endian 32-bit read. Internal so the discovery decoder can reuse it. */
    internal fun readInt(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    internal fun readUInt(buffer: ByteArray, offset: Int): Long = readInt(buffer, offset).toLong() and 0xFFFFFFFFL
}
