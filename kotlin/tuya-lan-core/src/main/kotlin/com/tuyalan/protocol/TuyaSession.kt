package com.tuyalan.protocol

import com.tuyalan.TuyaHandshakeException
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the state of one connection to a device: the protocol version, the
 * static local key, the negotiated session key and the sequence counter.
 *
 * On 3.4 and 3.5 every frame after the handshake is encrypted with a session key
 * derived from the local key and two random nonces, so the static local key
 * never travels and cannot be replayed. [activeKey] returns the local key until
 * [setSessionKey] is called, which is exactly what the handshake frames need.
 */
class TuyaSession(
    val version: TuyaProtocolVersion,
    localKey: ByteArray,
) {
    private val localKey: ByteArray = localKey.copyOf()
    private var sessionKey: ByteArray? = null
    private val sequence = AtomicLong(0)

    init {
        TuyaCrypto.validateKey(localKey)
    }

    /** The key to encrypt and decrypt with: the session key once negotiated, otherwise the local key. */
    val activeKey: ByteArray get() = sessionKey ?: localKey

    /** True once the 3.4/3.5 handshake has completed. */
    val hasSessionKey: Boolean get() = sessionKey != null

    /** Where the 15-byte version header goes for 3.3/3.4 frames. */
    var headerMode: VersionHeaderMode = VersionHeaderMode.INSIDE_CIPHERTEXT

    /** How to treat a leading return code when decoding. */
    var retcodePolicy: RetcodePolicy = RetcodePolicy.AUTO

    /** Increments and returns the next sequence number (uint32 semantics). */
    fun nextSequenceNumber(): Long = sequence.incrementAndGet() and 0xFFFFFFFFL

    /** Current sequence number without incrementing. */
    fun currentSequenceNumber(): Long = sequence.get() and 0xFFFFFFFFL

    /** Encrypts and frames one message. */
    @JvmOverloads
    fun encode(command: Int, plaintext: ByteArray, returnCode: Long? = null, iv: ByteArray? = null): ByteArray =
        TuyaFrameCodec.pack(version, activeKey, nextSequenceNumber(), command, plaintext, returnCode, iv, headerMode)

    /** Decrypts one raw frame. */
    fun decode(frame: TuyaRawFrame): TuyaMessage =
        TuyaFrameCodec.unpack(version, activeKey, frame.bytes, retcodePolicy, headerMode)

    /** Decrypts one raw frame from its bytes. */
    fun decode(frameBytes: ByteArray): TuyaMessage =
        TuyaFrameCodec.unpack(version, activeKey, frameBytes, retcodePolicy, headerMode)

    /** Installs the session key produced by the handshake. */
    fun setSessionKey(key: ByteArray) {
        TuyaCrypto.validateKey(key)
        sessionKey = key.copyOf()
    }

    /** Forgets the session key, e.g. after the socket drops. */
    fun clearSessionKey() {
        sessionKey = null
    }

    /**
     * HMAC-SHA256 of a nonce, keyed with the **static local key**. Used to prove
     * possession of the key in both directions of the handshake.
     */
    fun computeNonceHmac(nonce: ByteArray): ByteArray = TuyaCrypto.hmacSha256(localKey, nonce)

    /**
     * Derives the session key from the two nonces.
     *
     * 3.4: `AES-128-ECB(localKey, clientNonce XOR deviceNonce)`.
     * 3.5: GCM-encrypt the XOR with the local key and the first 12 bytes of the
     * client nonce as the nonce, then keep the 16-byte ciphertext.
     */
    fun deriveSessionKey(clientNonce: ByteArray, deviceNonce: ByteArray): ByteArray {
        if (clientNonce.size != 16) throw TuyaHandshakeException("client nonce must be 16 bytes")
        if (deviceNonce.size != 16) throw TuyaHandshakeException("device nonce must be 16 bytes")

        val mixed = ByteArray(16) { index -> (clientNonce[index].toInt() xor deviceNonce[index].toInt()).toByte() }

        if (version.usesGcm) {
            // The GCM output is iv(12) || ciphertext(16) || tag(16); the device
            // uses the ciphertext block as the session key.
            val nonce = clientNonce.copyOfRange(0, TuyaCrypto.GCM_NONCE_SIZE)
            val (ciphertext, _) = TuyaCrypto.aesGcmEncrypt(localKey, nonce, mixed, ByteArray(0))
            return ciphertext
        }

        return TuyaCrypto.aesEcbEncrypt(localKey, mixed, pad = false)
    }

    companion object {
        /** A fresh 16-byte client nonce for the handshake. */
        @JvmStatic
        fun createNonce(): ByteArray = TuyaCrypto.randomBytes(16)
    }
}
