# Device console — test a real plug, and integrate against it

`tools/device_console.py` is one file, no build step, one dependency
(`cryptography`, already in `tools/requirements.txt`). It does two jobs:

1. a **web page** at `/` where you type the host, device id and local key,
   connect, see the data points and press On / Off;
2. a **JSON REST API** under `/api` that performs the same operations, so the
   same running process can be called by a script, an app, Home Assistant or
   anything else that speaks HTTP.

It speaks protocol 3.3, 3.4 and 3.5 over TCP 6668 using the frame primitives in
`tools/reference_impl.py` — the same code the SDK's test vectors are generated
from. No Tuya SDK, no cloud account, no app.

## Running it

The shortest path, no browser:

```bash
pip install -r tools/requirements.txt     # cryptography, once

python3 tools/device_console.py --try --host 192.168.1.42 \
    --device-id eb0123456789abcdefghij --key 0123456789abcdef
```

If the plug accepts the command but the data point does not move, `--try` says
so, prints what the plug answered, and then flips every other on/off data point
in turn to find the one that actually switches it — so a wrong data point is
answered in the same run, not another round.

It connects, lists the data points, tries to switch DP 1 on, and prints a
verdict in plain words — including the exact shape that worked, if one did. It
searches every control shape (command × version header × payload envelope)
before giving up, so one command is usually enough. `--off` switches off
instead, `--dp 41` targets another data point.

Once it names a shape, pin it for an instant re-test:

```bash
python3 tools/device_console.py --try --host 192.168.1.42 \
    --device-id eb0123456789abcdefghij --key 0123456789abcdef \
    --style standard --header without --command 0x0D
```

Or use the page:

```bash
python3 tools/device_console.py           # http://127.0.0.1:8080
```

| flag | meaning |
|---|---|
| `--listen 0.0.0.0` | reachable from other machines on your LAN (default `127.0.0.1`) |
| `--port 8080` | HTTP port |
| `--api-key KEY` | require `X-API-Key: KEY` on every `/api` call (or set `TUYA_CONSOLE_API_KEY`) |
| `--demo` | also start a fake 3.5 device on `127.0.0.1:16668` so you can click through without hardware |
| `--selftest` | run the end-to-end check against the fake device and exit |
| `--try` | connect to one plug, switch it, print the verdict, exit (with `--host`, `--device-id`, `--key`) |
| `--quiet` | no per-request logging |

If the page keeps showing an old version number, an earlier copy of the console
is still running. On Windows a second process can bind a port that is already
listening, so the browser talks to the old one; this version refuses that bind
and exits with "port already in use".

## The four mandatory inputs

| field | where it comes from |
|---|---|
| `host` | the plug's IP on your LAN. UDP discovery on port 6667 gives this, or your router's client list |
| `device_id` | 20-odd character id, from discovery or the app |
| `local_key` | **16 characters**, from the Smart Life / Tuya app's device info screen, or `GET /v1.0/devices/{id}` through the cloud API. A 32-digit hex string is accepted too |
| `version` | `3.5` for current firmware, `3.3` / `3.4` for older plugs. Get it right: the wrong version fails at the handshake and looks exactly like a wrong key |

Optional: `port` (default 6668), `cid` (gateway sub-device), `command_set`
(the **query** command: `legacy` = `0x0A`, `new` = `0x10`), `control` (the
**control** command: `auto` = try `0x0D` then `0x07`, or pin one), `timeout`
(seconds), `ack_timeout` (how long to wait for an acknowledgement, default 1.6),
`settle` (how long to wait after a control before judging the state, default 0.45).

`auto` is the right default: 3.4/3.5 firmware commonly implements only
CONTROL_NEW (`0x0D`) and silently ignores the legacy CONTROL (`0x07`), while
older firmware is the other way round. With `auto` the console sends `0x0D`,
reads the state back, and only falls through to `0x07` if the device neither
acknowledged nor changed — so a working device is never sent two commands.

Two more knobs on the control frame, for firmware that is picky about the body:

* `header` (default `true`) — whether to put the 15-byte `3.5` version header in
  front of the JSON body. A few firmwares parse the payload strictly and drop a
  frame that starts with it.
* `payload_style` — which envelope the data points travel in:
  `modern5` / `modern4` (`{"protocol": 5, "t": …, "data": {"dps": {…}}}`, what
  3.4 and 3.5 firmware expects), `standard` / `gwid` / `both` (the legacy
  `{devId, uid, t, dps}` shape), or `auto` (try modern first, then legacy).

  This is the single most common reason a 3.5 plug ignores control: send it the
  legacy envelope and it finds no `data.dps`, so it drops the frame with no
  acknowledgement and no error. `auto` handles it without you thinking about it.

## Endpoints

All responses are JSON. Success is `{"ok": true, ...}`; failure is
`{"ok": false, "error": {"type": "...", "message": "..."}}` with a matching HTTP
status (400 bad input, 401 bad API key, 404 unknown device, 409 not connected,
502 device/protocol error, 504 timeout).

