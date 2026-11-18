package com.tuyalan.protocol

/**
 * LAN protocol revision a device speaks. Reported in the discovery broadcast as
 * the `version` field ("3.3", "3.4", "3.5").
 */
enum class TuyaProtocolVersion(val label: String) {
    /** Legacy: 0x000055AA frame, AES-128-ECB with the static local key, CRC-32. */
    V33("3.3"),

    /** 0x000055AA frame, per-connection session key, AES-128-ECB + HMAC-SHA256. */
    V34("3.4"),

    /** 0x00006699 frame, per-connection session key, AES-128-GCM. Default for current firmware. */
    V35("3.5");

    /** True for 3.5, which uses AES-GCM and the 0x00006699 frame. */
    val usesGcm: Boolean get() = this == V35

    /** True for 3.4 and 3.5, which negotiate a per-connection session key. */
    val usesSessionKey: Boolean get() = this >= V34

    /** True for 3.4 and 3.5, which authenticate the frame with HMAC-SHA256 or a GCM tag. */
    val usesHmac: Boolean get() = this >= V34

    /**
     * The 15-byte version header that precedes most payloads: 3 ASCII bytes
     * ("3.5") followed by 12 zero bytes.
     */
    val versionHeader: ByteArray
        get() {
            val header = ByteArray(15)
            val label = label.toByteArray(Charsets.US_ASCII)
            label.copyInto(header, 0, 0, label.size)
            return header
        }

    companion object {
        /** Parses a discovery `version` string. Unknown values fall back to 3.3. */
        @JvmStatic
        fun parseLabel(label: String?): TuyaProtocolVersion = when (label?.trim()) {
            "3.5" -> V35
            "3.4" -> V34
            "3.3" -> V33
            else -> V33
        }
    }
}
