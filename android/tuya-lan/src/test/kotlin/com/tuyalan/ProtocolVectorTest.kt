package com.tuyalan

import com.tuyalan.cloud.TuyaCloudSigner
import com.tuyalan.devices.TuyaDiscovery
import com.tuyalan.devices.TuyaStatus
import com.tuyalan.protocol.RetcodePolicy
import com.tuyalan.protocol.TuyaCommand
import com.tuyalan.protocol.TuyaCrypto
import com.tuyalan.protocol.TuyaFrameCodec
import com.tuyalan.protocol.TuyaProtocolVersion
import com.tuyalan.protocol.TuyaSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Known-answer tests. Every expected value below is produced by
 * `tools/reference_impl.py --emit-vectors`, an independent implementation of the
 * same wire format, so a mistake in this library cannot silently define its own
 * expectations.
 */
class ProtocolVectorTest {

    // "0123456789abcdef" - a stand-in local key.
    private val key = TuyaCrypto.fromHex("30313233343536373839616263646566")

    // "0123456789ab" - a fixed GCM nonce so frames are reproducible.
    private val iv = TuyaCrypto.fromHex("303132333435363738396162")

    private val plaintext = """{"devId":"eb0123456789abcdefghij","t":1700000000,"dps":{"1":true}}"""

