<p align="center">
  <img src="assets/logo.svg" width="120" alt="Tuya LAN SDK">
</p>

<h1 align="center">Tuya LAN SDK</h1>

<p align="center">
  Talk to Tuya smart plugs and switches on your own network — C# (.NET 10) and Kotlin (Android).
</p>

<p align="center">
  <a href="https://github.com/puneet-swarup/tuya-lan-sdk/actions/workflows/ci.yml"><img src="https://github.com/puneet-swarup/tuya-lan-sdk/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="https://github.com/puneet-swarup/tuya-lan-sdk/releases"><img src="https://img.shields.io/github/v/release/puneet-swarup/tuya-lan-sdk" alt="Release"></a>
  <a href="https://www.nuget.org/packages/TuyaLan"><img src="https://img.shields.io/nuget/v/TuyaLan" alt="NuGet"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/puneet-swarup/tuya-lan-sdk" alt="License"></a>
  <img src="https://img.shields.io/badge/.NET-10.0-512BD4" alt=".NET 10">
  <img src="https://img.shields.io/badge/Android-minSdk%2024-3DDC84" alt="Android">
  <img src="https://img.shields.io/badge/Python-3.11+-3776AB" alt="Python 3.11+">
</p>

---

A complete, independent implementation of the Tuya local protocol for
protocol versions **3.3, 3.4 and 3.5**, plus a client for the Tuya Cloud API.
No Tuya SDK, no Tuya app, no third-party protocol library.

## What it does

**On the local network** (no cloud, no account, no internet):

* Discovery over UDP — device id, IP, protocol version, product key.
* Session-key handshake for 3.4 and 3.5 (AES-128-GCM on 3.5).
* Read data points, set data points, subscribe to status pushes, heartbeat.
* 3.3, 3.4 and 3.5 in one API, with the firmware quirks absorbed rather than
  pushed onto you.

**Through the Tuya cloud** (HTTPS):

* Token management, device list, status, commands, all HMAC-SHA256 signed.
* Useful when a device will not accept local control, or when you need the
  local key in the first place.

## Install

```bash
dotnet add package TuyaLan
```

```kotlin
implementation("com.tuyalan:tuya-lan:2.0.0")
```

