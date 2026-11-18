# Test vectors

Every value on this page is produced by `tools/reference_impl.py --emit-vectors`,
an independent Python implementation of the protocol, and asserted by both the
C# suite (`dotnet/tests/TuyaLan.Tests/VectorTests.cs`) and the Kotlin suite
(`android/tuya-lan/src/test/kotlin/com/tuyalan/ProtocolVectorTest.kt`). If a port
disagrees with this page, the port is wrong.

## Shared inputs

| Input | Value |
|---|---|
| Local key | `0123456789abcdef` (`30313233343536373839616263646566`) |
| Fixed GCM nonce | `0123456789ab` (`303132333435363738396162`) |
| Discovery key | `MD5("yGAdlopoPVldABfn")` = `6c1ec8e2bb9bb59ab50b0daf649b410a` |
| Payload | `{"devId":"eb0123456789abcdefghij","t":1700000000,"dps":{"1":true}}` |
| Payload hex | `7b226465764964223a226562303132333435363738396162636465666768696a222c2274223a313730303030303030302c22647073223a7b2231223a747275657d7d` |

Payload is 66 bytes, so a 3.5 frame carries 66 bytes of ciphertext.

## CRC-32

CRC-32 (IEEE, zlib polynomial) of the payload above:

```
expected = 2524178384  (0x9673E7D0)
```

## 3.5 frame — CONTROL, sequence 7, no return code

```
00006699000000000007000000070000005e3031323334353637383961621960699e582949ab
39211286b4eb91c0a2bd405f5282246107fb141610fab2e4d9d58c73a1b6b8ef301af890e141
a4b900938a107efb4eb841d994321b66c2f0478cd3b3916df1ada9d7c36cc7aeb1e64c210000
9966
```

Decodes to: prefix `0x00006699`, reserved 0, sequence 7, command `0x07`,
length `0x5e` = 94, payload = the JSON above, no return code.

## 3.5 frame — STATUS, sequence 8, return code 0

Same payload, but with a 4-byte return code inside the ciphertext. The
length grows to `0x62` = 98 = 12 + (4 + 66) + 16.

```
00006699000000000008000000080000006230313233343536373839616262420dfb554249ec
754a13c6bef8c691a6b9445b5e8e73345ca6101214f6bee89c91c76da1a0abac2210f997e141
a4b91c81de5021fb10b310ca8c734d2595af4e8394a77454c43d5b3fbe57e279a439fcf618a5
4ebc00009966
```

## 3.4 frame — CONTROL, sequence 3

`0x000055AA` frame, AES-128-ECB with the local key, 15-byte `"3.4"` version
header inside the ciphertext, HMAC-SHA256 trailer (32 bytes) plus footer.

```
000055aa0000000300000007000000844490b05d74be9368c24a038cbaeded8e93cdaa9ddbe7
59a2de1dd3364730e6e7b02174601734b32067a7df69e7fdae03b2068860fe6deff0bde9fdfc
2e98273941fb8ef70a70a27b93a7e74ffe75426303000aeb4ff51d29188f82e4a162a1f627cd
bca6f71f92a1bf0a6b965b8f203913db4fc95b759309b969660796980fd20000aa55
```

## 3.3 frame — DP_QUERY, sequence 4

`0x000055AA` frame, AES-128-ECB with the local key, CRC-32 trailer. DP_QUERY
carries no version header.

```
000055aa000000040000000a00000058f1a712fc1110fda4489c54a53046067e0a426a5d54a4
82ce0e3fbd3bdbec46141f2a18cc7295041d10eba653e1ff2e47f09a264f41ed2ed2962f8ff1
1444d4dd64215f365b0dad6b2ac8fab15b1d40bdeb39c6c20000aa55
```

## Session key derivation

Client nonce `30313233343536373839616263646566`, device nonce
`66656463626139383736353433323130`, local key `30313233343536373839616263646566`:

| Version | Derivation | Session key |
|---|---|---|
| 3.5 | GCM-encrypt `xor(nonces)` with the local key, IV = client nonce[0..12], keep the ciphertext | `34165bab783422860c0c23b2d48cf7a5` |
| 3.4 | AES-ECB-encrypt `xor(nonces)` with the local key | `6575cf6b37479d9215337ff9767fe786` |

The two keys differ for the same nonces, which is the trap that makes a 3.5
device fail when the client assumes the 3.4 derivation.

## Cloud API signature

The worked example from Tuya's "Sign Requests for Cloud Authorization":

| Field | Value |
|---|---|
| client_id | `1KAD46OrT9HafiKdsXeg` |
| secret | `4OHBOnWOqaEC1mWXOpVL3yV50s0qGSRC` |
| t | `1588925778000` |
| nonce | `5138cc3a9033d69856923fd07b491173` |
| URL | `/v1.0/token?grant_type=1` |

`stringToSign`:

```
GET
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
area_id:29a33e8796834b1efa6
call_id:8afdb70ab2ed11eb85290242ac130003

/v1.0/token?grant_type=1
```

```
sign = 9E48A3E93B302EEECC803C7241985D0A34EB944F40FB573C7B5C2A82158AF13E
```

Note the blank line before the URL: the optional signature-header section is
empty here, but its trailing newline still has to be present.
