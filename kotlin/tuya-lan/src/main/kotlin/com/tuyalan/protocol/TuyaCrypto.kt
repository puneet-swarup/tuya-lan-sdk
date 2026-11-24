package com.tuyalan.protocol

import com.tuyalan.TuyaCryptoException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives used by the Tuya LAN protocol: AES-128 in ECB mode
 * (3.3 / 3.4 payloads), AES-128-GCM (3.5 payloads), MD5 and HMAC-SHA256.
 *
 * Everything here works on raw bytes. AES-ECB is unauthenticated by design in
 * this protocol; the integrity guarantee comes from the CRC-32 or HMAC-SHA256
 * trailer on 3.3/3.4 and from the GCM tag on 3.5.
 */
object TuyaCrypto {
    const val BLOCK_SIZE = 16
    const val KEY_SIZE = 16
    const val GCM_NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16

    private val secureRandom = SecureRandom()

    /** Throws unless [key] is exactly 16 bytes. */
    @JvmStatic
    fun validateKey(key: ByteArray) {
        require(key.size == KEY_SIZE) { "a Tuya key must be $KEY_SIZE bytes, got ${key.size}" }
    }

    /** MD5 digest. Used to build the well-known discovery key. */
    @JvmStatic
    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    /** HMAC-SHA256, used by the 3.4 frame trailer and the handshake. */
    @JvmStatic
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** AES-128-ECB encryption, PKCS#7 padded to a 16-byte block. */
    @JvmStatic
    @JvmOverloads
    fun aesEcbEncrypt(key: ByteArray, plaintext: ByteArray, pad: Boolean = true): ByteArray {
        validateKey(key)
        val data = if (pad) pkcs7Pad(plaintext) else plaintext
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /** AES-128-ECB decryption with PKCS#7 unpadding. */
    @JvmStatic
    @JvmOverloads
    fun aesEcbDecrypt(key: ByteArray, ciphertext: ByteArray, unpad: Boolean = true): ByteArray {
        validateKey(key)
        if (ciphertext.isEmpty() || ciphertext.size % BLOCK_SIZE != 0) {
            throw TuyaCryptoException("ciphertext length ${ciphertext.size} is not a multiple of $BLOCK_SIZE")
        }

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val plain = cipher.doFinal(ciphertext)
        return if (unpad) pkcs7Unpad(plain) else plain
    }

    /** AES-128-GCM encryption. Returns the ciphertext and the 16-byte tag. */
    @JvmStatic
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        validateKey(key)
        require(nonce.size == GCM_NONCE_SIZE) { "GCM nonce must be $GCM_NONCE_SIZE bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val combined = cipher.doFinal(plaintext)
        val split = combined.size - GCM_TAG_SIZE
        return combined.copyOfRange(0, split) to combined.copyOfRange(split, combined.size)
    }

    /**
     * AES-128-GCM decryption. Throws [TuyaCryptoException] when the tag does not
     * verify, which is how a wrong local key surfaces on 3.5.
     */
    @JvmStatic
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray {
        validateKey(key)
        require(nonce.size == GCM_NONCE_SIZE) { "GCM nonce must be $GCM_NONCE_SIZE bytes" }
        require(tag.size == GCM_TAG_SIZE) { "GCM tag must be $GCM_TAG_SIZE bytes" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext + tag)
        } catch (e: BadPaddingException) {
            throw TuyaCryptoException(
                "GCM tag verification failed - the local key or the session key is wrong, or the frame was tampered with",
                e,
            )
        }
    }

    /** PKCS#7 padding to a 16-byte block boundary. */
    @JvmStatic
    fun pkcs7Pad(data: ByteArray): ByteArray {
        val padLength = BLOCK_SIZE - data.size % BLOCK_SIZE
        val padded = ByteArray(data.size + padLength)
        data.copyInto(padded)
        for (i in data.size until padded.size) padded[i] = padLength.toByte()
        return padded
    }

    /** PKCS#7 unpadding with validation of every padding byte. */
    @JvmStatic
    fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % BLOCK_SIZE != 0) {
            throw TuyaCryptoException("padded length ${data.size} is not a positive multiple of $BLOCK_SIZE")
        }

        val padLength = data.last().toInt() and 0xFF
        if (padLength < 1 || padLength > BLOCK_SIZE) {
            throw TuyaCryptoException("invalid padding length byte $padLength")
        }
        for (i in data.size - padLength until data.size) {
            if ((data[i].toInt() and 0xFF) != padLength) throw TuyaCryptoException("invalid padding data")
        }
        return data.copyOfRange(0, data.size - padLength)
    }

    /** CRC-32 (IEEE 802.3, the zlib polynomial) used as the 3.3 frame trailer. */
    @JvmStatic
    fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /** Cryptographically strong random bytes. */
    @JvmStatic
    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /** Lowercase hex, for logs and test vectors. */
    @JvmStatic
    fun toHex(data: ByteArray): String {
        val builder = StringBuilder(data.size * 2)
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            builder.append("0123456789abcdef"[value ushr 4])
            builder.append("0123456789abcdef"[value and 0x0F])
        }
        return builder.toString()
    }

    /** Parses a hex string, ignoring spaces, dashes and colons. */
    @JvmStatic
    fun fromHex(hex: String): ByteArray {
        val cleaned = hex.replace(" ", "").replace("-", "").replace(":", "").trim()
        require(cleaned.length % 2 == 0) { "hex string must have an even length" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            out[i] = cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
