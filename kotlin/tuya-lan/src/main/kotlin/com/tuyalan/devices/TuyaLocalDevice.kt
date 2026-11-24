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
import com.tuyalan.device.TuyaDevice
import com.tuyalan.device.TuyaDeviceAsync
import com.tuyalan.device.TuyaDisconnectListener
import com.tuyalan.device.TuyaErrorListener
import com.tuyalan.device.TuyaStatusListener
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.runBlocking
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
 * device.connectAsync()
 * val status = device.getStatus()
 * device.setDp("1", true)
 * device.close()
 * ```
 */
class TuyaLocalDevice(val options: TuyaDeviceOptions) : TuyaDevice, TuyaDeviceAsync, Closeable {

    private val session = TuyaSession(options.version, options.localKeyBytes?.copyOf() ?: options.localKey.toByteArray(Charsets.UTF_8)).apply {
        headerMode = options.headerMode
        retcodePolicy = options.retcodePolicy
    }

    private var socket: Socket? = null

    /** Set when the receive loop or the heartbeat finds the connection gone. */
    @Volatile
    private var disconnected = false
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

    // The flows above are the Kotlin binding. These are the Java one - a
    // SharedFlow needs a coroutine to collect, which a Java caller does not have.
    private val statusListeners = CopyOnWriteArrayList<TuyaStatusListener>()
    private val disconnectListeners = CopyOnWriteArrayList<TuyaDisconnectListener>()
    private val errorListeners = CopyOnWriteArrayList<TuyaErrorListener>()

    /** Protocol revision in use. */
    val version: TuyaProtocolVersion get() = session.version

    /** True once the 3.4/3.5 handshake has completed. */
    val hasSessionKey: Boolean get() = session.hasSessionKey

    /**
     * True while the socket is usable.
     *
     * [java.net.Socket.isConnected] only says the socket was connected once; it
     * stays true after the peer closes. Combined with [java.net.Socket.isClosed],
     * which is only true when *we* closed it, a connection that died quietly read
     * as healthy for ever - so a host that cached the device reused a dead socket
     * and every command timed out. The receive loop and the heartbeat already
     * notice the death; they now record it here.
     */
    override val isConnected: Boolean
        get() {
            if (disconnected) return false
            val current = socket ?: return false
            return current.isConnected && !current.isClosed
        }

    /** Connects only if the connection is not usable. */
    suspend fun ensureConnectedAsync() {
        if (!isConnected) connectAsync()
    }

    /** Opens the TCP connection and negotiates a session key when the version needs one. */
    override suspend fun connectAsync() {
        if (isConnected) throw IllegalStateException("already connected")

        // A connection that died leaves its socket behind; drop it rather than
        // refuse to connect because of one nobody can use.
        if (socket != null) disconnectAsync()

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
            disconnected = false
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
                // still report true and the next connectAsync() would throw "already connected".
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
    override suspend fun disconnectAsync() {
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
        disconnected = false
    }

    /** Queries the current data points. */
    override suspend fun getStatusAsync(): TuyaStatus {
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
    suspend fun setDpsAsync(dps: TuyaDps) = setDpsAsync(dps.asMap)

    /** Sends one or more data points and waits for the device to acknowledge. */
    override suspend fun setDpsAsync(dps: Map<String, Any?>) {
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
    override suspend fun setDpAsync(dpId: String, value: Boolean) = setDpsAsync(TuyaDps().set(dpId, value))

    /** Sets a single numeric data point, e.g. brightness. */
    override suspend fun setDpAsync(dpId: String, value: Int) = setDpsAsync(TuyaDps().set(dpId, value))

    /** Sets a single string data point, e.g. a work mode. */
    override suspend fun setDpAsync(dpId: String, value: String) = setDpsAsync(TuyaDps().set(dpId, value))

    /** Sends a heartbeat and returns true when the device answers. */
    override suspend fun pingAsync(): Boolean = try {
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
    suspend fun sendRawCommandAsync(
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

    // ---- TuyaDevice: the Java-facing, blocking half ------------------------

    override val protocolVersion: String get() = session.version.label

    override fun connect() = runBlocking { connectAsync() }

    override fun getStatus(): TuyaStatus = runBlocking { getStatusAsync() }

    override fun setDps(dps: Map<String, Any?>) = runBlocking { setDpsAsync(dps) }

    override fun setDp(dpId: String, value: Boolean) = runBlocking { setDpAsync(dpId, value) }

    override fun setDp(dpId: String, value: Int) = runBlocking { setDpAsync(dpId, value) }

    override fun setDp(dpId: String, value: String) = runBlocking { setDpAsync(dpId, value) }

    override fun ping(): Boolean = runBlocking { pingAsync() }

    override fun addStatusListener(listener: TuyaStatusListener) { statusListeners.add(listener) }

    override fun addDisconnectListener(listener: TuyaDisconnectListener) { disconnectListeners.add(listener) }

    override fun addErrorListener(listener: TuyaErrorListener) { errorListeners.add(listener) }

    override fun removeStatusListener(listener: TuyaStatusListener) { statusListeners.remove(listener) }

    override fun removeDisconnectListener(listener: TuyaDisconnectListener) { disconnectListeners.remove(listener) }

    override fun removeErrorListener(listener: TuyaErrorListener) { errorListeners.remove(listener) }

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
            val stream = output ?: throw IllegalStateException("the device is not connected; call connectAsync() first")
            stream.write(frame)
            stream.flush()
        }
    }

    private suspend fun readFrame(): TuyaRawFrame = withContext(Dispatchers.IO) {
        val stream = input ?: throw IllegalStateException("the device is not connected; call connectAsync() first")
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

    /** One place that reports an error, to flows and to listeners alike. */
    private fun emitError(error: TuyaException) {
        _protocolErrors.tryEmit(error)
        errorListeners.forEach { runCatching { it.onError(error) } }
    }

    private suspend fun receiveLoop() {
        try {
            while (true) {
                val raw = readFrame()
                dispatch(raw)
            }
        } catch (e: IOException) {
            if (isConnected) {
                disconnected = true
                emitError(TuyaProtocolException("connection lost: ${e.message}", e))
                disconnectListeners.forEach { runCatching { it.onDisconnected(e) } }
            }
        } catch (e: TuyaException) {
            emitError(e)
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
            emitError(e)
            return
        }

        val request = pending
        if (request != null && request.accepts(message.command)) {
            request.deferred.complete(message)
            return
        }

        if (TuyaCommand.carriesDataPoints(message.command) && message.payload.isNotEmpty()) {
            val status = TuyaStatus.parse(message.payload)
            if (status.hasData) {
                _statuses.tryEmit(status)
                statusListeners.forEach { runCatching { it.onStatus(status) } }
            }
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
