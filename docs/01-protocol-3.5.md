# Tuya LAN protocol 3.5 — wire format reference

This is the specification this SDK implements, written from the public
reverse-engineering record rather than from any Tuya SDK. Everything here is
checkable against `tools/reference_impl.py`, which is an independent
implementation of the same rules and produces the vectors in
`docs/05-test-vectors.md`.

Sources: the TinyTuya `PROTOCOL.md` and its `message_helper` / `header` /
`command_types` modules, the `localtuya` and `tuya-local` Home Assistant
integrations, and the protocol notes in the tinytuya discussion thread
"Protocol notes #260".

## 1. Versions at a glance

| Version | Handshake | Frame | Payload crypto | Integrity | Version header |
|---|---|---|---|---|---|
| 3.3 | none | `0x000055AA` | AES-128-ECB, static local key | CRC-32 | 15 bytes |
| 3.4 | 3-message session key | `0x000055AA` | AES-128-ECB, session key | HMAC-SHA256 | 15 bytes, encrypted |
| **3.5** | **3-message session key** | **`0x00006699`** | **AES-128-GCM, session key** | **GCM tag** | **15 bytes, encrypted** |

3.5 is not backward compatible with 3.4. A 3.5 device will fail a 3.3/3.4
handshake and the failure looks like a bad local key, so always take the version
from the discovery broadcast instead of assuming.

Ports: TCP **6668** for control, UDP **6667** for encrypted announcements, UDP
**7000** for app-style discovery and 3.5 solicitation, UDP **6666** for plaintext
3.1 announcements.

## 2. The 3.5 frame

```
 offset  size  field
 ------  ----  -------------------------------------------------------------
   0      4    prefix        00 00 66 99
   4      2    reserved      00 00 (ignored on receive)
   6      4    sequence      uint32 big-endian
  10      4    command       uint32 big-endian
  14      4    length        uint32 big-endian
  18     12    iv / nonce    unique per packet
  30      n    ciphertext    payload, unpadded
 30+n    16    tag           AES-GCM authentication tag
 46+n     4    footer        00 00 99 66
```

* `length` counts from the first IV byte through the last tag byte and
  **excludes** the footer, so `length = 12 + n + 16`.
* Total frame size is `18 + length + 4`.
* The associated data authenticated (but not encrypted) by GCM is the 14 bytes
  after the prefix: `reserved(2) || sequence(4) || command(4) || length(4)`.
* The payload is not padded — GCM is a stream mode.
* All integers are big-endian.

### Return code

In a device-to-client data frame the first four decrypted bytes are a big-endian
return code (0 = success) followed by the JSON body. Client-to-device frames do
not carry one, and some firmware omits it in replies too, so this library
auto-detects: if the body already starts with `{` or `[` (or with a `3.x` version
header) no return code is stripped; otherwise the first four bytes are taken as
the return code. `RetcodePolicy.Always` / `.Never` override the guess.

## 3. Crypto

* Key: 16 bytes, the "local key" from the Tuya app or the cloud API.
* AES-128-GCM, 12-byte nonce, 16-byte tag.
* Every frame after the handshake uses a per-connection **session key**, not the
  static local key.

### Session key handshake

Three messages, all framed as ordinary 3.5 frames but encrypted with the
**static local key**:

1. Client → device, command `0x03` (SESS_KEY_NEG_START), payload = 16 random bytes
   (the client nonce).
2. Device → client, command `0x04` (SESS_KEY_NEG_RESP), payload =
   `device_nonce(16) || HMAC-SHA256(local_key, client_nonce)(32)`.
3. Client verifies the HMAC, then sends command `0x05` (SESS_KEY_NEG_FINISH),
   payload = `HMAC-SHA256(local_key, device_nonce)`.

Both sides then derive the same session key:

```
mixed       = client_nonce XOR device_nonce              (16 bytes)
session_key = AES-128-GCM-Encrypt(key = local_key,
                                  iv  = client_nonce[0..12],
                                  plaintext = mixed).ciphertext   (16 bytes)
```

That is: encrypt the XOR with GCM using the first 12 bytes of the client nonce as
the nonce, and keep the 16-byte ciphertext block (bytes 12..28 of the
`iv || ciphertext || tag` blob). 3.4 uses the same XOR but derives the key with
AES-128-**ECB** instead, which yields a different key from the same nonces.

Handshake frames carry no version header (see below).

## 4. The 15-byte version header

Most payloads are prefixed with 15 bytes: the ASCII version (`"3.5"`) followed by
12 zero bytes. It is **inside** the encryption on 3.4 and 3.5, so it is only
visible after decryption.

It is **not** used for these commands:

| Command | Value | Carries version header |
|---|---|---|
| SESS_KEY_NEG_START | `0x03` | no |
| SESS_KEY_NEG_RESP | `0x04` | no |
| SESS_KEY_NEG_FINISH | `0x05` | no |
| HEART_BEAT | `0x09` | no |
| DP_QUERY | `0x0A` | no |
| DP_QUERY_NEW | `0x10` | no |
| UPDATEDPS | `0x12` | no |
| LAN_EXT_STREAM | `0x40` | no |
| CONTROL / STATUS / everything else | `0x07`, `0x08`, … | **yes** |

Older 3.3 firmware has been observed with the header prepended in the clear
instead of encrypted. That is why `VersionHeaderMode` exists; 3.5 has no such
ambiguity.

