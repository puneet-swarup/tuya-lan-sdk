# Tuya LAN SDK — C# (.NET 10) and Kotlin (Android)

[![CI](https://github.com/puneet-swarup/tuya-lan-sdk/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/puneet-swarup/tuya-lan-sdk/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/puneet-swarup/tuya-lan-sdk)](https://github.com/puneet-swarup/tuya-lan-sdk/releases)
[![License](https://img.shields.io/github/license/puneet-swarup/tuya-lan-sdk)](LICENSE)
[![.NET](https://img.shields.io/badge/.NET-10.0-512BD4)](https://dotnet.microsoft.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF)](https://kotlinlang.org/)
[![Python](https://img.shields.io/badge/Python-3.11+-3776AB)](https://www.python.org/)
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
├── dotnet/                    .NET 10 library, console demo, xUnit tests
├── android/                   Android library module, sample app, JVM tests
└── tools/
    ├── reference_impl.py      independent Python implementation + vector generator
    ├── fake_device.py         a working 3.5 device simulator (no hardware needed)
    ├── test_roundtrip.py      end-to-end handshake / control / push test
    ├── device_console.py      web page + REST API to test on/off on a real plug
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
    LocalKey = "0123456789abcdef", // or LocalKeyBytes = keyBytes for exact binary keys
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

## Validate a real plug

`tools/device_console.py` is a single-file harness that connects to one device
and switches it, either from a web page or from a JSON endpoint you can call
from your own code:

```bash
python3 tools/device_console.py              # http://127.0.0.1:8080
python3 tools/device_console.py --demo       # with a fake device to click through
python3 tools/device_console.py --selftest   # end-to-end check, no browser
```

```bash
curl -s localhost:8080/api/connect -H 'Content-Type: application/json' -d '{
  "host": "192.168.1.42", "device_id": "eb0123456789abcdefghij",
  "local_key": "0123456789abcdef", "version": "3.5"}'

curl -s localhost:8080/api/devices/eb0123456789abcdefghij/switch \
  -H 'Content-Type: application/json' -d '{"on": true}'
```

It speaks 3.3, 3.4 and 3.5, reports the data points the device returns, and
confirms the switch by reading the state back rather than trusting the ack.
Full endpoint list in [`docs/06-device-console.md`](docs/06-device-console.md).

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
19 of its checks, including a byte-exact match against **Tuya's own published
cloud signature example**, and against the official discovery key
`MD5("yGAdlopoPVldABfn")`.

The session layer is exercised end to end as well: `python3
tools/test_roundtrip.py` starts `tools/fake_device.py` — a working 3.5 device
simulator — and drives handshake → DP_QUERY → CONTROL → status push → heartbeat,
15 checks, all passing. That is the same sequence `TuyaLocalDevice` implements in
both languages, including the two firmware quirks it has to absorb (a return code
prefixed to the handshake reply, and a return code on data frames).

**Build and simulator verification.** The .NET 10 test suite and Android Gradle library tests have been run in the development environment. The Python reference self-test and simulated-device round-trip test also pass. These checks validate the protocol implementation but do not replace device-specific hardware verification.

**Hardware verification.** Hardware verification is not yet complete. The automated simulator and protocol tests do not establish compatibility with every device model or firmware revision. Before relying on the SDK in production, verify an authenticated session and device-specific DP control against the target hardware.

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
