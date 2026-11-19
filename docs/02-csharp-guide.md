# C# / .NET guide

Target framework: **net10.0** (`AesGcm` is a .NET Core 3.0+ type and is not
available on `netstandard2.0` or .NET Framework). On .NET Framework, replace the
two `AesGcm` calls in `TuyaCrypto` with BouncyCastle's `GcmBlockCipher`; every
other part of the library is framework-neutral.

```
dotnet/TuyaLan.sln
├── src/TuyaLan/                     the library
├── samples/TuyaLan.Demo/            console playground
└── tests/TuyaLan.Tests/             known-answer tests (xunit)
```

```bash
dotnet build dotnet/TuyaLan.sln
dotnet test  dotnet/TuyaLan.sln
dotnet run   --project dotnet/samples/TuyaLan.Demo -- scan
```

## Namespaces

| Namespace | Contents |
|---|---|
| `TuyaLan` | `TuyaException` and its subtypes |
| `TuyaLan.Protocol` | frame codec, crypto, session, command ids, enums |
| `TuyaLan.Devices` | `TuyaLocalDevice`, `TuyaDiscovery`, `TuyaStatus`, `TuyaDps` |
| `TuyaLan.Cloud` | `TuyaCloudClient`, signer, models |

## Local control

```csharp
using TuyaLan.Devices;
using TuyaLan.Protocol;

await using var device = new TuyaLocalDevice(new TuyaDeviceOptions
{
    Host = "192.168.1.42",
    DeviceId = "eb0123456789abcdefghij",
    LocalKey = "0123456789abcdef",     // exactly 16 characters
    Version = TuyaProtocolVersion.V35, // take this from discovery
});

device.StatusUpdated += (_, e) =>
    Console.WriteLine($"push: {e.Status}");

device.ProtocolError += (_, e) =>
    Console.WriteLine($"bad frame: {e.Error.Message}");

await device.ConnectAsync();           // performs the session-key handshake

var status = await device.GetStatusAsync();
bool? switchState = status.GetBool("1");

await device.SetDpAsync("1", !(switchState ?? false));

await device.SetDpsAsync(new TuyaDps()
    .Set("1", true)
    .Set("2", 500)          // brightness
    .SetEnum("3", "white")  // work mode
    .SetRaw("24", "014a03e803a9"));
```

`TuyaLocalDevice` is `IAsyncDisposable`; `DisposeAsync` closes the socket and
stops the receive and heartbeat loops.

### Reading the status

`TuyaStatus` handles all three shapes a device uses (`{"dps":…}`,
`{"data":{"dps":…}}`, and a double-encoded `data` string). Values stay as
`JsonNode`, with typed helpers:

```csharp
status.GetBool("1");     // bool?
status.GetInt("2");      // int?
status.GetNumber("2");   // double?
status.GetString("3");   // string?
status["24"];            // JsonNode?  (raw)
```

### Behaviour worth knowing

* **One request at a time.** Tuya devices serialise on their side, so commands
  queue behind a semaphore.
* **Control acknowledgements are optional.** Many devices answer a CONTROL frame
  with an unsolicited STATUS push instead of a reply. Status frames count as the
  ack; set `WaitForControlAck = false` to fire and forget.
* **`DP_QUERY` can be rejected.** Some firmware answers
  `json obj data unvalid`; `GetStatusAsync` retries once with `DP_QUERY_NEW`
  automatically.
* **`device22` devices** (22-character `gwId`) need
  `DeviceType = TuyaDeviceType.Device22`.
* **Wrong key looks like a handshake error.** On 3.5 the GCM tag fails before any
  HMAC check, so a bad key surfaces as `TuyaHandshakeException` with a nonce-HMAC
  message or as `TuyaCryptoException`.

### Raw commands

Anything the library does not model:

```csharp
var reply = await device.SendRawCommandAsync(
    TuyaCommand.UpdateDps,
    Array.Empty<byte>(),
    new[] { TuyaCommand.Status });
```

## Discovery

```csharp
var devices = await TuyaDiscovery.ScanAsync(TimeSpan.FromSeconds(6));
foreach (var device in devices)
{
    Console.WriteLine($"{device.IpAddress} {device.DeviceId} v{device.Version.ToLabel()} {device.ProductKey}");
}
```

`ScanAsync` binds UDP 6666, 6667 and 7000, decrypts all three announcement
formats, and sends the 3.5 solicitation broadcast. If every bind fails it throws
`TuyaException` — usually the Smart Life app is holding the ports.

Discovery gives you the IP, the device id and the protocol version, **not** the
local key. Get the key from the cloud API (`TuyaCloudClient.GetDeviceAsync`) or
from the Tuya app's device information screen.

## Cloud API

```csharp
using var cloud = new TuyaCloudClient(new TuyaCloudOptions
{
    ClientId = "your-access-id",
    Secret = "your-access-secret",
    Region = TuyaCloudRegion.India,
});

var token = await cloud.GetTokenAsync();          // cached and auto-refreshed
var devices = await cloud.GetDevicesAsync();
var device = await cloud.GetDeviceAsync(devices[0].Id);   // includes LocalKey
var status = await cloud.GetStatusAsync(devices[0].Id);

await cloud.SendCommandsAsync(devices[0].Id, new[]
{
    new TuyaCloudCommand("switch_1", true),
});
```

See `docs/04-cloud-api.md` for signing details and the generic
`RequestAsync<T>` escape hatch.

## Test project

`dotnet test` runs 20 known-answer tests: frame packing against fixed vectors,
GCM and HMAC rejection of tampered frames, session-key derivation for both 3.4
and 3.5, CRC-32, incremental frame parsing, and the published Tuya cloud
signature example. The expected bytes come from `tools/reference_impl.py`, not
from this library.
