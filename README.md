# Tuya LAN SDK — C# (.NET 8) and Kotlin (Android)

A dependency-light implementation of the **Tuya LAN protocol 3.5** (plus 3.4 and
3.3 for older firmware) and the **Tuya Cloud API**, in two parallel libraries
that share one wire format and one set of test vectors.

* Local control over TCP 6668: AES-128-GCM frames, per-connection session keys,
  data points, status pushes, heartbeat, discovery.
* Cloud control over HTTPS: token management, device list, status, commands,
  all HMAC-SHA256 signed.
* No Tuya SDK, no Tuya app, no third-party protocol library.

```
tuya-lan-sdk/
├── docs/
│   ├── 01-protocol-3.5.md     the wire format this SDK implements
│   ├── 02-csharp-guide.md     .NET usage
│   ├── 03-android-guide.md    Kotlin usage
│   ├── 04-cloud-api.md        cloud signing and endpoints
│   └── 05-test-vectors.md     known-answer vectors
├── dotnet/                    .NET 8 library, console demo, xunit tests
├── android/                   Android library module, sample app, JVM tests
└── tools/
    ├── reference_impl.py      independent Python implementation + vector generator
    ├── fake_device.py         a working 3.5 device simulator (no hardware needed)
    ├── test_roundtrip.py      end-to-end handshake / control / push test
    └── test_vectors.json      generated vectors
```

## Quick start — C#

```csharp
using TuyaLan.Devices;
using TuyaLan.Protocol;

await using var device = new TuyaLocalDevice(new TuyaDeviceOptions
{
    Host = "192.168.1.42",
    DeviceId = "eb0123456789abcdefghij",
    LocalKey = "0123456789abcdef",
    Version = TuyaProtocolVersion.V35,
});

await device.ConnectAsync();
var status = await device.GetStatusAsync();
await device.SetDpAsync("1", true);
```

```bash
dotnet build dotnet/TuyaLan.sln
dotnet test  dotnet/TuyaLan.sln
```

## Quick start — Kotlin

```kotlin
val device = TuyaLocalDevice(
    TuyaDeviceOptions(
        host = "192.168.1.42",
        deviceId = "eb0123456789abcdefghij",
        localKey = "0123456789abcdef",
        version = TuyaProtocolVersion.V35,
    ),
)

lifecycleScope.launch {
    device.connect()
    val status = device.getStatus()
    device.setDp("1", true)
}
```

```bash
cd android && ./gradlew :tuya-lan:test
```

## What protocol 3.5 actually is

Worth stating plainly, because it is widely misremembered as "3.4 with a different
signature": 3.5 is a **new frame** (`0x00006699` instead of `0x000055AA`) with
**AES-128-GCM** instead of AES-ECB, a **per-connection session key** negotiated in
a three-message handshake, and the return code moved inside the ciphertext. A 3.5
device rejects a 3.3/3.4 client at the handshake, and the failure looks exactly
like a wrong local key. Full details in
[`docs/01-protocol-3.5.md`](docs/01-protocol-3.5.md).

## Verification status

Be clear about what has and has not been checked:

**Checked, and reproducible here.** The wire format is implemented twice
independently — once in `tools/reference_impl.py` and once in each of the C# and
Kotlin libraries — and the vectors in `docs/05-test-vectors.md` are generated from
the Python implementation. `python3 tools/reference_impl.py --selftest` passes all
18 of its checks, including a byte-exact match against **Tuya's own published
cloud signature example**, and against the official discovery key
`MD5("yGAdlopoPVldABfn")`.

The session layer is exercised end to end as well: `python3
tools/test_roundtrip.py` starts `tools/fake_device.py` — a working 3.5 device
simulator — and drives handshake → DP_QUERY → CONTROL → status push → heartbeat,
15 checks, all passing. That is the same sequence `TuyaLocalDevice` implements in
both languages, including the two firmware quirks it has to absorb (a return code
prefixed to the handshake reply, and a return code on data frames).

**Not checked here.** This environment has no .NET SDK and no JDK, so neither the
C# nor the Kotlin project has been compiled, and no code has been run against a
real Tuya device. Compile both before relying on them, and treat the first run
against hardware as a validation step — `dotnet test` and `./gradlew
:tuya-lan:test` will confirm the protocol core matches the reference model, and a
device will confirm the parts only hardware can confirm (handshake timing, whether
your firmware sends a return code, whether it answers `DP_QUERY` or only
`DP_QUERY_NEW`).

**Deliberately not covered.** Device provisioning (AP config, token bind),
gateway sub-protocols beyond the `cid` field, and category-specific DP encodings
(colour strings, IR codes). Those are payload conventions rather than protocol.

## Design notes

* **Frame codec is separate from transport.** `TuyaFrameCodec` turns bytes into
  messages and back; `TuyaSession` owns keys and sequence numbers;
  `TuyaLocalDevice` owns the socket. You can unit-test the first two without
  hardware, and drive a fake device with them (see `tools/fake_device.py` for a
  3.5 device simulator to test against).
* **Return codes are auto-detected.** Firmware disagrees about whether a reply
  carries one, so the decoder decides arithmetically (block alignment) on 3.3/3.4
  and by inspecting the first bytes on 3.5, with `RetcodePolicy` to override.
* **One request in flight.** Tuya devices serialise on their side; both clients
  hold a semaphore and correlate replies by command family rather than by sequence
  number, because 3.5 devices use a global sequence counter of their own.
* **Both APIs are async and cancellable** — `Task`/`CancellationToken` in C#,
  `suspend`/coroutines in Kotlin.

## Getting the local key

Discovery gives you the IP, device id and protocol version. The 16-character
**local key** comes from either:

1. the Tuya/Smart Life app's device information screen, or
2. `GET /v1.0/devices/{id}` through the cloud API (`TuyaCloudClient.GetDeviceAsync`),
   which needs a Tuya IoT project with the device linked to it.

Without a key you can still discover devices and read their announcements; you
cannot decrypt or send control frames.

## License

MIT. See `LICENSE`.

This is an independent implementation written from the public reverse-engineering
record; it is not affiliated with or endorsed by Tuya. Use it on hardware you own.
