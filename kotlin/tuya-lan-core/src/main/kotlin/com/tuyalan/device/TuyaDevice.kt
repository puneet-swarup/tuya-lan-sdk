package com.tuyalan.device

import com.tuyalan.devices.TuyaStatus
import com.tuyalan.TuyaException

/**
 * A Tuya device on the local network, with a blocking API.
 *
 * This is the interface the library is built around, and it is written for Java
 * first: every method returns a value, nothing is `suspend`, and events are
 * listeners rather than flows. Kotlin callers who want coroutines should use
 * [TuyaDeviceAsync], which the same object implements.
 *
 * ```java
 * try (TuyaDevice device = new TuyaLocalDevice(options)) {
 *     device.connect();
 *     Boolean on = device.getStatus().getBool("1");
 *     device.setDp("1", !Boolean.TRUE.equals(on));
 * }
 * ```
 *
 * Every method throws [TuyaException] - or a subclass - when the device cannot
 * be reached. `TuyaException` is a checked exception, so Java must handle it.
 */
interface TuyaDevice : AutoCloseable {

    /** True while the socket is usable. Turns false as soon as the connection dies. */
    val isConnected: Boolean

    /** Protocol revision in use, e.g. `"3.5"`. */
    val protocolVersion: String

    /** Connects, or reconnects over a socket that died. */
    @Throws(TuyaException::class)
    fun connect()

    /** Closes the connection. Safe to call when already closed. */
    @Throws(TuyaException::class)
    override fun close()

    /** Reads every data point the device reports. */
    @Throws(TuyaException::class)
    fun getStatus(): TuyaStatus

    /** Writes several data points in one command. */
    @Throws(TuyaException::class)
    fun setDps(dps: Map<String, Any?>)

    /** Writes one boolean data point, e.g. a relay. */
    @Throws(TuyaException::class)
    fun setDp(dpId: String, value: Boolean)

    /** Writes one integer data point, e.g. a countdown. */
    @Throws(TuyaException::class)
    fun setDp(dpId: String, value: Int)

    /** Writes one string data point. */
    @Throws(TuyaException::class)
    fun setDp(dpId: String, value: String)

    /** Sends a heartbeat and waits for the reply. */
    @Throws(TuyaException::class)
    fun ping(): Boolean

    /** Called with every status the device pushes. */
    fun addStatusListener(listener: TuyaStatusListener)

    /** Called when the connection drops, with the cause. */
    fun addDisconnectListener(listener: TuyaDisconnectListener)

    /** Called for protocol and crypto failures. */
    fun addErrorListener(listener: TuyaErrorListener)

    /** Removes a listener previously added. */
    fun removeStatusListener(listener: TuyaStatusListener)

    /** Removes a listener previously added. */
    fun removeDisconnectListener(listener: TuyaDisconnectListener)

    /** Removes a listener previously added. */
    fun removeErrorListener(listener: TuyaErrorListener)
}

/** Receives a status the device pushed. */
fun interface TuyaStatusListener {
    fun onStatus(status: TuyaStatus)
}

/** Receives the cause of a dropped connection. */
fun interface TuyaDisconnectListener {
    fun onDisconnected(cause: Exception)
}

/** Receives a protocol or crypto failure. */
fun interface TuyaErrorListener {
    fun onError(error: TuyaException)
}

/**
 * The same device, with a coroutine API.
 *
 * Kept separate from [TuyaDevice] so neither audience pays for the other: Java
 * never sees a `suspend` function, and Kotlin never has to wrap a blocking call
 * in `runBlocking` to avoid pinning a thread.
 */
interface TuyaDeviceAsync {

    /** True while the socket is usable. */
    val isConnected: Boolean

    /** Connects, or reconnects over a socket that died. */
    suspend fun connectAsync()

    /** Closes the connection. */
    suspend fun disconnectAsync()

    /** Reads every data point the device reports. */
    suspend fun getStatusAsync(): TuyaStatus

    /** Writes several data points in one command. */
    suspend fun setDpsAsync(dps: Map<String, Any?>)

    /** Writes one boolean data point. */
    suspend fun setDpAsync(dpId: String, value: Boolean)

    /** Writes one integer data point. */
    suspend fun setDpAsync(dpId: String, value: Int)

    /** Writes one string data point. */
    suspend fun setDpAsync(dpId: String, value: String)

    /** Sends a heartbeat and waits for the reply. */
    suspend fun pingAsync(): Boolean
}