    @Test
    fun crc32MatchesReference() {
        assertEquals(2524178384L, TuyaCrypto.crc32(plaintext.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun pack6699MatchesReference() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 7, TuyaCommand.CONTROL,
            plaintext.toByteArray(Charsets.UTF_8), iv = iv,
        )
        assertEquals(
            "00006699000000000007000000070000005e3031323334353637383961621960699e582949ab39211286b4eb91c0a2bd405f5282246107fb141610fab2e4d9d58c73a1b6b8ef301af890e141a4b900938a107efb4eb841d994321b66c2f0478cd3b3916df1ada9d7c36cc7aeb1e64c2100009966",
            TuyaCrypto.toHex(frame),
        )
    }

    @Test
    fun pack6699WithReturnCodeMatchesReference() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 8, TuyaCommand.STATUS,
            plaintext.toByteArray(Charsets.UTF_8), returnCode = 0, iv = iv,
        )
        assertEquals(
            "00006699000000000008000000080000006230313233343536373839616262420dfb554249ec754a13c6bef8c691a6b9445b5e8e73345ca6101214f6bee89c91c76da1a0abac2210f997e141a4b91c81de5021fb10b310ca8c734d2595af4e8394a77454c43d5b3fbe57e279a439fcf618a54ebc00009966",
            TuyaCrypto.toHex(frame),
        )
    }

    @Test
    fun unpack6699RoundTrips() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 42, TuyaCommand.CONTROL,
            plaintext.toByteArray(Charsets.UTF_8), iv = iv,
        )
        val message = TuyaFrameCodec.unpack(TuyaProtocolVersion.V35, key, frame)

        assertEquals(TuyaCommand.CONTROL, message.command)
        assertEquals(42L, message.sequenceNumber)
        assertEquals(plaintext, message.payloadText)
        assertNull(message.returnCode)
    }

    @Test
    fun unpack6699StripsReturnCode() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 8, TuyaCommand.STATUS,
            plaintext.toByteArray(Charsets.UTF_8), returnCode = 0, iv = iv,
        )
        val message = TuyaFrameCodec.unpack(TuyaProtocolVersion.V35, key, frame)

        assertEquals(0L, message.returnCode)
        assertEquals(plaintext, message.payloadText)
    }

    @Test
    fun unpack6699RejectsTampering() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 7, TuyaCommand.CONTROL,
            plaintext.toByteArray(Charsets.UTF_8), iv = iv,
        )
        frame[30] = (frame[30].toInt() xor 0xFF).toByte()
        try {
            TuyaFrameCodec.unpack(TuyaProtocolVersion.V35, key, frame)
            fail("expected a crypto failure")
        } catch (expected: TuyaCryptoException) {
            // expected
        }
    }

    @Test
    fun unpack6699RejectsWrongKey() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 7, TuyaCommand.CONTROL,
            plaintext.toByteArray(Charsets.UTF_8), iv = iv,
        )
        val wrongKey = TuyaCrypto.fromHex("66656463626139383736353433323130")
        try {
            TuyaFrameCodec.unpack(TuyaProtocolVersion.V35, wrongKey, frame)
            fail("expected a crypto failure")
        } catch (expected: TuyaCryptoException) {
            // expected
        }
    }

    @Test
    fun pack55aaV34MatchesReference() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V34, key, 3, TuyaCommand.CONTROL, plaintext.toByteArray(Charsets.UTF_8),
        )
        assertEquals(
            "000055aa0000000300000007000000844490b05d74be9368c24a038cbaeded8e93cdaa9ddbe759a2de1dd3364730e6e7b02174601734b32067a7df69e7fdae03b2068860fe6deff0bde9fdfc2e98273941fb8ef70a70a27b93a7e74ffe75426303000aeb4ff51d29188f82e4a162a1f627cdbca6f71f92a1bf0a6b965b8f203913db4fc95b759309b969660796980fd20000aa55",
            TuyaCrypto.toHex(frame),
        )
    }

    @Test
    fun unpack55aaV34RoundTrips() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V34, key, 3, TuyaCommand.CONTROL, plaintext.toByteArray(Charsets.UTF_8),
        )
        val message = TuyaFrameCodec.unpack(TuyaProtocolVersion.V34, key, frame, RetcodePolicy.NEVER)

        assertEquals(TuyaCommand.CONTROL, message.command)
        assertEquals(plaintext, message.payloadText)
    }

    @Test
    fun pack55aaV33MatchesReference() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V33, key, 4, TuyaCommand.DP_QUERY, plaintext.toByteArray(Charsets.UTF_8),
        )
        assertEquals(
            "000055aa000000040000000a00000058f1a712fc1110fda4489c54a53046067e0a426a5d54a482ce0e3fbd3bdbec46141f2a18cc7295041d10eba653e1ff2e47f09a264f41ed2ed2962f8ff11444d4dd64215f365b0dad6b2ac8fab15b1d40bdeb39c6c20000aa55",
            TuyaCrypto.toHex(frame),
        )
    }

    @Test
    fun deriveSessionKey35MatchesReference() {
        val session = TuyaSession(TuyaProtocolVersion.V35, key)
        val derived = session.deriveSessionKey(
            TuyaCrypto.fromHex("30313233343536373839616263646566"),
            TuyaCrypto.fromHex("66656463626139383736353433323130"),
        )
        assertEquals("34165bab783422860c0c23b2d48cf7a5", TuyaCrypto.toHex(derived))
    }

    @Test
    fun deriveSessionKey34MatchesReference() {
        val session = TuyaSession(TuyaProtocolVersion.V34, key)
        val derived = session.deriveSessionKey(
            TuyaCrypto.fromHex("30313233343536373839616263646566"),
            TuyaCrypto.fromHex("66656463626139383736353433323130"),
        )
        assertEquals("6575cf6b37479d9215337ff9767fe786", TuyaCrypto.toHex(derived))
    }

    @Test
    fun discoveryKeyMatchesReference() {
        assertEquals("6c1ec8e2bb9bb59ab50b0daf649b410a", TuyaCrypto.toHex(TuyaDiscovery.discoveryKey))
    }

    @Test
    fun tryReadFrameHandlesPartialInput() {
        val frame = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 1, TuyaCommand.CONTROL, plaintext.toByteArray(Charsets.UTF_8), iv = iv,
        )

        assertNull(TuyaFrameCodec.tryReadFrame(frame, 0, 10))
        val parsed = TuyaFrameCodec.tryReadFrame(frame)
        assertNotNull(parsed)
        assertEquals(frame.size, parsed!!.bytes.size)
        assertEquals(TuyaCommand.CONTROL, parsed.command)
    }

    @Test
    fun tryReadFrameHandlesTwoFramesInOneBuffer() {
        val first = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 1, TuyaCommand.CONTROL, plaintext.toByteArray(Charsets.UTF_8), iv = iv,
        )
        val second = TuyaFrameCodec.pack(
            TuyaProtocolVersion.V35, key, 2, TuyaCommand.STATUS, plaintext.toByteArray(Charsets.UTF_8), iv = iv,
        )
        val buffer = first + second

        val parsed = TuyaFrameCodec.tryReadFrame(buffer)
        assertNotNull(parsed)
        assertEquals(first.size, parsed!!.bytes.size)

        val next = TuyaFrameCodec.tryReadFrame(buffer, first.size, buffer.size - first.size)
        assertNotNull(next)
        assertEquals(2L, next!!.sequenceNumber)
    }

    @Test
    fun statusParsesBareAndWrappedPayloads() {
        val bare = TuyaStatus.parse("""{"dps":{"1":true,"2":500,"3":"white"}}""")
        assertEquals(3, bare.dps.size)
        assertEquals(true, bare.getBool("1"))
        assertEquals(500, bare.getInt("2"))
        assertEquals("white", bare.getString("3"))

        val wrapped = TuyaStatus.parse("""{"devId":"abc","t":1700000000,"data":{"dps":{"20":false}}}""")
        assertEquals("abc", wrapped.deviceId)
        assertEquals(1700000000L, wrapped.timestamp)
        assertEquals(false, wrapped.getBool("20"))

        assertFalse(TuyaStatus.parse("json obj data unvalid").hasData)
    }

    @Test
    fun cloudTokenSignMatchesPublishedVector() {
        val stringToSign = TuyaCloudSigner.buildStringToSign(
            "GET",
            ByteArray(0),
            listOf(
                "area_id" to "29a33e8796834b1efa6",
                "call_id" to "8afdb70ab2ed11eb85290242ac130003",
            ),
            "/v1.0/token?grant_type=1",
        )

        assertEquals(
            "GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n" +
                "area_id:29a33e8796834b1efa6\ncall_id:8afdb70ab2ed11eb85290242ac130003\n\n/v1.0/token?grant_type=1",
            stringToSign,
        )

        assertEquals(
            "9E48A3E93B302EEECC803C7241985D0A34EB944F40FB573C7B5C2A82158AF13E",
            TuyaCloudSigner.signToken(
                "1KAD46OrT9HafiKdsXeg",
                "4OHBOnWOqaEC1mWXOpVL3yV50s0qGSRC",
                1588925778000L,
                "5138cc3a9033d69856923fd07b491173",
                stringToSign,
            ),
        )
    }

    @Test
    fun cloudQueryParametersAreSorted() {
        assertEquals(
            "/v1.0/iot-03/devices/x/logs?end_time=1657263936000&event_types=1&start_time=1657160836000",
            TuyaCloudSigner.buildUrl(
                "/v1.0/iot-03/devices/x/logs",
                mapOf(
                    "start_time" to "1657160836000",
                    "event_types" to "1",
                    "end_time" to "1657263936000",
                    "ignored" to null,
                ),
            ),
        )
    }

    @Test
    fun pkcs7RoundTrips() {
        val data = "hello tuya".toByteArray(Charsets.UTF_8)
        assertArrayEquals(data, TuyaCrypto.pkcs7Unpad(TuyaCrypto.pkcs7Pad(data)))
    }
}
