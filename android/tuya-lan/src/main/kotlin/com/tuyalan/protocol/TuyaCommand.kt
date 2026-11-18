package com.tuyalan.protocol

/**
 * Command identifiers carried in the frame header. Values match the Tuya
 * embedded SDK's `lan_protocol.h`.
 */
object TuyaCommand {
    const val AP_CONFIG = 0x01
    const val ACTIVE = 0x02

    /** Session key negotiation, step 1 (client nonce). 3.4 / 3.5 only. */
    const val SESSION_KEY_NEG_START = 0x03

    /** Session key negotiation, step 2 (device nonce + HMAC). */
    const val SESSION_KEY_NEG_RESP = 0x04

    /** Session key negotiation, step 3 (HMAC of the device nonce). */
    const val SESSION_KEY_NEG_FINISH = 0x05

    const val UNBIND = 0x06

    /** Set data points. The workhorse command for control. */
    const val CONTROL = 0x07

    /** Data point report, sent by the device unsolicited. */
    const val STATUS = 0x08

    const val HEART_BEAT = 0x09

    /** Query current data points. */
    const val DP_QUERY = 0x0A

    const val QUERY_WIFI = 0x0B
    const val TOKEN_BIND = 0x0C

    /** Control variant used by older 22-character gwId devices ("device22"). */
    const val CONTROL_NEW = 0x0D

    const val ENABLE_WIFI = 0x0E
    const val WIFI_INFO = 0x0F

    /** DP query variant used by "device22" devices; also a common status reply command. */
    const val DP_QUERY_NEW = 0x10

    const val SCENE_EXECUTE = 0x11

    /** Ask the device to refresh and push its data points. */
    const val UPDATEDPS = 0x12

    /** Encrypted discovery broadcast (UDP 6667). */
    const val UDP_NEW = 0x13

    const val AP_CONFIG_NEW = 0x14

    /** Discovery broadcast used by 3.4 firmware. */
    const val BOARDCAST_LPV34 = 0x23

    /** Discovery solicitation request (UDP 7000). */
    const val REQ_DEVINFO = 0x25

    /** Extended stream, used for gateway / sub-device operations. */
    const val LAN_EXT_STREAM = 0x40

    private val noVersionHeader = setOf(
        DP_QUERY,
        DP_QUERY_NEW,
        UPDATEDPS,
        HEART_BEAT,
        SESSION_KEY_NEG_START,
        SESSION_KEY_NEG_RESP,
        SESSION_KEY_NEG_FINISH,
        LAN_EXT_STREAM,
    )

    /**
     * True when the payload for this command carries the 15-byte "3.x" version
     * header. Query, heartbeat and handshake commands do not.
     */
    @JvmStatic
    fun carriesVersionHeader(command: Int): Boolean = command !in noVersionHeader

    /** True when the command is one of the session-key negotiation steps. */
    @JvmStatic
    fun isHandshake(command: Int): Boolean =
        command == SESSION_KEY_NEG_START || command == SESSION_KEY_NEG_RESP || command == SESSION_KEY_NEG_FINISH

    /** True when the command carries data points (for correlating replies). */
    @JvmStatic
    fun carriesDataPoints(command: Int): Boolean =
        command == CONTROL || command == STATUS || command == DP_QUERY ||
            command == DP_QUERY_NEW || command == CONTROL_NEW || command == UPDATEDPS
}
