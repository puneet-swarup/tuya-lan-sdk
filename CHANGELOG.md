# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Releases before 1.1.0 predate this file; `git log` is the record for those.

## [Unreleased]

## [1.1.0]

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
