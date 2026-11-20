package com.tuyalan.devices

import com.tuyalan.TuyaException
import com.tuyalan.TuyaHandshakeException
import com.tuyalan.TuyaProtocolException
import com.tuyalan.TuyaTimeoutException
import com.tuyalan.protocol.TuyaCommand
import com.tuyalan.protocol.TuyaDeviceType
import com.tuyalan.protocol.TuyaFrameCodec
import com.tuyalan.protocol.TuyaMessage
import com.tuyalan.protocol.TuyaProtocolVersion
import com.tuyalan.protocol.TuyaRawFrame
import com.tuyalan.protocol.TuyaSession
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * A client for one Tuya device over the local network (TCP 6668).
 *
 * The lifecycle is: construct with [TuyaDeviceOptions], call [connect] (which
 * performs the 3.4/3.5 session-key handshake), then issue commands. A background
 * loop decodes frames and emits unsolicited status pushes on [statuses].
 *
 * Tuya devices accept one request at a time, so commands are serialised behind a
 * mutex. Control commands frequently get no explicit reply - the device simply
 * pushes a status frame - so a [TuyaStatus] push is also accepted as the
 * acknowledgement.
 *
 * ```kotlin
 * val device = TuyaLocalDevice(
 *     TuyaDeviceOptions(host = "192.168.1.42", deviceId = "eb0123...", localKey = "0123456789abcdef"),
 * )
 * device.connect()
 * val status = device.getStatus()
 * device.setDp("1", true)
 * device.close()
 * ```
 */
class TuyaLocalDevice(val options: TuyaDeviceOptions) : Closeable {

