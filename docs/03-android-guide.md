# Android / Kotlin guide

Library module: `kotlin/tuya-lan` (namespace `com.tuyalan`, `minSdk` 24).
Dependencies: `kotlinx-coroutines-core` and `okhttp` for the cloud client; JSON is
handled with the `org.json` that ships with Android, so there is no serialisation
dependency.

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── tuya-lan/          the library
│   └── src/main/kotlin/com/tuyalan/
│       ├── protocol/  frame codec, crypto, session, command ids
│       ├── devices/   TuyaLocalDevice, TuyaDiscovery, TuyaStatus, TuyaDps
│       ├── cloud/     TuyaCloudClient, signer, models
│       └── TuyaException.kt
└── sample-app/        discover, connect, read status, toggle a switch
```

```bash
cd kotlin
./gradlew :tuya-lan:test          # JVM unit tests (known-answer vectors)
./gradlew :sample-app:assembleDebug
```

Add the module to an existing app:

```kotlin
// settings.gradle.kts
include(":tuya-lan")
// app/build.gradle.kts
implementation(project(":tuya-lan"))
```

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are declared by the
library manifest and merged into your app automatically. Local control talks to
the device's IP directly on TCP 6668, so **no location permission is needed** —
but the phone must be on the same subnet, and many Android builds drop UDP
broadcasts when a VPN is active.

## Local control

```kotlin
val device = TuyaLocalDevice(
    TuyaDeviceOptions(
        host = "192.168.1.42",
        deviceId = "eb0123456789abcdefghij",
        localKey = "0123456789abcdef",          // exactly 16 characters
        version = TuyaProtocolVersion.V35,      // take this from discovery
    ),
)

// Status pushes, solicited or not:
lifecycleScope.launch {
    device.statuses.collect { status ->
        Log.d("Tuya", "dps: ${status.dps}")
    }
}

lifecycleScope.launch {
    device.connect()                            // performs the session-key handshake
    val status = device.getStatus()
    device.setDp("1", !(status.getBool("1") ?: false))

    device.setDps(
        TuyaDps()
            .set("1", true)
            .set("2", 500)
            .setEnum("3", "white"),
    )
}

override fun onDestroy() {
    device.close()                              // non-suspending shutdown
    super.onDestroy()
}
```

All I/O functions are `suspend` and switch to `Dispatchers.IO` internally, so
they are safe to call from `lifecycleScope` / `viewModelScope` directly.

### Reading the status

`TuyaStatus` handles `{"dps":…}`, `{"data":{"dps":…}}` and a double-encoded
`data` string. Values keep the types `org.json` produces (`Boolean`, `Int`,
`Long`, `Double`, `String`, `JSONObject`), with typed accessors:

```kotlin
status.getBool("1")     // Boolean?
status.getInt("2")      // Int?
status.getDouble("2")   // Double?
status.getString("3")   // String?
status["24"]            // Any?
```

### Errors

| Exception | Meaning |
|---|---|
| `TuyaHandshakeException` | wrong local key, or the device is a different protocol version |
| `TuyaCryptoException` | GCM tag / HMAC / CRC-32 verification failed |
| `TuyaTimeoutException` | no reply within `commandTimeoutMillis` |
| `TuyaProtocolException` | malformed frame or a desynchronised stream |

Non-fatal decode failures are emitted on `protocolErrors` instead of killing the
connection. A dropped socket surfaces as `TuyaProtocolException("connection
lost…")` on the same flow.

## Discovery

```kotlin
lifecycleScope.launch {
    val devices = TuyaDiscovery.scan(durationMillis = 6_000)
    devices.forEach { Log.d("Tuya", "$it") }
}
```

`scan` binds UDP 6666, 6667 and 7000, decrypts all three announcement formats and
sends the 3.5 solicitation broadcast. Ports already in use by another app are
skipped; if none can be bound it throws `TuyaException`.

Discovery yields the IP, device id and protocol version — **not** the local key.
Fetch the key with the cloud client or from the Tuya app.

## Cloud API

```kotlin
val cloud = TuyaCloudClient(
    TuyaCloudOptions(
        clientId = "your-access-id",
        secret = "your-access-secret",
        region = TuyaCloudRegion.INDIA,
    ),
)

lifecycleScope.launch {
    val devices = cloud.getDevices()
    val device = cloud.getDevice(devices.first().id)      // includes localKey
    val status = cloud.getStatus(devices.first().id)
    cloud.sendCommands(devices.first().id, listOf(TuyaCloudCommand("switch_1", true)))
}
```

The token is cached, refreshed with the refresh token near expiry, and a token
error triggers one automatic re-authenticated retry.

## ProGuard / R8

`consumer-rules.pro` ships with the module and keeps `com.tuyalan.**`. If you
minify the sample app, nothing extra is needed.

## Unit tests

`./gradlew :tuya-lan:test` runs the same known-answer vectors as the C# suite:
frame packing, GCM/HMAC tamper rejection, session-key derivation for 3.4 and 3.5,
CRC-32, incremental frame parsing, DPS parsing and the published Tuya cloud
signature example. `org.json` is added as a test dependency because the real one
only exists inside the Android framework.
