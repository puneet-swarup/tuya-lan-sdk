package com.tuyalan.cloud

import org.json.JSONObject

/** An access token for the cloud API, plus the expiry the client tracks. */
data class TuyaCloudToken(
    val accessToken: String,
    val refreshToken: String = "",
    /** Token lifetime in seconds, as reported by the API. */
    val expireTime: Long = 0,
    val uid: String? = null,
    /** Local clock time (epoch millis) at which this token stops being usable. */
    val expiresAtMillis: Long = 0,
) {
    /** True when the token is still valid for at least another 30 seconds. */
    val isFresh: Boolean get() = expiresAtMillis > System.currentTimeMillis() + 30_000

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): TuyaCloudToken {
            val expireTime = json.optLong("expire_time", 0)
            val lifetimeSeconds = if (expireTime > 60) expireTime - 60 else expireTime
            return TuyaCloudToken(
                accessToken = json.optString("access_token", ""),
                refreshToken = json.optString("refresh_token", ""),
                expireTime = expireTime,
                uid = json.optString("uid", "").takeIf { it.isNotEmpty() },
                expiresAtMillis = System.currentTimeMillis() + lifetimeSeconds * 1000,
            )
        }
    }
}

/** A device as returned by the cloud device endpoints. */
data class TuyaCloudDevice(
    val id: String,
    val name: String? = null,
    val category: String? = null,
    val productId: String? = null,
    val productName: String? = null,
    val online: Boolean = false,
    /** The local key, available when the project has device-key permission. */
    val localKey: String? = null,
    val ip: String? = null,
    val timeZone: String? = null,
    val uid: String? = null,
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject?): TuyaCloudDevice? {
            if (json == null) return null
            val id = json.optString("id", "")
            if (id.isEmpty()) return null
            return TuyaCloudDevice(
                id = id,
                name = json.optString("name", "").takeIf { it.isNotEmpty() },
                category = json.optString("category", "").takeIf { it.isNotEmpty() },
                productId = json.optString("product_id", "").takeIf { it.isNotEmpty() },
                productName = json.optString("product_name", "").takeIf { it.isNotEmpty() },
                online = json.optBoolean("online", false),
                localKey = json.optString("local_key", "").takeIf { it.isNotEmpty() },
                ip = json.optString("ip", "").takeIf { it.isNotEmpty() },
                timeZone = json.optString("time_zone", "").takeIf { it.isNotEmpty() },
                uid = json.optString("uid", "").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** One data point in a cloud status response. */
data class TuyaCloudStatusItem(val code: String, val value: Any?) {
    override fun toString(): String = "$code=$value"

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject?): TuyaCloudStatusItem? {
            if (json == null) return null
            val code = json.optString("code", "")
            if (code.isEmpty()) return null
            return TuyaCloudStatusItem(code, json.opt("value"))
        }
    }
}

/** One instruction sent to a device through the cloud. */
data class TuyaCloudCommand(val code: String, val value: Any?) {
    fun toJson(): JSONObject = JSONObject().put("code", code).put("value", value ?: JSONObject.NULL)

    companion object {
        @JvmStatic
        fun of(code: String, value: Boolean) = TuyaCloudCommand(code, value)

        @JvmStatic
        fun of(code: String, value: Int) = TuyaCloudCommand(code, value)

        @JvmStatic
        fun of(code: String, value: String) = TuyaCloudCommand(code, value)
    }
}