    private val session = TuyaSession(options.version, options.localKeyBytes?.copyOf() ?: options.localKey.toByteArray(Charsets.UTF_8)).apply {
        headerMode = options.headerMode
        retcodePolicy = options.retcodePolicy
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null

    private val requestMutex = Mutex()
    private val writeMutex = Mutex()
    @Volatile private var pending: PendingRequest? = null

    private val readBuffer = ByteArray(16 * 1024)
    private var readLength = 0

    private val closed = AtomicBoolean(false)

    private val _statuses = MutableSharedFlow<TuyaStatus>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every status frame, solicited or not. */
    val statuses: SharedFlow<TuyaStatus> = _statuses.asSharedFlow()

    private val _protocolErrors = MutableSharedFlow<TuyaException>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Frames that could not be decoded. The connection stays open. */
    val protocolErrors: SharedFlow<TuyaException> = _protocolErrors.asSharedFlow()

    /** Protocol revision in use. */
    val version: TuyaProtocolVersion get() = session.version

    /** True once the 3.4/3.5 handshake has completed. */
    val hasSessionKey: Boolean get() = session.hasSessionKey

    /** True while the socket is open. */
    val isConnected: Boolean
        get() {
            val current = socket ?: return false
            return current.isConnected && !current.isClosed
        }

    /** Opens the TCP connection and negotiates a session key when the version needs one. */
    suspend fun connect() {
        if (socket != null) throw IllegalStateException("already connected")

        withContext(Dispatchers.IO) {
            val newSocket = Socket()
            newSocket.tcpNoDelay = true
            try {
                newSocket.connect(InetSocketAddress(options.host, options.port), options.connectTimeoutMillis)
            } catch (e: SocketTimeoutException) {
                newSocket.close()
                throw TuyaTimeoutException(
                    "could not connect to ${options.host}:${options.port} within ${options.connectTimeoutMillis}ms",
                )
            } catch (e: IOException) {
                newSocket.close()
                throw TuyaException("could not connect to ${options.host}:${options.port}: ${e.message}", e)
            }
            socket = newSocket
            input = newSocket.getInputStream()
            output = newSocket.getOutputStream()
        }

        readLength = 0

        if (options.version.usesSessionKey) {
            // A blocking InputStream.read() cannot be cancelled, so give the
            // handshake read a real socket timeout instead of relying on
            // coroutine cancellation. Reset to blocking once it is done.
            runCatching { socket?.soTimeout = options.commandTimeoutMillis.toInt() }
            try {
                negotiateSessionKey()
            } catch (failure: Throwable) {
                // A failed handshake (wrong local key, wrong protocol version, a silent
                // device) must not leave a half-open socket behind: isConnected would
                // still report true and the next connect() would throw "already connected".
                runCatching { socket?.close() }
                socket = null
                input = null
                output = null
                readLength = 0
                session.clearSessionKey()
                throw failure
            } finally {
                runCatching { socket?.soTimeout = 0 }
            }
        }

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        receiveJob = newScope.launch { receiveLoop() }
        if (options.enableHeartbeat) heartbeatJob = newScope.launch { heartbeatLoop() }
    }

    /** Closes the connection and stops the background loops. */
    suspend fun disconnect() {
        scope?.cancel()
        scope = null
        heartbeatJob = null
        receiveJob = null
        withContext(Dispatchers.IO) { runCatching { socket?.close() } }
        socket = null
        input = null
        output = null
        session.clearSessionKey()
        readLength = 0
    }

    /** Queries the current data points. */
    suspend fun getStatus(): TuyaStatus {
        val reply = sendRequest(
            queryCommand,
            buildQueryPayload(),
            setOf(TuyaCommand.STATUS, TuyaCommand.DP_QUERY_NEW, TuyaCommand.CONTROL, TuyaCommand.CONTROL_NEW),
        )

        val status = TuyaStatus.parse(reply.payload)
        if (status.hasData) return status

        // Some firmware answers the standard query with "json obj data unvalid".
        // Retrying with the other query command usually works.
        val alternate = if (queryCommand == TuyaCommand.DP_QUERY) TuyaCommand.DP_QUERY_NEW else TuyaCommand.DP_QUERY
        return try {
            val retry = sendRequest(
                alternate,
                buildQueryPayload(),
                setOf(TuyaCommand.STATUS, TuyaCommand.DP_QUERY_NEW, TuyaCommand.CONTROL),
            )
            TuyaStatus.parse(retry.payload).takeIf { it.hasData } ?: status
        } catch (e: TuyaTimeoutException) {
            status
        }
    }

    /** Sends one or more data points and waits for the device to acknowledge. */
    suspend fun setDps(dps: TuyaDps) = setDps(dps.asMap)

    /** Sends one or more data points and waits for the device to acknowledge. */
    suspend fun setDps(dps: Map<String, Any?>) {
        val payload = buildControlPayload(dps)
        val reply = sendRequest(
            controlCommand,
            payload,
            setOf(TuyaCommand.CONTROL, TuyaCommand.CONTROL_NEW, TuyaCommand.STATUS, TuyaCommand.DP_QUERY_NEW),
            tolerateTimeout = !options.waitForControlAck,
        )
        throwIfErrorReply(reply)
    }

    /**
     * Tuya firmware answers a frame it cannot use with a short plain-text complaint
     * inside an otherwise normal reply - "data format error", "json obj data unvalid".
     * Without this check a rejected control looks exactly like a successful one.
     */
    private fun throwIfErrorReply(message: TuyaMessage) {
        val payload = message.payload
        if (payload.isEmpty() || payload.size > 64) return

        val text = String(payload, Charsets.UTF_8)
        if (text.trimStart().startsWith("{")) return

        for (marker in listOf("error", "unvalid", "invalid", "fail", "not support")) {
            if (text.contains(marker, ignoreCase = true)) {
                throw TuyaProtocolException("the device rejected the control frame: $text")
            }
        }
    }

    /** Sets a single boolean data point, e.g. a switch. */
    suspend fun setDp(dpId: String, value: Boolean) = setDps(TuyaDps().set(dpId, value))

    /** Sets a single numeric data point, e.g. brightness. */
    suspend fun setDp(dpId: String, value: Int) = setDps(TuyaDps().set(dpId, value))

    /** Sets a single string data point, e.g. a work mode. */
    suspend fun setDp(dpId: String, value: String) = setDps(TuyaDps().set(dpId, value))

    /** Sends a heartbeat and returns true when the device answers. */
    suspend fun ping(): Boolean = try {
        sendRequest(
            TuyaCommand.HEART_BEAT,
            ByteArray(0),
            setOf(TuyaCommand.HEART_BEAT, TuyaCommand.STATUS),
        )
        true
    } catch (e: TuyaTimeoutException) {
        false
    }

    /**
     * Sends an arbitrary command with a raw payload and returns the reply. Use
     * this for commands this library does not model.
     */
    @JvmOverloads
    suspend fun sendRawCommand(
        command: Int,
        payload: ByteArray,
        acceptedReplies: Set<Int> = setOf(command, TuyaCommand.STATUS),
    ): TuyaMessage = sendRequest(command, payload, acceptedReplies)

    /** Non-suspending shutdown, for use from `onDestroy` and similar. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope?.cancel()
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        session.clearSessionKey()
    }

    // ------------------------------------------------------------ internals

    private val queryCommand: Int
        get() = if (options.deviceType == TuyaDeviceType.DEVICE22) TuyaCommand.DP_QUERY_NEW else TuyaCommand.DP_QUERY

    private val controlCommand: Int
        get() = if (options.deviceType == TuyaDeviceType.DEVICE22) TuyaCommand.CONTROL_NEW else TuyaCommand.CONTROL

    private suspend fun negotiateSessionKey() {
        val clientNonce = TuyaSession.createNonce()
        write(session.encode(TuyaCommand.SESSION_KEY_NEG_START, clientNonce))

        val raw = try {
            withTimeoutOrNull(options.commandTimeoutMillis) { readFrame() }
        } catch (e: SocketTimeoutException) {
            null // the socket read timeout fired first
        } ?: throw TuyaHandshakeException(
            "the device did not answer the session key request within ${options.commandTimeoutMillis}ms",
        )

        if (raw.command != TuyaCommand.SESSION_KEY_NEG_RESP) {
            throw TuyaHandshakeException(
                "expected a session key response (0x04), got command 0x%02X. ".format(raw.command) +
                    "The device probably speaks a different protocol version.",
            )
        }

        val response = session.decode(raw)

        // The body is device_nonce(16) || HMAC(32). Some firmware prefixes a
        // 4-byte return code even here, which makes it 52 bytes; the retcode
        // auto-detection cannot see that because the first byte is random, so
        // accept both shapes explicitly.
        val body = if (response.payload.size == 52) response.payload.copyOfRange(4, 52) else response.payload
        if (body.size != 48) {
            throw TuyaHandshakeException("session key response was ${response.payload.size} bytes, expected 48 or 52")
        }

        val deviceNonce = body.copyOfRange(0, 16)
        val expected = session.computeNonceHmac(clientNonce)
        val actual = body.copyOfRange(16, 48)

        if (!MessageDigest.isEqual(expected, actual)) {
            throw TuyaHandshakeException(
                "the device rejected the local key (nonce HMAC mismatch). Check the 16-character local key.",
            )
        }

        write(session.encode(TuyaCommand.SESSION_KEY_NEG_FINISH, session.computeNonceHmac(deviceNonce)))
        session.setSessionKey(session.deriveSessionKey(clientNonce, deviceNonce))
    }

    private suspend fun sendRequest(
        command: Int,
        payload: ByteArray,
        acceptedReplies: Set<Int>,
        tolerateTimeout: Boolean = false,
    ): TuyaMessage = requestMutex.withLock {
        val request = PendingRequest(acceptedReplies)
        pending = request
        try {
            write(session.encode(command, payload))
            val message = withTimeoutOrNull(options.commandTimeoutMillis) { request.deferred.await() }
            when {
                message != null -> message
                tolerateTimeout -> TuyaMessage(TuyaFrameCodec.PREFIX_55AA, 0, command, null, ByteArray(0))
                else -> throw TuyaTimeoutException(
                    "no reply to command 0x%02X within ${options.commandTimeoutMillis}ms".format(command),
                )
            }
        } finally {
            pending = null
        }
    }

    private suspend fun write(frame: ByteArray) = withContext(Dispatchers.IO) {
        // All writes are serialised: the heartbeat loop writes outside requestMutex,
        // and an OutputStream gives no atomicity guarantee for concurrent writes, so
        // two frames could otherwise interleave on the wire and corrupt both.
        writeMutex.withLock {
            val stream = output ?: throw IllegalStateException("the device is not connected; call connect() first")
            stream.write(frame)
            stream.flush()
        }
    }

    private suspend fun readFrame(): TuyaRawFrame = withContext(Dispatchers.IO) {
        val stream = input ?: throw IllegalStateException("the device is not connected; call connect() first")
        while (true) {
            val frame = TuyaFrameCodec.tryReadFrame(readBuffer, 0, readLength)
            if (frame != null) {
                val consumed = frame.bytes.size
                System.arraycopy(readBuffer, consumed, readBuffer, 0, readLength - consumed)
                readLength -= consumed
                return@withContext frame
            }

            if (readLength == readBuffer.size) {
                throw TuyaProtocolException("receive buffer overflow; the stream is not a Tuya frame")
            }

            val read = stream.read(readBuffer, readLength, readBuffer.size - readLength)
            if (read < 0) throw IOException("the device closed the connection")
            readLength += read
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    private suspend fun receiveLoop() {
        try {
            while (true) {
                val raw = readFrame()
                dispatch(raw)
            }
        } catch (e: IOException) {
            if (isConnected) _protocolErrors.tryEmit(TuyaProtocolException("connection lost: ${e.message}", e))
        } catch (e: TuyaException) {
            _protocolErrors.tryEmit(e)
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(options.heartbeatIntervalMillis)
            try {
                write(session.encode(TuyaCommand.HEART_BEAT, ByteArray(0)))
            } catch (e: Exception) {
                return
            }
        }
    }

    private fun dispatch(raw: TuyaRawFrame) {
        val message = try {
            session.decode(raw)
        } catch (e: TuyaException) {
            _protocolErrors.tryEmit(e)
            return
        }

        val request = pending
        if (request != null && request.accepts(message.command)) {
            request.deferred.complete(message)
            return
        }

        if (TuyaCommand.carriesDataPoints(message.command) && message.payload.isNotEmpty()) {
            val status = TuyaStatus.parse(message.payload)
            if (status.hasData) _statuses.tryEmit(status)
        }
    }

    private fun buildQueryPayload(): ByteArray {
        val payload = JSONObject()
            .put("gwId", options.deviceId)
            .put("devId", options.deviceId)
            .put("uid", options.deviceId)
            .put("t", System.currentTimeMillis() / 1000)
        options.cid?.let { payload.put("cid", it) }
        return payload.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildControlPayload(dps: Map<String, Any?>): ByteArray {
        val dpsObject = JSONObject()
        for ((key, value) in dps) dpsObject.put(key, value ?: JSONObject.NULL)

        val now = System.currentTimeMillis() / 1000
        val payload = JSONObject()
        if (options.version.usesSessionKey) {
            // 3.4/3.5: {"protocol":N,"t":...,"data":{"dps":{...}}}.
            // The legacy {devId,uid,t,dps} body is answered with "data format error"
            // and the data point never changes - verified against 3.5 hardware,
            // where queries kept working and made it look like a dead control path.
            payload.put("protocol", options.protocolNumber)
            payload.put("t", now)
            payload.put("data", JSONObject().put("dps", dpsObject))
        } else {
            payload.put("devId", options.deviceId)
            payload.put("uid", options.deviceId)
            payload.put("t", now)
            payload.put("dps", dpsObject)
        }
        options.cid?.let { payload.put("cid", it) }
        return payload.toString().toByteArray(Charsets.UTF_8)
    }

    private class PendingRequest(private val accepted: Set<Int>) {
        val deferred = CompletableDeferred<TuyaMessage>()

        fun accepts(command: Int): Boolean = command in accepted
    }
}
