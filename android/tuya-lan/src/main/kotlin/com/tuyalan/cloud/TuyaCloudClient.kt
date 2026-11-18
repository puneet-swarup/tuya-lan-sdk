package com.tuyalan.cloud

import com.tuyalan.TuyaCloudException
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Client for the Tuya Cloud API: token management, device listing, status and
 * control.
 *
 * Every request is signed with HMAC-SHA256 as described in [TuyaCloudSigner].
 * The client caches the access token, refreshes it with the refresh token when
 * it is close to expiry, and retries once with a fresh token when the API
 * answers with a token error.
 *
 * This is the cloud path, which needs internet access and a Tuya IoT project.
 * For local control without the cloud, use [com.tuyalan.devices.TuyaLocalDevice].
 *
 * ```kotlin
 * val cloud = TuyaCloudClient(
 *     TuyaCloudOptions(clientId = "...", secret = "...", region = TuyaCloudRegion.INDIA),
 * )
 * val devices = cloud.getDevices()
 * val status = cloud.getStatus(devices.first().id)
 * cloud.sendCommands(devices.first().id, listOf(TuyaCloudCommand("switch_1", true)))
 * ```
 */
class TuyaCloudClient(
    private val options: TuyaCloudOptions,
    private val http: OkHttpClient = OkHttpClient(),
) : Closeable {

    private val tokenMutex = Mutex()
    private var token: TuyaCloudToken? = null

    /** The endpoint currently in use. */
    val baseUrl: String get() = options.baseUrl

    // ------------------------------------------------------------- tokens

    /** Returns a usable access token, fetching or refreshing it when needed. */
    @JvmOverloads
    suspend fun getToken(forceRefresh: Boolean = false): TuyaCloudToken = tokenMutex.withLock {
        val cached = token
        if (!forceRefresh && cached != null && cached.isFresh) return@withLock cached

        if (!forceRefresh && cached != null && cached.refreshToken.isNotEmpty()) {
            try {
                return@withLock refreshToken(cached.refreshToken)
            } catch (e: TuyaCloudException) {
                // fall through to a full token request
            }
        }

        requestToken()
    }

    private suspend fun requestToken(): TuyaCloudToken {
        val root = execute("GET", "/v1.0/token", mapOf("grant_type" to "1"), null, useToken = false, retryOnTokenError = false)
        val result = root.optJSONObject("result")
            ?: throw TuyaCloudException("the token endpoint returned no access token")
        return store(TuyaCloudToken.fromJson(result))
    }

    private suspend fun refreshToken(refreshToken: String): TuyaCloudToken {
        val root = execute("GET", "/v1.0/token/$refreshToken", null, null, useToken = false, retryOnTokenError = false)
        val result = root.optJSONObject("result")
            ?: throw TuyaCloudException("the refresh endpoint returned no access token")
        return store(TuyaCloudToken.fromJson(result))
    }

    private fun store(newToken: TuyaCloudToken): TuyaCloudToken {
        if (newToken.accessToken.isEmpty()) {
            throw TuyaCloudException("the token endpoint returned an empty access token")
        }
        token = newToken
        return newToken
    }

    // -------------------------------------------------------------- devices

    /** All devices on the project's linked account. */
    suspend fun getDevices(): List<TuyaCloudDevice> = devicesFrom(execute("GET", "/v1.0/devices"))

    /** Devices owned by one app user. */
    suspend fun getUserDevices(userId: String): List<TuyaCloudDevice> =
        devicesFrom(execute("GET", "/v1.0/users/$userId/devices"))

    /** One device, including its local key - useful to bootstrap local control. */
    suspend fun getDevice(deviceId: String): TuyaCloudDevice? {
        val root = execute("GET", "/v1.0/devices/$deviceId")
        return TuyaCloudDevice.fromJson(root.optJSONObject("result"))
    }

    /** Current data points of a device. */
    suspend fun getStatus(deviceId: String): List<TuyaCloudStatusItem> {
        val root = execute("GET", "/v1.0/devices/$deviceId/status")
        val array = root.optJSONArray("result") ?: return emptyList()
        return (0 until array.length()).mapNotNull { TuyaCloudStatusItem.fromJson(array.optJSONObject(it)) }
    }

    /** Sends one or more instructions to a device. */
    suspend fun sendCommands(deviceId: String, commands: List<TuyaCloudCommand>): Boolean {
        val body = JSONObject().put("commands", JSONArray().apply { commands.forEach { put(it.toJson()) } })
        val root = execute("POST", "/v1.0/devices/$deviceId/commands", null, body)
        return root.optBoolean("result", false)
    }

    /** The functions and status set defined by the device's product. */
    suspend fun getSpecification(deviceId: String): JSONObject? =
        execute("GET", "/v1.0/iot-03/devices/$deviceId/specification").optJSONObject("result")

    /**
     * Escape hatch for endpoints this client does not model. Returns the raw
     * response object; the `result` member holds the payload.
     */
    @JvmOverloads
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String?>? = null,
        body: JSONObject? = null,
    ): JSONObject = execute(method, path, query, body)

    // ------------------------------------------------------------ transport

    private fun devicesFrom(root: JSONObject): List<TuyaCloudDevice> {
        val array = root.optJSONArray("result") ?: return emptyList()
        return (0 until array.length()).mapNotNull { TuyaCloudDevice.fromJson(array.optJSONObject(it)) }
    }

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String?>? = null,
        body: JSONObject? = null,
        useToken: Boolean = true,
        retryOnTokenError: Boolean = true,
    ): JSONObject {
        val url = TuyaCloudSigner.buildUrl(path, query)
        val bodyBytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val accessToken = if (useToken) getToken().accessToken else null

        val timestamp = TuyaCloudSigner.currentTimestamp()
        val nonce = TuyaCloudSigner.createNonce()
        val stringToSign = TuyaCloudSigner.buildStringToSign(method, bodyBytes, null, url)
        val sign = if (accessToken == null) {
            TuyaCloudSigner.signToken(options.clientId, options.secret, timestamp, nonce, stringToSign)
        } else {
            TuyaCloudSigner.signBusiness(options.clientId, accessToken, options.secret, timestamp, nonce, stringToSign)
        }

        val builder = Request.Builder()
            .url(options.baseUrl + url)
            .header("client_id", options.clientId)
            .header("sign", sign)
            .header("sign_method", "HMAC-SHA256")
            .header("t", timestamp.toString())
            .header("nonce", nonce)
            .header("lang", options.language)

        if (accessToken != null) builder.header("access_token", accessToken)

        when (val verb = method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(verb, (bodyBytes ?: ByteArray(0)).toRequestBody(JSON_MEDIA_TYPE))
        }

        val response = withContext(Dispatchers.IO) {
            try {
                http.newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw TuyaCloudException("could not reach ${options.baseUrl}: ${e.message}")
            }
        }

        val statusCode = response.code
        val text = response.body?.string() ?: ""
        response.close()

        if (statusCode !in 200..299) {
            throw TuyaCloudException("HTTP $statusCode from $method $path", null, statusCode, text)
        }

        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw TuyaCloudException("the cloud API returned a non-JSON body", null, statusCode, text)
        }

        val success = !root.has("success") || root.optBoolean("success", true)
        if (success) return root

        val code = root.opt("code")?.toString()
        val message = root.optString("msg", "").takeIf { it.isNotEmpty() }

        if (retryOnTokenError && isTokenError(code, message)) {
            getToken(forceRefresh = true)
            return execute(method, path, query, body, useToken, retryOnTokenError = false)
        }

        throw TuyaCloudException(
            message ?: "the cloud API returned success=false",
            code,
            statusCode,
            text,
        )
    }

    private fun isTokenError(code: String?, message: String?): Boolean {
        if (code != null && (code == "1010" || code == "1011" || code == "1012")) return true
        return (code?.contains("TOKEN", ignoreCase = true) ?: false) ||
            (message?.contains("token", ignoreCase = true) ?: false)
    }

    /** Drops idle connections. The [OkHttpClient] itself stays usable. */
    override fun close() {
        http.connectionPool.evictAll()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