The .NET package is on [nuget.org](https://www.nuget.org/packages/TuyaLan).

The Android library is **not** on Maven Central yet — that needs the namespace
and signing set up, and [`docs/07-publishing.md`](docs/07-publishing.md) walks
through it. Until then, take the `.aar` from the
[releases page](https://github.com/puneet-swarup/tuya-lan-sdk/releases), or
reference the projects directly:

```bash
git clone https://github.com/puneet-swarup/tuya-lan-sdk.git
dotnet add reference tuya-lan-sdk/csharp/src/TuyaLan/TuyaLan.csproj
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
dotnet build csharp/TuyaLan.sln
dotnet test  csharp/TuyaLan.sln
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
    device.connectAsync()
    val status = device.getStatusAsync()
    device.setDpAsync("1", true)
}
```

```bash
cd kotlin && ./gradlew :tuya-lan:test
```

## Compatibility

| protocol | frame | crypto | session key | state |
|---|---|---|---|---|
| 3.3 | `0x000055AA` | AES-128-ECB + CRC-32 | no | supported |
| 3.4 | `0x000055AA` | AES-128-ECB + HMAC-SHA256 | yes | supported |
| 3.5 | `0x00006699` | AES-128-GCM | yes | supported, verified on hardware |

| platform | requirement |
|---|---|
| .NET | `net10.0` |
| Android | `minSdk 24`, `compileSdk 36`, AGP 9 |
| Python tools | 3.11+, `cryptography` |

## Validate a real plug

`tools/device_console.py` is a single-file harness that connects to one device
and switches it, either from a web page or from a JSON endpoint you can call
from your own code. It is also how you find the control shape an unknown
firmware wants.

```bash
python3 tools/device_console.py --try --host 192.168.1.42 \
    --device-id eb0123456789abcdefghij --key 0123456789abcdef
```

```bash
python3 tools/device_console.py              # http://127.0.0.1:8080
python3 tools/device_console.py --demo       # with a fake device to click through
python3 tools/device_console.py --selftest   # 43 checks, no browser
```

```bash
curl -s localhost:8080/api/connect -H 'Content-Type: application/json' -d '{
  "host": "192.168.1.42", "device_id": "eb0123456789abcdefghij",
  "local_key": "0123456789abcdef", "version": "3.5"}'

curl -s localhost:8080/api/devices/eb0123456789abcdefghij/switch \
  -H 'Content-Type: application/json' -d '{"on": true}'
```

It speaks 3.3, 3.4 and 3.5, reports the data points the device returns, and
confirms the switch by reading the state back rather than trusting the
acknowledgement. Full endpoint list in
[`docs/06-device-console.md`](docs/06-device-console.md).

## What protocol 3.5 actually is

Worth stating plainly, because it is widely misremembered as "3.4 with a different
signature": 3.5 is a **new frame** (`0x00006699` instead of `0x000055AA`) with
**AES-128-GCM** instead of AES-ECB, a **per-connection session key** negotiated in
a three-message handshake, and the return code moved inside the ciphertext. A 3.5
device rejects a 3.3/3.4 client at the handshake, and the failure looks exactly
like a wrong local key.

The payload changes too: 3.4 and 3.5 want
`{"protocol":5,"t":…,"data":{"dps":{…}}}` for control, not the 3.1–3.3
`{devId,uid,t,dps}`. Send the old one and the device answers `data format error`
while queries keep working — the single most confusing failure in this protocol.
Full details in [`docs/01-protocol-3.5.md`](docs/01-protocol-3.5.md).

## Repository layout

```
tuya-lan-sdk/
├── docs/                      protocol, per-language guides, console, publishing
├── csharp/                    .NET 10 library, console demo, xUnit tests
├── android/                   Android library module, sample app, JVM tests
├── tools/
│   ├── reference_impl.py      independent Python implementation + vector generator
│   ├── fake_device.py         a working 3.5 device simulator (no hardware needed)
│   ├── test_roundtrip.py      end-to-end handshake / control / push test
│   ├── device_console.py      web page + REST API to test on/off on a real plug
│   └── test_vectors.json      generated vectors
└── assets/                    logo
```

## Verification status

Be clear about what has and has not been checked.

**Checked, and reproducible here.** The wire format is implemented twice
independently — once in `tools/reference_impl.py` and once in each of the C# and
Kotlin libraries — and the vectors in `docs/05-test-vectors.md` are generated from
the Python implementation. `python3 tools/reference_impl.py --selftest` passes all
19 of its checks, including a byte-exact match against **Tuya's own published
cloud signature example**, and against the official discovery key
`MD5("yGAdlopoPVldABfn")`.

The session layer is exercised end to end as well: `python3 tools/test_roundtrip.py`
starts `tools/fake_device.py` and drives handshake → DP_QUERY → CONTROL → status
push → heartbeat, 15 checks. `python3 tools/device_console.py --selftest` adds 43
more, covering 3.3 and 3.4 framing, both control bodies, with and without the
version header, error replies, and a firmware that only implements one control
command.

**Hardware verification.** Protocol 3.5 has been verified end to end on one real
plug: session-key handshake, `DP_QUERY`, and switching a relay with
`CONTROL_NEW` and the modern body, confirmed by the device reporting the new
state. That is one device on one firmware revision. 3.3 and 3.4 are verified
against the reference implementation and the framing tests, not against hardware.
Before relying on this in production, run
`python3 tools/device_console.py --try` against your own device and check the
data points it reports.

**Deliberately not covered.** Device provisioning (AP config, token bind),
gateway sub-protocols beyond the `cid` field, and category-specific DP encodings
(colour strings, IR codes). Those are payload conventions rather than protocol.

## Design notes

* **Frame codec is separate from transport.** `TuyaFrameCodec` turns bytes into
  messages and back; `TuyaSession` owns keys and sequence numbers;
  `TuyaLocalDevice` owns the socket. You can unit-test the first two without
  hardware, and drive a fake device with them.
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

## Documentation

| guide | for |
|---|---|
| [Protocol 3.5](docs/01-protocol-3.5.md) | the wire format, if you want to check the code against the spec |
| [.NET guide](docs/02-csharp-guide.md) | the C# client in detail |
| [Android guide](docs/03-android-guide.md) | the Kotlin client in detail |
| [Cloud API](docs/04-cloud-api.md) | signing, endpoints, regions |
| [Test vectors](docs/05-test-vectors.md) | known-answer vectors |
| [Device console](docs/06-device-console.md) | testing a real plug, REST API |
| [Publishing](docs/07-publishing.md) | releasing to NuGet and Maven Central |

## Contributing

Bug reports, firmware quirks and pull requests are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md) for how to run the suites and what evidence a
change needs. Changes are recorded in [CHANGELOG.md](CHANGELOG.md). Please do not
paste local keys or device ids into issues.

## License

MIT. See [LICENSE](LICENSE).

This is an independent implementation written from the public reverse-engineering
record; it is not affiliated with or endorsed by Tuya. Use it on hardware you own.

## Two APIs, one engine

The library is written Java-first: the core interface is blocking, returns values
and reports events through listeners. Kotlin callers get the same object with a
coroutine API on it.

| Java | Kotlin |
|---|---|
| `device.connect()` | `device.connectAsync()` |
| `device.getStatus()` | `device.getStatusAsync()` |
| `device.setDp("1", true)` | `device.setDpAsync("1", true)` |
| `device.close()` | `device.disconnectAsync()` |
| `device.addStatusListener(l -> ...)` | `device.statuses.collect { }` |

```java
TuyaDeviceOptions options = new TuyaDeviceOptionsBuilder()
        .host("192.168.1.42")
        .deviceId("bf1234567890abcdefghij")
        .localKey("0123456789abcdef")
        .version(TuyaProtocolVersion.V33)
        .build();

try (TuyaDevice device = new TuyaLocalDevice(options)) {
    device.addStatusListener(status -> System.out.println(status.getDps()));
    device.connect();
    Boolean on = device.getStatus().getBool("1");
    device.setDp("1", !Boolean.TRUE.equals(on));
}
```

`TuyaException` is a checked exception: Java must catch it, and that is deliberate
- a device that cannot be reached is not an exceptional edge case in this domain,
it is Tuesday.

## Connection lifecycle

A Tuya device accepts **one session at a time**. What the library guarantees,
and what a host has to do:

| | |
|---|---|
| `IsConnected` | authoritative. It turns false as soon as the receive loop or the heartbeat sees the socket go. |
| `Disconnected` | raised **once** per death, with the exception that caused it. |
| Reconnection | **not automatic.** The host decides when to retry - a battery-powered host may not want to. |
| `EnsureConnectedAsync` | connect-if-needed. Call this before an operation rather than caching the device and checking `IsConnected` by hand. |

The failure this section exists to prevent: caching a `TuyaLocalDevice`, letting
the connection die quietly (router idle timeout, Wi-Fi power save, a device
reboot, or the host machine sleeping), and then reusing it. Every command times
out, nothing reconnects, and the device looks unreachable for ever. Before 2.1.0
`IsConnected` stayed true through all of that.

Two things follow:

- **Enable the heartbeat** if the host is long-running. It is what detects a
  connection that dies while nothing is being sent.
- **Drop the device on any failure**, not just when `IsConnected` says so. A
  command that times out is a strong hint that the socket is gone.