| method | path | body | does |
|---|---|---|---|
| GET | `/` | — | the web page |
| GET | `/api/health` | — | liveness; needs no API key |
| POST | `/api/connect` | `{host, device_id, local_key, version, ...}` | connects, negotiates the session key, returns the data points |
| GET | `/api/devices` | — | every connected device |
| GET | `/api/devices/{id}` | — | one device's connection details |
| GET | `/api/devices/{id}/status` | — | fresh `DP_QUERY`, returns `dps` |
| POST | `/api/devices/{id}/switch` | `{on: true, dp: "1", control?: "auto"}` | sets the switch and reads the state back |
| POST | `/api/devices/{id}/probe` | `{on: true, dp: "1"}` | tries every control shape (command × header × payload) and reports which one the device reacts to |
| POST | `/api/devices/{id}/dp` | `{dp: "2", value: 300}` | sets any data point (brightness, mode, colour) |
| POST | `/api/devices/{id}/command` | `{command: 9, payload: ""}` | raw command, for anything not modelled |
| POST | `/api/devices/{id}/disconnect` | — | closes and forgets the device |
| DELETE | `/api/devices/{id}` | — | same, as DELETE |
| POST | `/api/disconnect-all` | — | closes everything |

## Calling it

```bash
# connect
curl -s localhost:8080/api/connect -H 'Content-Type: application/json' -d '{
  "host": "192.168.1.42",
  "device_id": "eb0123456789abcdefghij",
  "local_key": "0123456789abcdef",
  "version": "3.5"
}'
```

```json
{
  "ok": true,
  "device": {"device_id": "eb0123456789abcdefghij", "host": "192.168.1.42",
             "version": "3.5", "session_key": true, "connected": true},
  "dps": {"1": false, "2": 500, "3": "white", "20": true},
  "command": "0x10"
}
```

```bash
# on, then off
curl -s localhost:8080/api/devices/eb0123456789abcdefghij/switch \
  -H 'Content-Type: application/json' -d '{"on": true}'

curl -s localhost:8080/api/devices/eb0123456789abcdefghij/switch \
  -H 'Content-Type: application/json' -d '{"on": false, "dp": "1"}'
```

```json
{
  "ok": true,
  "acknowledged": true,
  "sent": {"1": true},
  "status": {"dps": {"1": true, "2": 500}, "source": "read-back query"},
  "confirmed": true,
  "control_command": "0x0D",
  "attempts": [
    {"command": "0x0D", "acknowledged": true, "reply": "0x07",
     "state_after": {"1": true, "2": 500}, "state_changed": true}
  ],
  "events": [{"after_s": 0.08, "command": "0x08", "dps": {"1": true}}],
  "pulsed": false
}
```

`confirmed` is the answer to "did it actually switch": the console does not
trust the acknowledgement. Many devices acknowledge a control frame with an
empty reply and then push a `STATUS` frame; some acknowledge nothing at all. So
the console waits `settle` seconds, reads the state back, and compares the data
point with the value it sent. `acknowledged: false` with `confirmed: true` is
normal and means the device applied the change silently.

The three fields that diagnose a switch that will not work:

* `attempts` — every control command tried, whether it was acknowledged, and the
  state afterwards. If both `0x0D` and `0x07` show `acknowledged: false` and no
  change, the frame is being decrypted and ignored: the DP id or the payload
  shape is wrong, not the transport.
* `events` — every frame the device sent after the command, with the data points
  it carried and how many seconds after the send. This is the record that
  distinguishes "ignored" from "acted and reverted".
* `received` — every frame the socket produced after the command, decoded or
  not, with its command byte and size. Empty `received` means the device said
  nothing at all; a frame with `ok: false` means it answered in a shape we could
  not parse (the `errors` field carries the reason). This is the field that
  separates "the device ignored us" from "we failed to read its answer".
* `pulsed: true` — the device reported the requested state and then left it
  again. That is a momentary / inching relay (or a toggle DP), a device setting,
  not a protocol failure. On Tuya relays the mode is usually a DP of its own —
  look for an enum data point in the `/status` list (values like `"relay"` or
  `"memory"`) and change it before concluding anything about the switch.

## When the device ignores control

Queries work, control draws no response at all. In order of likelihood:

1. **The version header.** `CONTROL` frames carry a 15-byte `3.5` header before
   the JSON body. At least one real 3.5 plug silently drops any control frame
   that has it, and accepts the identical frame without it — queries unaffected,
   so it looks like a dead control path. This was the actual cause on the device
   used to develop this tool.
2. **Wrong envelope.** 3.4/3.5 devices expect
   `{"protocol": 5, "t": …, "data": {"dps": {…}}}`. The legacy
   `{devId, uid, t, dps}` shape is silently dropped — no ack, no error, no
   disconnect. `auto` tries the modern one first.
