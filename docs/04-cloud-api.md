# Tuya Cloud API

Two API families share one signature scheme with one difference, and getting that
difference wrong is the usual cause of `sign invalid`.

## 1. Endpoints

| Region | Base URL |
|---|---|
| China | `https://openapi.tuyacn.com` |
| Western America | `https://openapi.tuyaus.com` |
| Eastern America | `https://openapi-ueaz.tuyaus.com` |
| Central Europe | `https://openapi.tuyaeu.com` |
| Western Europe | `https://openapi-weaz.tuyaeu.com` |
| India | `https://openapi.tuyain.com` |
| Singapore | `https://openapi-sg.iotbing.com` |

The endpoint must match the region the Tuya IoT project was created in, not the
region the devices are in. A mismatch fails at the token request.

## 2. Signing

```
stringToSign = HTTPMethod + "\n"
             + SHA256hex(body) + "\n"
             + optionalSignatureHeaders + "\n"
             + URL
```

* `SHA256hex(body)` of an empty body is
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
* `optionalSignatureHeaders` is `key:value\n` per header listed in
  `Signature-Headers`. When you use none, the section is empty — the trailing
  newline still has to be there, which is what leaves a blank line before the URL.
* `URL` is the path plus query string with parameters sorted by key ascending.
  No `?` when there are no parameters.

Then:

```
token APIs     : sign = HMAC-SHA256(client_id + t + nonce + stringToSign, secret)
business APIs  : sign = HMAC-SHA256(client_id + access_token + t + nonce + stringToSign, secret)
```

Hex-encode and upper-case the digest. `t` is a 13-digit millisecond timestamp,
`nonce` a UUID.

Both implementations are checked against Tuya's own published example — see
`docs/05-test-vectors.md`. If that vector passes, the signing code is right.

## 3. Required headers

| Header | Value |
|---|---|
| `client_id` | project access id |
| `sign` | computed above |
| `sign_method` | `HMAC-SHA256` |
| `t` | 13-digit timestamp |
| `access_token` | token APIs: omit. Business APIs: required |
| `nonce` | UUID (optional in the legacy scheme) |
| `lang` | `en` outside mainland China |

## 4. Token flow

```
GET /v1.0/token?grant_type=1                -> {access_token, expire_time, refresh_token, uid}
GET /v1.0/token/{refresh_token}             -> same shape
```

`expire_time` is a lifetime in seconds (typically 7200), not a timestamp. Both
clients here treat it as such, keep a minute of headroom, refresh with the
refresh token when close to expiry, and re-authenticate once on a token error.

## 5. Endpoints used by this SDK

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1.0/token` | fetch a token |
| GET | `/v1.0/token/{refresh_token}` | refresh a token |
| GET | `/v1.0/devices` | all devices on the account |
| GET | `/v1.0/users/{uid}/devices` | devices of one app user |
| GET | `/v1.0/devices/{id}` | one device, **including `local_key`** |
| GET | `/v1.0/devices/{id}/status` | current data points |
| POST | `/v1.0/devices/{id}/commands` | send instructions |
| GET | `/v1.0/iot-03/devices/{id}/specification` | functions and status set |

The response envelope is `{success, t, code, msg, result}`. `result` carries the
payload and is absent on failure.

Control body:

```json
{"commands":[{"code":"switch_1","value":true}]}
```

Status body:

```json
{"result":[{"code":"switch_1","value":true},{"code":"cur_power","value":0}]}
```

## 6. Getting a local key

`GET /v1.0/devices/{id}` returns `local_key`, which is what local control needs.
It is only present when the cloud project has the **Device Status Notification**
and device-authorisation scope for that device; without it the field is empty.
The link between the project and the user's devices is created by scanning the
app-account QR code in the Tuya IoT console.

## 7. Error handling

| Code | Meaning | What the SDK does |
|---|---|---|
| `1010` | token invalid | refreshes and retries once |
| `1011` | token expired | refreshes and retries once |
| `1012` | token not exist | refreshes and retries once |
| `1106` | permission denied | throws `TuyaCloudException` |
| `2009` / `sign invalid` | wrong signature | throws with the raw body attached |

`TuyaCloudException` carries `Code`, `HttpStatus` and `Body`, so a signature
failure can be diagnosed from the response body without enabling logging.

## 8. Escape hatch

Both clients expose a generic request for endpoints this SDK does not model:

```csharp
var result = await cloud.RequestAsync<JsonNode>(
    HttpMethod.Get, "/v1.0/devices", new Dictionary<string, string?> { ["page_no"] = "1" });
```

```kotlin
val response = cloud.request("GET", "/v1.0/devices", mapOf("page_no" to "1"))
val result = response.optJSONObject("result")
```

## 9. Notes

* Cloud control is slower and less reliable than local control and is subject to
  per-project rate limits. Use the cloud to fetch keys and device lists, then talk
  to devices over the LAN.
* The cloud API is for a *project*, not a personal account. Devices must be linked
  to the project through the app-account QR flow before they appear.
* Tuya's cloud SDKs and Postman collection implement the same algorithm; if a
  signature is rejected, compare the `stringToSign` byte for byte against
  `docs/05-test-vectors.md`.
