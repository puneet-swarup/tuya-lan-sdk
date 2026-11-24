package com.tuyalan.devices

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A socket that accepts connections and nothing else.
 *
 * Enough to test the connection lifecycle, which is where the Kotlin library had
 * the same bug as the .NET one: isConnected stayed true after the peer closed,
 * so a cached device was reused for ever.
 *
 * It speaks protocol 3.3 on purpose - 3.4 and 3.5 negotiate a session key, which
 * would mean faking the handshake, while 3.3 uses the static key and connects
 * without negotiating.
 */
class FakeDevice : AutoCloseable {

    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val accepted = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var current: Socket? = null

    @Volatile
    private var running = true

    val port: Int get() = server.localPort
    val acceptedCount: Int get() = accepted.get()

    init {
        thread(isDaemon = true, name = "fake-device") {
            while (running) {
                try {
                    val socket = server.accept()
                    accepted.incrementAndGet()
                    current = socket
                } catch (closed: Exception) {
                    return@thread
                }
            }
        }
    }

    /**
     * Closes the peer's side, the way a router timeout or a reboot would.
     *
     * Waits for the accept thread to have stored the socket first. The kernel
     * completes the TCP handshake before `accept()` returns, so a caller that
     * drops immediately after connecting can otherwise close nothing and leave
     * the device happily connected - which is what made
     * isConnectedTurnsFalseWhenThePeerGoesAway fail intermittently.
     */
    fun dropConnection() {
        val deadline = System.currentTimeMillis() + 2000
        while (current == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        runCatching { current?.close() }
        current = null
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        dropConnection()
    }
}