3. **Wrong body.** The plug answers `data format error` (or `json obj data
   unvalid`) inside a normal frame. That is a rejection, not an acknowledgement —
   the console treats it as one and keeps searching. This was the second real
   finding on the plug used to develop this tool.
4. **Wrong control command** — `0x0D` vs `0x07`.
5. **Wrong data point**, or a read-only one.
6. **The device only accepts control from the app** (cloud, or a gateway `cid`).

A plain `Turn ON` now searches 1-3 automatically before reporting a failure, and
`/probe` reports the search in detail:

```bash
curl -s localhost:8080/api/devices/d7dbceed25a572e0c3fppy/probe \
  -H 'Content-Type: application/json' -d '{"on": true, "dp": "1"}'
```

It sends up to twenty shapes — `0x0D` and `0x07`, with and without the version
header, in five payload styles — and stops at the first one the device
acknowledges or reacts to. `winner` names it, and the console remembers it, so
every later switch is sent the right way the first time.

| probe result | meaning |
|---|---|
| `winner` set | that combination is the one this firmware wants; pin it with `control` / `header` / `payload_style` |
| `winner: null`, `received` empty | the device decrypted and dropped every frame. The transport is fine — the data point, the body, or the device's own policy is the problem |
| `winner: null`, `received` shows `ok: false` | the device *is* answering in a shape we cannot parse; `errors` says why |
| `winner: null` on DP 1, but DP 41 reacts | DP 1 is read-only on this model; the writable one is elsewhere |

If the probe finds nothing, the next question is whether the device accepts LAN
control from anything other than the app. `tinytuya` answers that in three
lines, and its debug output gives you the exact bytes for comparison:

```python
import tinytuya
tinytuya.set_debug(True)
d = tinytuya.Device('device_id', '192.168.1.17', 'local_key', version=3.5)
print(d.status())
print(d.turn_on())
```

If that also fails, the plug is not refusing our encoding — it wants something
this protocol path cannot give it (a gateway `cid`, or cloud-only control).

## Using it from your own code

The switch call is the whole integration surface:

```python
import requests

def set_plug(on: bool):
    r = requests.post("http://127.0.0.1:8080/api/devices/eb0123456789abcdefghij/switch",
                      json={"on": on}, timeout=15)
    body = r.json()
    if not body.get("ok") or not body.get("confirmed"):
        raise RuntimeError("plug did not confirm: %s" % body)
```

Notes for real use:

* **The console holds the connection.** Tuya devices accept very few concurrent
  TCP clients (often one). If the Tuya app or another service is connected to the
  plug, connect fails or drops. That is the device, not this code.
* **One request in flight per device** is enforced with a lock, because the
  devices serialise on their side.
* **Keep it on your LAN.** Credentials arrive over plain HTTP. Bind
  `127.0.0.1` (the default), or `--api-key` plus a reverse proxy with TLS if you
  need it from elsewhere. Never expose it to the internet as-is.
* The local key is never logged and never returned in a response.

## When it does not work

| symptom | likely cause |
|---|---|
| `device closed the connection during the handshake` | wrong local key, or wrong protocol version. Try 3.3, then 3.4 |
| `handshake reply could not be decrypted with this local key` | wrong key |
| `the device rejected the local key (nonce HMAC mismatch)` | the device answered but holds a different key — key from another device |
| connects, but `dps` is empty | try `command_set: "new"`; some firmware wants `0x10` instead of `0x0A` |
| `no reply to command 0x0A` | device busy with another client, or blocked on your LAN (AP isolation) |
| `confirmed: false` but `acknowledged: true` | the frame is right and the data point is wrong. `--try` sweeps the other on/off data points automatically |
| `confirmed: false`, both attempts unacknowledged | the firmware wants a different command or payload; `--try` searches those |
| `confirmed: false`, `pulsed: true` | momentary / inching relay, or a toggle DP. Change the mode data point (often an enum showing `"relay"`) |
| switch works but the value flips straight back | same as above, or the plug is following a schedule / countdown DP |
| `could not connect to host:6668` | wrong IP, or the device is on another VLAN / guest network |

## What has been verified, and what has not

`python3 tools/device_console.py --selftest` runs 43 checks: the 3.3 and 3.4
framing (pack, decode, return-code handling, version-header strip, tamper
rejection) locally, and the full 3.5 session against `tools/fake_device.py` —
handshake, status, on, off, read-back confirmation, raw command, a wrong key
being rejected without leaving a socket open, a firmware that ignores `0x0D`
being driven through the `0x07` fallback, a firmware that ignores `0x0D` being
found by `/probe`, control frames with and without the version header round
tripping on all three protocol versions, and a simulated momentary relay being
reported as `pulsed` rather than as a failure. It also covers the envelope bug
above: a simulated plug that accepts only the modern envelope switches on the
first attempt, and the second switch reuses the shape that worked. All pass.

That is a simulator. It does not prove compatibility with a particular plug or
firmware revision. The first thing to do with real hardware is `/api/connect`
and check that `dps` lists the data points you expect; then `/switch`.
