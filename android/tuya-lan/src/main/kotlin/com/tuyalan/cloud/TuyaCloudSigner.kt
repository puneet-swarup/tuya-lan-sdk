package com.tuyalan.cloud

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds Tuya cloud API request signatures.
 *
 * The signature is HMAC-SHA256, hex encoded and upper-cased. What gets hashed
 * differs between the two API families:
 *
 * - Token APIs: `client_id + t + nonce + stringToSign`
 * - Business APIs: `client_id + access_token + t + nonce + stringToSign`
 *
 * and `stringToSign` is `METHOD \n SHA256hex(body) \n key:value lines \n URL`.
 * When no custom signature headers are used the key-value section is empty,
 * which leaves a blank line between the content hash and the URL.
 */
object TuyaCloudSigner {

    /**
     * Builds the path-plus-query string with parameters sorted by key, as the
     * signature requires. Null values are dropped.
     */
    @JvmStatic
    fun buildUrl(path: String, query: Map<String, String?>?): String {
        if (query.isNullOrEmpty()) return path

        val pairs = query.entries
            .filter { it.value != null }
            .sortedBy { it.key }

        if (pairs.isEmpty()) return path

        return buildString {
            append(path)
            append('?')
            pairs.forEachIndexed { index, entry ->
                if (index > 0) append('&')
                append(entry.key)
                append('=')
                append(entry.value)
            }
        }
    }

    /** Builds `stringToSign` from the four documented components. */
    @JvmStatic
    fun buildStringToSign(
        method: String,
        body: ByteArray?,
        signatureHeaders: List<Pair<String, String>>?,
        url: String,
    ): String {
        val optional = buildString {
            signatureHeaders?.forEach { (key, value) ->
                append(key)
                append(':')
                append(value)
                append('\n')
            }
        }

        val contentHash = toHex(MessageDigest.getInstance("SHA-256").digest(body ?: ByteArray(0)))
        return buildString {
            append(method.uppercase())
            append('\n')
            append(contentHash)
            append('\n')
            append(optional)
            append('\n')
            append(url)
        }
    }

    /** Signs a token management request. */
    @JvmStatic
    fun signToken(clientId: String, secret: String, timestamp: Long, nonce: String, stringToSign: String): String =
        hmac("$clientId$timestamp$nonce$stringToSign", secret)

    /** Signs a business request, which additionally binds the access token. */
    @JvmStatic
    fun signBusiness(
        clientId: String,
        accessToken: String,
        secret: String,
        timestamp: Long,
        nonce: String,
        stringToSign: String,
    ): String = hmac("$clientId$accessToken$timestamp$nonce$stringToSign", secret)

    /** Current 13-digit Unix timestamp in milliseconds. */
    @JvmStatic
    fun currentTimestamp(): Long = System.currentTimeMillis()

    /** A random UUID suitable for the `nonce` header. */
    @JvmStatic
    fun createNonce(): String = UUID.randomUUID().toString().replace("-", "")

    private fun hmac(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return toHex(mac.doFinal(payload.toByteArray(Charsets.UTF_8))).uppercase()
    }

    private fun toHex(data: ByteArray): String {
        val builder = StringBuilder(data.size * 2)
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            builder.append("0123456789abcdef"[value ushr 4])
            builder.append("0123456789abcdef"[value and 0x0F])
        }
        return builder.toString()
    }
}