## 5. Commands

| Name | Value | Direction | Notes |
|---|---|---|---|
| CONTROL | `0x07` | client → device | set data points |
| STATUS | `0x08` | device → client | unsolicited DP report |
| HEART_BEAT | `0x09` | both | keepalive |
| DP_QUERY | `0x0A` | client → device | read data points |
| CONTROL_NEW | `0x0D` | client → device | "device22" variant of CONTROL |
| DP_QUERY_NEW | `0x10` | both | "device22" query; also a common status reply |
| UPDATEDPS | `0x12` | client → device | ask the device to refresh |
| UDP_NEW | `0x13` | broadcast | encrypted discovery on UDP 6667 |
| REQ_DEVINFO | `0x25` | client → broadcast | 3.5 solicitation on UDP 7000 |
| LAN_EXT_STREAM | `0x40` | both | gateway / sub-device operations |

"device22" devices are older units whose `gwId` is 22 characters; they want
`0x0D` and `0x10` instead of `0x07` and `0x0A`. `TuyaDeviceType.Device22` selects
those.

## 6. Data point payloads

Query (no version header):

```json
{"gwId":"eb0123456789abcdefghij","devId":"eb0123456789abcdefghij","uid":"eb0123456789abcdefghij","t":1700000000}
```

Control (version header prefixed before encryption):

```json
{"devId":"eb0123456789abcdefghij","uid":"eb0123456789abcdefghij","t":1700000000,"dps":{"1":true}}
```

A status reply is either a bare `{"dps":{...}}` or a wrapper
`{"devId":"...","t":...,"data":{"dps":{...}}}`; some gateways double-encode the
wrapper as a JSON string. Sub-devices behind a gateway add `cid`.

## 7. Discovery

Announcement payload (JSON, before encryption):

```json
{"ip":"192.168.1.42","gwId":"eb0123456789abcdefghij","active":2,"ablilty":0,
 "encrypt":true,"productKey":"keydeadbeef12345","version":"3.5","token":true,"wf_cfg":true}
```

The `ablilty` misspelling is what the firmware actually sends.

* 3.1: plaintext JSON broadcast on UDP 6666.
* 3.3+: the JSON encrypted with AES-128-ECB under the public discovery key, on
  UDP 6667.
* 3.5: the same JSON inside a normal `0x00006699` GCM frame, keyed with the
  discovery key, on UDP 6667.

The discovery key is public by design — it protects the advertisement, never
control:

```
discovery_key = MD5("yGAdlopoPVldABfn") = 6c1ec8e2bb9bb59ab50b0daf649b410a
```

Newer 3.5 firmware does not broadcast at all. Instead the client sends

```json
{"from":"app","ip":"<client ip>"}
```

as a GCM frame under the discovery key to the subnet broadcast address on **UDP
7000**, and the device replies directly to the client on port 7000.

## 8. Worked example

Local key `"0123456789abcdef"`, nonce `"0123456789ab"`, command CONTROL, sequence
7, payload
`{"devId":"eb0123456789abcdefghij","t":1700000000,"dps":{"1":true}}` (66 bytes):

```
00006699 0000 00000007 00000007 0000005e
         ^^^^ ^^^^^^^^ ^^^^^^^^ ^^^^^^^^
         |    |        |        +-- length 94 = 12 + 66 + 16
         |    |        +----------- command 0x07 CONTROL
         |    +-------------------- sequence 7
         +------------------------- reserved
303132333435363738396162          <- 12-byte IV, ASCII "0123456789ab"
1960699e582949ab39211286b4eb91c0  <- 66-byte ciphertext
  a2bd405f5282246107fb141610fab2e4
  d9d58c73a1b6b8ef301af890e141a4b9
  00938a107efb4eb841d994321b66c2f0
  478c
d3b3916df1ada9d7c36cc7aeb1e64c21  <- 16-byte GCM tag
00009966                            <- footer
```

Concatenated, that is the 116-byte frame asserted by both test suites:

```
00006699000000000007000000070000005e3031323334353637383961621960699e582949ab3921
1286b4eb91c0a2bd405f5282246107fb141610fab2e4d9d58c73a1b6b8ef301af890e141a4b900938a
107efb4eb841d994321b66c2f0478cd3b3916df1ada9d7c36cc7aeb1e64c2100009966
```

The exact bytes, including the tag, are in `docs/05-test-vectors.md` and are
asserted by both test suites.

## 9. Reference frames for 3.3 / 3.4

Kept for context because the same library talks to those devices:

```
3.3/3.4:  000055aa | seq(4) | cmd(4) | length(4) | [retcode(4)] | ciphertext | trailer | 0000aa55
```

* `length` counts everything after the length field, including the trailer.
* Trailer is CRC-32 + footer (8 bytes) on 3.3, HMAC-SHA256 + footer (36 bytes) on
  3.4. The HMAC covers everything from the prefix up to the start of the HMAC.
* The return code, when present, sits in the clear between the length field and
  the ciphertext.
* Payloads are PKCS#7 padded AES-128-ECB.

## 10. What this document does not cover

* Zigbee sub-device specifics beyond the `cid` field.
* `LAN_EXT_STREAM` sub-protocols for gateways.
* Device provisioning (AP config / token bind).
* IR blaster and other category-specific DP encodings — those are payload-level
  conventions, not protocol-level.
