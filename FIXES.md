# Bug fixes — Tuya LAN SDK

Two real defects and two small ones, fixed in the C# and Kotlin clients. Nothing
touches the crypto or the frame codec: the wire format was already right.

## What is in this drop

| file | what it is |
|---|---|
| `bugfixes.patch` | everything below as one `git apply`-able patch, against `main` (`669bd01`) |
| `TuyaLocalDevice.cs` | the fixed .NET client, whole file, ready to replace |
| `TuyaLocalDevice.kt` | the fixed Kotlin client, whole file, ready to replace |
| `device_console.py` | the new test/integration endpoint (see `06-device-console.md`) |
| `06-device-console.md` | endpoint reference for the console |
| `README.md`, `LICENSE` | small doc changes, included for completeness |

Apply the patch:

```bash
cd tuya-lan-sdk
git apply --check bugfixes.patch     # should print nothing
git apply bugfixes.patch
```

Or replace the two `TuyaLocalDevice` files by hand — the edits are marked with a
comment starting "A failed handshake", "All writes are serialised" and
"Discard anything still in flight".

---

## 1. A failed handshake leaked the socket and wedged the client

**Severity: high.** It fires on the most common user error — a wrong local key —
and the symptom (every later attempt throws "already connected") sends you
looking in the wrong place.

`ConnectAsync` / `connect()` stored the socket, then negotiated the session key
with no error handling. When the negotiation threw, the socket stayed open, the
client still reported `IsConnected == true`, and the next connect threw
`InvalidOperationException("already connected")` / `IllegalStateException`
against a socket nobody could use.

C# — `ConnectAsync`:

```csharp
        try
        {
            if (_options.Version.UsesSessionKey())
            {
                await NegotiateSessionKeyAsync(cancellationToken).ConfigureAwait(false);
            }
        }
        catch
        {
            _stream?.Dispose();
            _stream = null;
            _client?.Dispose();
            _client = null;
            _session.ClearSessionKey();
            _readLength = 0;
            throw;
        }
```

Kotlin — `connect()`:

```kotlin
            try {
                negotiateSessionKey()
            } catch (failure: Throwable) {
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
```

The caller now gets the real error (`TuyaHandshakeException`, "the device
rejected the local key…") and can fix the key and retry on the same object.

## 2. The heartbeat bypassed the request gate

**Severity: medium.** `HeartbeatLoopAsync` / `heartbeatLoop` wrote straight to
the stream while `SendRequestAsync` / `sendRequest` was writing its own frame.
`NetworkStream` and `OutputStream` give no atomicity guarantee for concurrent
writes, so two frames can interleave and both be rejected by the device — which
shows up as a random timeout every few minutes.

Fixed by serialising every write behind a dedicated gate, rather than making the
heartbeat take the request semaphore (which would delay it behind a slow
request):

```csharp
    private async Task WriteAsync(byte[] frame, CancellationToken cancellationToken)
    {
        await _writeGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var stream = _stream ?? throw new InvalidOperationException("the device is not connected");
            await stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _writeGate.Release();
        }
    }
```

Kotlin does the same with a `writeMutex`. `_writeGate` is disposed in
`DisposeAsync`.

## 3. `_pending` was not volatile (C#)

`_pending` is written by the request path and read by the receive loop with no
memory barrier. Reference assignment is atomic so it worked in practice; it is
now `volatile` so the intent is explicit and the compiler cannot hoist the read.

Kotlin's `pending` got `@Volatile` for the same reason.

## 4. Kotlin `disconnect()` kept a dead scope

`disconnect()` cancelled the `CoroutineScope` but left it in the field, so a
reconnect launched jobs into a cancelled scope. It now sets `scope = null`.

---

## Known issue in the SDK, not fixed here

`BuildControlPayload` in both clients sends the legacy envelope on every
protocol version:

```json
{"devId": "…", "uid": "…", "t": …, "dps": {"1": true}}
```

3.4/3.5 firmware may instead expect the modern one:

```json
{"protocol": 5, "t": …, "data": {"dps": {"1": true}}}
```

and the frame codec always prepends a 15-byte `3.5` version header to
`CONTROL` / `CONTROL_NEW`. On a real 3.5 plug tested during this work, that
header is what broke control: the device answered `0x0D` **with** the header
with silence, and the same frame **without** it was acknowledged. Queries kept
working throughout, so it looked like a dead control path rather than a framing
detail.

`HeaderMode` already exists in `TuyaDeviceOptions` for this, but it only affects
3.3/3.4 — `Pack6699` always writes the header. The fix is a `HeaderMode` (or a
`VersionHeader` bool) that reaches the 3.5 packer, plus the payload envelope
choice above.

Not patched here because I cannot test it against hardware: whether a given
device wants the header, and whether it wants `protocol` 4 or 5, varies by
firmware. `tools/device_console.py` now finds the right combination for a device
in one command (`--try`) and reports it, so the SDK fix can be driven by what
that prints. Say the word and I will write it for both languages.

## Deliberately not changed

* **Reply correlation by command family.** Documented design (devices run their
  own sequence counter and 3.5 replies do not echo ours). The consequence is
  real: a status push arriving just after a request starts can be mistaken for
  its reply. It is not fixable without a correlation id, so it is documented
  instead — and `tools/device_console.py` shows the practical workaround (let the
  line go quiet before each request, and use the push as the answer rather than
  querying again).
* **`DisconnectAsync` under concurrent calls.** Two simultaneous disconnects can
  both dispose the same stream; `NetworkStream.Dispose` is idempotent, so the
  worst case is a redundant dispose. A guard is possible but it is not worth the
  extra state.
* **`okhttp` declared `api` in the Android library.** I flagged this in the
  review and I was wrong: `OkHttpClient` appears in the public constructor's
  default argument, so consumers compiling a Kotlin call site need it on the
  compile classpath. `api` is correct.
* **No Kotlin plugin in `android/build.gradle.kts`.** Also correct as written:
  AGP 9 has built-in Kotlin support and the module uses its DSL
  (`kotlin { compilerOptions { … } }`). It just pins the build to AGP 9.x.

## Verification

Run here, on a fresh clone with the patch applied:

* `python3 tools/reference_impl.py --selftest` — all 19 checks pass (unchanged).
* `python3 tools/test_roundtrip.py` — all 15 round-trip checks pass (unchanged).
* `python3 tools/device_console.py --selftest` — all 30 checks pass, including
  the failed-handshake path: a wrong local key is rejected **and leaves no
  socket open**, which is fix 1 exercised end to end. It also covers two cases
  found on real hardware afterwards: a firmware that ignores `CONTROL_NEW` (the
  console falls through to `0x07`) and a momentary relay (reported as `pulsed`,
  not as a failure).

Not verified: the C# and Kotlin edits were not compiled — there is no .NET 10
SDK, JDK or Android SDK in this environment. The edits are small and local (a
try/catch around one call, a semaphore around two writes, one field modifier),
and brace/paren balance was checked, but the first `dotnet build` and
`./gradlew :tuya-lan:test` on your machine is the real gate.

If you want a regression test for fix 1, the cheapest one is in the existing
suites: point `TuyaLocalDevice` at `tools/fake_device.py` with a wrong key,
assert the exception type, then assert `IsConnected` is false and that a second
`ConnectAsync` with the right key succeeds on the same instance.
