package com.tuyalan.devices

import org.json.JSONException
import org.json.JSONObject

/**
 * A data-point snapshot reported by a device.
 *
 * A device sends either a bare `{"dps":{...}}` object or a wrapper such as
 * `{"devId":"...","t":1700000000,"data":{"dps":{...}}}`. Some gateways
 * double-encode the wrapper as a JSON string. All three shapes are handled.
 *
 * Values are kept as the types `org.json` produces: [Boolean], [Int], [Long],
 * [Double], [String] or a nested [JSONObject].
 */
class TuyaStatus(
    /** Device id, when the payload carried one. */
    val deviceId: String? = null,
    /** Device-side Unix timestamp, or 0 when absent. */
    val timestamp: Long = 0,
    /** Data points keyed by DP id ("1", "20", ...). */
    val dps: Map<String, Any?> = emptyMap(),
    /** The decoded payload, kept for debugging. */
    val rawJson: String = "",
) {
    /** True when at least one data point was present. */
    val hasData: Boolean get() = dps.isNotEmpty()

    /** Looks up a data point. */
    operator fun get(dpId: String): Any? = dps[dpId]

    /** The data point as a boolean, or null when absent or of another type. */
    fun getBool(dpId: String): Boolean? = dps[dpId] as? Boolean

    /** The data point as a number, or null when absent. */
    fun getDouble(dpId: String): Double? = (dps[dpId] as? Number)?.toDouble()

    /** The data point as a 32-bit integer, or null when absent. */
    fun getInt(dpId: String): Int? = (dps[dpId] as? Number)?.toInt()

    /** The data point as a string, or null when absent. */
    fun getString(dpId: String): String? = dps[dpId] as? String

    override fun toString(): String =
        "TuyaStatus(${dps.size} dps: ${dps.entries.joinToString(", ") { "${it.key}=${it.value}" }})"

    companion object {
        /** Parses a payload. Never throws: an unparseable payload yields an empty status. */
        @JvmStatic
        fun parse(payload: ByteArray): TuyaStatus = parse(String(payload, Charsets.UTF_8))

        /** Parses a payload. Never throws: an unparseable payload yields an empty status. */
        @JvmStatic
        fun parse(json: String?): TuyaStatus {
            if (json.isNullOrBlank()) return TuyaStatus(rawJson = json ?: "")

            try {
                val root = JSONObject(json)

                val deviceId = root.optString("devId", "").takeIf { it.isNotEmpty() }
                val timestamp = root.optLong("t", 0)

                var dpsObject: JSONObject? = root.optJSONObject("dps")
                if (dpsObject == null) {
                    when (val data = root.opt("data")) {
                        is JSONObject -> dpsObject = data.optJSONObject("dps")
                        is String -> {
                            val nested = parse(data)
                            if (nested.hasData) return nested
                        }
                    }
                }

                val dps = LinkedHashMap<String, Any?>()
                if (dpsObject != null) {
                    val keys = dpsObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        dps[key] = dpsObject.opt(key)
                    }
                }

                return TuyaStatus(deviceId, timestamp, dps, json)
            } catch (e: JSONException) {
                return TuyaStatus(rawJson = json)
            }
        }
    }
}

/**
 * Fluent builder for a control payload's `dps` object.
 *
 * ```kotlin
 * val dps = TuyaDps()
 *     .set("1", true)
 *     .set("2", 500)
 *     .setEnum("3", "white")
 * device.setDps(dps)
 * ```
 */
class TuyaDps {
    private val values = LinkedHashMap<String, Any?>()

    /** The data points as a plain map, ready for the payload builder. */
    val asMap: Map<String, Any?> get() = values

    /** Sets a boolean data point (switches, toggles). */
    fun set(dpId: String, value: Boolean): TuyaDps = apply { values[dpId] = value }

    /** Sets a numeric data point (brightness, temperature, colour data). */
    fun set(dpId: String, value: Int): TuyaDps = apply { values[dpId] = value }

    /** Sets a numeric data point with a fractional value. */
    fun set(dpId: String, value: Double): TuyaDps = apply { values[dpId] = value }

    /** Sets a string data point. */
    fun set(dpId: String, value: String): TuyaDps = apply { values[dpId] = value }

    /** Sets an enum data point, which is a JSON string on the wire. */
    fun setEnum(dpId: String, value: String): TuyaDps = set(dpId, value)

    /** Sets a raw data point from a hex string (used by some DP types). */
    fun setRaw(dpId: String, hexValue: String): TuyaDps = set(dpId, hexValue)
}
