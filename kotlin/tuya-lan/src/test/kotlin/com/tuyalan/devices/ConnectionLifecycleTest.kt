package com.tuyalan.devices

import com.tuyalan.protocol.TuyaProtocolVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the connection lifecycle, where the worst bug in this library lived:
 * isConnected reported "the socket object exists", stayed true after the peer
 * closed, and every later command timed out against a socket nobody read.
 */
class ConnectionLifecycleTest {

    private fun options(port: Int) = TuyaDeviceOptions(
        host = "127.0.0.1",
        deviceId = "bf0123456789abcdefghij",
        localKey = "0123456789abcdef",
        // 3.3 negotiates nothing, so the fake peer only has to accept.
        version = TuyaProtocolVersion.V33,
        port = port,
        connectTimeoutMillis = 2000,
        commandTimeoutMillis = 2000,
        enableHeartbeat = false,
    )

    @Test
    fun connectingToAListeningPeerSucceeds() = runBlocking {
        FakeDevice().use { peer ->
            TuyaLocalDevice(options(peer.port)).use { device ->
                device.connect()
                assertTrue(device.isConnected)
                assertEquals(1, peer.acceptedCount)
            }
        }
    }

    @Test
    fun isConnectedTurnsFalseWhenThePeerGoesAway() = runBlocking {
        FakeDevice().use { peer ->
            TuyaLocalDevice(options(peer.port)).use { device ->
                device.connect()
                assertTrue(device.isConnected)

                peer.dropConnection()

                assertTrue(
                    "the device never noticed that its peer had gone",
                    waitUntil(5000) { !device.isConnected }
                )
            }
        }
    }

    @Test
    fun ensureConnectedReconnectsAfterThePeerGoesAway() = runBlocking {
        FakeDevice().use { peer ->
            TuyaLocalDevice(options(peer.port)).use { device ->
                device.connect()
                peer.dropConnection()
                waitUntil(5000) { !device.isConnected }

                device.ensureConnected()

                assertTrue(device.isConnected)
                assertEquals(2, peer.acceptedCount)
            }
        }
    }

    @Test
    fun ensureConnectedDoesNotReconnectWhenTheSocketIsFine() = runBlocking {
        FakeDevice().use { peer ->
            TuyaLocalDevice(options(peer.port)).use { device ->
                device.connect()
                device.ensureConnected()
                device.ensureConnected()
                assertEquals(1, peer.acceptedCount)
            }
        }
    }

    private suspend fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25)
        }
        return condition()
    }
}
