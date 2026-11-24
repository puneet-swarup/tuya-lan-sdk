# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Releases before 2.0.0 predate this file; `git log` is the record for those.

## [Unreleased]

### Documented
- The Android guide showed `device.connect()` / `getStatus()` / `setDp()` inside a coroutine. After 3.0.0 those are the *blocking* methods, so following the guide would pin a thread; they are `connectAsync` / `getStatusAsync` / `setDpAsync` now.
- The C# guide never mentioned `EnsureConnectedAsync`, `Disconnected`, or what `IsConnected` means - the three things 2.1.0 changed.
- The README claimed both APIs are async; the Kotlin core is blocking first, with coroutines as `TuyaDeviceAsync`.

## [3.0.0] - 2026-11-24

### Changed (breaking)
- **The API is Java-first.** The library was coroutine-based throughout, so a Java caller could do nothing without a `Continuation` - the reason 2.2.0 shipped a blocking facade. That was the wrong shape for a public library: Kotlin calls Java happily, Java cannot call Kotlin happily, so the core should be the language both can use. `TuyaDevice` is now a blocking interface - `connect()`, `close()`, `getStatus()`, `setDps()`, `setDp()`, `ping()`, and listener registration - and `TuyaLocalDevice` implements it directly. The facade is gone; there is one front door.
- **Events are listeners.** `addStatusListener`, `addDisconnectListener`, `addErrorListener` take SAM interfaces that Java can pass a lambda to. The `statuses` and `protocolErrors` flows remain as the Kotlin binding.
- **The coroutine API moved to `TuyaDeviceAsync`**, implemented by the same object: `connectAsync()`, `disconnectAsync()`, `getStatusAsync()`, `setDpsAsync()`, `setDpAsync()`, `pingAsync()`. Kotlin callers use this and never wrap a blocking call in `runBlocking` to avoid pinning a thread.
- **`TuyaDeviceOptionsBuilder`** builds the options from Java. A data class with default arguments has no Java-friendly constructor.

### Why the version is 3.0.0
Renaming `connect()` to `connectAsync()` on the suspend side and taking the plain name for the blocking side breaks every Kotlin caller. One consumer existed (Battery Guardian), and it was updated in the same release.

## [2.2.0] - 2026-11-24

### Added
- **Java support.** The library is coroutine-based, so `connect()`, `getStatus()` and the setters are `suspend` functions Java cannot call without building a `Continuation` by hand. `com.tuyalan.java.TuyaLanDevice` wraps every call in a blocking method - `connect()`, `getStatus()`, `getBool()`, `setBool()`, `setInt()`, `setString()`, `ping()`, `close()` - plus a `create()` factory so Java never meets a Kotlin default argument. `JavaInteropTest` compiles and runs with the unit tests, so the build fails if Java support breaks.

### Changed (repository)
- **`dotnet/` is now `csharp/`, `android/` is now `kotlin/`.** One folder was named after a framework, the other after a platform; both are now named after the language, which is what decides how you consume them. Workflows, the PR template, CONTRIBUTING and the guides were updated with the paths.

## [2.1.1] - 2026-11-23

### Fixed (Android)
- **The same connection-state defect as 2.1.0, in Kotlin.** `isConnected` was `socket.isConnected && !socket.isClosed`; Java's `isConnected` only means the socket was connected once and stays true after the peer closes, while `isClosed` is true only when the caller closed it. A connection that died quietly therefore read as healthy for ever, and a host that cached the device reused a dead socket. The receive loop now records the loss, and `disconnect()` clears it.
- `connect()` drops a dead socket instead of throwing "already connected".

### Added (Android)
- `ensureConnected()` - connect if needed, so a host does not cache the device and guess.

## [2.1.0] - 2026-11-23

### Fixed
- **`IsConnected` now means what it says.** It was `_stream is not null`, which stayed true after the socket died, until someone called `DisconnectAsync`. A host that cached the device and checked this property - the obvious thing to do, and what the documentation implied - reused a dead connection indefinitely: every command timed out, nothing reconnected, and the device looked unreachable for ever. The receive loop and the heartbeat already detected the death; they now record it, so the property answers the question it is asked.
- `ConnectAsync` no longer refuses to connect when a dead socket is still attached; it disconnects it first.

### Added
- **`EnsureConnectedAsync`.** Connects only if the connection is not usable. A host should call this before every operation instead of caching the device and guessing at its state - that pattern is what produced the bug above.

### Documented
- A "Connection lifecycle" section in the README states the contract: one session per device, `IsConnected` is authoritative, `Disconnected` fires once per death, and there is no automatic reconnection - the host decides when to retry.

## [2.0.0] - 2026-11-21

Major rather than minor because the control-body fix changes behaviour:
`SetDpsAsync` / `setDp` can now throw where they used to return silently, and
3.4/3.5 control frames are built differently. A device whose firmware somehow
accepted the legacy body on 3.4/3.5 will now be sent the modern one — set
`ProtocolNumber` to match what it wants, or use `tools/device_console.py --try`
to find out.

### Fixed

- **Control frames on protocol 3.4 / 3.5 now use the modern body**
  (`{"protocol":N,"t":…,"data":{"dps":{…}}}`). The legacy `{devId,uid,t,dps}`
  body was answered with `data format error` and the data point never changed,
  while queries kept working — so it looked like a device refusing local control
  rather than a payload mismatch. Verified on hardware.
- **A rejected control no longer looks like a successful one.**
  `SetDpsAsync` / `setDps` now raise `TuyaProtocolException` when the device
  replies with a short plain-text complaint (`data format error`,
  `json obj data unvalid`, …). JSON replies are unaffected.
- **A failed session-key handshake no longer leaks the socket.** The connection
  is torn down before the exception propagates, so `IsConnected` is honest and a
  retry on the same object works instead of throwing `already connected`.
- **Concurrent writes are serialised.** The heartbeat loop wrote to the stream
  outside the request gate, so a heartbeat and a command frame could interleave.
- `_pending` / `pending` is now `volatile` / `@Volatile`; the receive loop read it
  with no memory barrier.
- Kotlin `disconnect()` clears the cancelled `CoroutineScope`.

### Added

- `TuyaDeviceOptions.ProtocolNumber` / `protocolNumber` — the `protocol` value
  used in 3.4/3.5 control bodies (default 5).
- `tools/device_console.py` — a web page and JSON REST API for testing one device,
  with a search that finds the control shape a firmware accepts.
- `tools/fake_device.py --envelope {both,modern,legacy}` — the simulator can now
  reject the wrong control body, the way real firmware does.

### Changed

- `docs/01-protocol-3.5.md`: the control-body section corrected, with the
  difference between 3.1–3.3 and 3.4/3.5 spelled out.

## [1.0.1]

Initial published layout: 3.3 / 3.4 / 3.5 local protocol, cloud client, C# and
Kotlin clients, Python reference implementation and test vectors.
