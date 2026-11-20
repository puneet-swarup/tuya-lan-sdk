#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tuya LAN device console - a local test harness and integration endpoint.

Two things in one process:

  * a web page (GET /) where you type host / device id / local key, connect,
    read the data points and press On or Off;
  * a JSON REST API under /api that does exactly the same thing, so the same
    endpoint can be called by a script, an app or a home-automation system.

It speaks 3.3, 3.4 and 3.5 over TCP 6668, reusing the frame primitives in
tools/reference_impl.py (the same code the SDK's test vectors are generated
from). No Tuya SDK, no cloud account, no app.

    python3 tools/device_console.py                 # http://127.0.0.1:8080
    python3 tools/device_console.py --demo          # with a fake device to click through
    python3 tools/device_console.py --selftest      # end-to-end check, no browser

Requires only tools/requirements.txt (cryptography). Standard library HTTP.
"""

import argparse
import collections
import hmac
import json
import os
import queue
import socket
import struct
import sys
import threading
import time
import traceback
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import reference_impl as R  # noqa: E402
except ImportError:
    sys.stderr.write(
        "device_console.py needs reference_impl.py in the same directory.\n"
        "Run it from the repository: python3 tools/device_console.py\n")
    raise SystemExit(2)

__version__ = "1.7.0"

DEFAULT_PORT = 6668
SWITCH_DP = "1"

# Commands whose replies count as "the device accepted the control frame".
CONTROL_ACKS = {R.CMD_CONTROL, R.CMD_CONTROL_NEW, R.CMD_STATUS, R.CMD_DP_QUERY_NEW, R.CMD_DP_QUERY}
STATUS_REPLIES = {R.CMD_STATUS, R.CMD_DP_QUERY_NEW, R.CMD_DP_QUERY, R.CMD_CONTROL, R.CMD_CONTROL_NEW}


# --------------------------------------------------------------------------
# Errors
# --------------------------------------------------------------------------
class ConsoleError(Exception):
    """An error with an HTTP status attached."""

    status = 502

    def __init__(self, message, status=None, detail=None):
        super().__init__(message)
        self.message = message
        if status is not None:
            self.status = status
        self.detail = detail


class BadRequest(ConsoleError):
    status = 400


class DeviceTimeout(ConsoleError):
    status = 504


class HandshakeFailed(ConsoleError):
    status = 502


ERROR_WORDS = ("error", "unvalid", "invalid", "fail", "not support", "unsupport",
               "format", "illegal")


def _looks_like_error(text):
    """True when the device answered with a complaint instead of a result.

    Tuya firmware replies to a frame it cannot use with a short plain-text
    message - "data format error", "json obj data unvalid", "parse data error" -
    inside an otherwise normal frame. Treating that as success stops the search
    one shape too early, which is exactly what happened on a real plug.
    """
    if not text:
        return False
    lowered = text.strip().lower()
    if lowered.startswith("{") or lowered.startswith("["):
        return False
    return any(word in lowered for word in ERROR_WORDS)


def _same(observed, expected):
    """Compare a data point read back from the device with what was sent.

    Devices are loose about types (true/1, "500"/500), so compare the string
    form, but treat booleans strictly so that True never matches 1.
    """
    if isinstance(expected, bool):
        if isinstance(observed, bool):
            return observed is expected
        if isinstance(observed, str):
            return observed.strip().lower() == ("true" if expected else "false")
        return observed in (1, 0) and bool(observed) is expected
    if observed is None:
        return False
    if isinstance(expected, (int, float)) and not isinstance(expected, bool):
        try:
            return float(observed) == float(expected)
        except (TypeError, ValueError):
            return False
    return str(observed) == str(expected)


# --------------------------------------------------------------------------
# One connection to one device
# --------------------------------------------------------------------------
class TuyaLocalClient:
    """A minimal, blocking client for a single Tuya LAN device.

    Thread-safe: one request in flight (the devices serialise on their side),
    writes serialised separately so nothing can interleave on the wire.
    """

    def __init__(self, host, device_id, local_key, version="3.5", port=DEFAULT_PORT,
                 cid=None, command_set="legacy", control="auto", payload_style="auto",
                 trailer=None,
                 timeout=6.0, ack_timeout=1.0, settle=0.3, quiesce=0.06):
        self.host = host
        self.port = int(port)
        self.device_id = device_id
        self.version = str(version)
        self.cid = cid or None
        self.timeout = float(timeout)
        # Tuya replies carry no usable correlation id: the device runs its own
        # sequence counter and answers by command family. A status push from the
        # previous command can therefore land just after the next request starts
        # and be mistaken for its reply. Let the line go quiet before each
        # request so every reply belongs to the request that follows it.
        self.quiesce = float(quiesce)

        if self.version not in ("3.3", "3.4", "3.5"):
            raise BadRequest("version must be one of 3.3, 3.4, 3.5")
        if command_set not in ("legacy", "new"):
            raise BadRequest("command_set must be 'legacy' or 'new'")
        if control not in ("auto", "legacy", "new"):
            raise BadRequest("control must be 'auto', 'legacy' or 'new'")

        # 3.4 frames carry an HMAC trailer by default; a few firmwares kept the
        # CRC. 3.3 always uses CRC, 3.5 always uses GCM.
        self.hmac_mode = (self.version == "3.4") if trailer is None else (trailer == "hmac")

        self.query_command = R.CMD_DP_QUERY_NEW if command_set == "new" else R.CMD_DP_QUERY
        self.control_command = R.CMD_CONTROL_NEW if command_set == "new" else R.CMD_CONTROL
        self.control = control
        self.payload_style = payload_style
        self.header = True
        # How long to wait for an acknowledgement to a control frame. Devices that
        # acknowledge do it in milliseconds; waiting longer only delays the
        # read-back, which is what actually tells you whether it worked.
        self.ack_timeout = float(ack_timeout)
        # How long to wait after a control frame before judging the device's
        # state, so a push that reports the new state has time to arrive.
        self.settle = float(settle)

        key_bytes = self._parse_key(local_key)
        self._local_key = key_bytes
        self._key = key_bytes

        self._sock = None
        self._seq = 0
        self._buffer = b""
        self._reader = None
        self._stop = threading.Event()

        self._write_lock = threading.Lock()
        self._req_lock = threading.Lock()
        self._waiter = None          # (accepted_commands, queue) while a request waits
        # Messages that arrived during a request but were not its reply, e.g. the
        # status push a control frame triggers. Kept so a caller that wants the
        # push (set_dps) can find it even if it landed a microsecond early.
        self._stash = collections.deque(maxlen=8)

        # Every frame that carried data points, in arrival order. A momentary
        # relay shows up here as true-then-false even though the final state is
        # false, which is the difference between "ignored" and "pulsed".
        self.events = collections.deque(maxlen=64)
        # Every frame the socket produced, decoded or not. If the device answers
        # with something we cannot parse, that must not look like silence.
        self.received = collections.deque(maxlen=64)
        self.last_sent = b""

        self.last_dps = {}
        self.raw_status = None
        self.last_error = None
        self.connected_at = None
        self.frames_in = 0
        self.handshake_done = False
        self.notes = []

    # ------------------------------------------------------------ keys

    @staticmethod
    def _parse_key(local_key):
        """Accept a 16-character key, a 16-character hex string, or 32 hex digits."""
        if isinstance(local_key, (bytes, bytearray)):
            raw = bytes(local_key)
            if len(raw) == 16:
                return raw
            raise BadRequest("local key must be 16 bytes")
        if not isinstance(local_key, str):
            raise BadRequest("local key is required")

        text = local_key.strip()
        if len(text) == 32:
            try:
                return bytes.fromhex(text)
            except ValueError:
                pass
        raw = text.encode("utf-8")
        if len(raw) != 16:
            raise BadRequest(
                "local key must be exactly 16 characters (got %d). Copy it from the "
                "Smart Life / Tuya app device information screen, or from the cloud "
                "API's GET /v1.0/devices/{id} response." % len(raw))
        return raw

    # ------------------------------------------------------------ lifecycle

    @property
    def is_open(self):
        return self._sock is not None and not self._stop.is_set()

    def open(self):
        if self._sock is not None:
            raise ConsoleError("already connected", status=409)

        try:
            sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except socket.timeout:
            raise DeviceTimeout("could not reach %s:%d within %.0fs" % (self.host, self.port, self.timeout))
        except OSError as exc:
            raise ConsoleError("could not connect to %s:%d: %s" % (self.host, self.port, exc))

        sock.settimeout(self.timeout)
        self._sock = sock
        self._stop.clear()
        self._buffer = b""
        self._seq = 0

        try:
            if self.version in ("3.4", "3.5"):
                self._handshake()
            self.handshake_done = True
        except Exception:
            # Never leave a half-open socket behind: the caller must be able to
            # fix the key and connect again without restarting the console.
            self.close()
            raise

        self.connected_at = time.time()
        self._reader = threading.Thread(target=self._read_loop, name="tuya-reader", daemon=True)
        self._reader.start()
        return self

    def close(self):
        self._stop.set()
        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        waiter = self._waiter
        if waiter is not None:
            try:
                waiter[1].put_nowait(None)
            except Exception:  # noqa: BLE001 - a closed waiter is harmless
                pass

    # ------------------------------------------------------------ handshake

    def _handshake(self):
        client_nonce = os.urandom(16)
        self._send_raw(self._pack(R.CMD_SESS_KEY_NEG_START, client_nonce), key=self._local_key)

        reply = self._read_message_raw(self._local_key)
        if reply["cmd"] != R.CMD_SESS_KEY_NEG_RESP:
            raise HandshakeFailed(
                "expected a session key response (0x04), got 0x%02X. The device probably "
                "speaks a different protocol version - try 3.3 or 3.4." % reply["cmd"])

        body = reply["payload"]
        # Some firmware prefixes a 4-byte return code even here.
        if len(body) == 52:
            body = body[4:]
        if len(body) != 48:
            raise HandshakeFailed(
                "session key response was %d bytes, expected 48 or 52" % len(reply["payload"]))

        device_nonce = body[:16]
        expected = hmac.new(self._local_key, client_nonce, sha256).digest()
        if not hmac.compare_digest(expected, body[16:48]):
            raise HandshakeFailed(
                "the device rejected the local key (nonce HMAC mismatch). The key is wrong, "
                "or the device is on a different protocol version.")

        finish = hmac.new(self._local_key, device_nonce, sha256).digest()
        self._send_raw(self._pack(R.CMD_SESS_KEY_NEG_FINISH, finish), key=self._local_key)

        if self.version == "3.5":
            self._key = R.derive_session_key_35(self._local_key, client_nonce, device_nonce)
        else:
            self._key = R.derive_session_key_34(self._local_key, client_nonce, device_nonce)

    # ------------------------------------------------------------ framing

    def _pack(self, command, payload, key=None, header=True):
        key = key or self._key
        self._seq += 1
        if not header:
            return self._pack_without_header(key, self._seq, command, payload)
        if self.version == "3.5":
            return R.pack_6699(key, self._seq, command, payload)
        return R.pack_55aa(key, self._seq, command, payload, self.version,
                           hmac_mode=self.hmac_mode)

    def _pack_without_header(self, key, seq, command, payload):
        """Same frame with no 15-byte "3.x" header before the JSON body.

        Some firmware parses the control body strictly and drops a frame that
        starts with the version header. There is no switch for this in
        reference_impl, so the frame is built here from the same primitives.
        """
        if self.version == "3.5":
            length = 12 + len(payload) + 16
            head = struct.pack(">IHIII", 0x00006699, 0, seq, command, length)
            iv = os.urandom(12)
            ciphertext, tag = R._gcm_encrypt(key, iv, payload, head[4:])
            return head + iv + ciphertext + tag + R.SUFFIX_6699

        ciphertext = R._ecb(key, R.pkcs7_pad(payload), True)
        end_len = 36 if self.hmac_mode else 8
        head = struct.pack(">IIII", 0x000055AA, seq, command, len(ciphertext) + end_len)
        data = head + ciphertext
        if self.hmac_mode:
            return data + hmac.new(key, data, sha256).digest() + R.SUFFIX_55AA
        return data + struct.pack(">I", R.crc32(data)) + R.SUFFIX_55AA

    def _unpack(self, frame, key=None):
        key = key or self._key
        if self.version == "3.5":
            message = R.unpack_6699(frame, key, retcode_policy="auto")
            # An empty acknowledgement decrypts to just the return code.
            if message["retcode"] is None and message["payload"] == b"\x00\x00\x00\x00":
                message["retcode"] = 0
                message["payload"] = b""
            return message

        try:
            message = R.unpack_55aa(frame, key, self.version, hmac_mode=self.hmac_mode,
                                    retcode_policy="auto", header_mode="prepended")
        except Exception:
            # Firmware that omits the return code on replies.
            message = R.unpack_55aa(frame, key, self.version, hmac_mode=self.hmac_mode,
                                    retcode_policy="never", header_mode="prepended")

        # Strip the 15-byte version header only when it is actually there. The
        # codec's own "inside" mode strips unconditionally, which eats the first
        # 15 bytes of a reply to a frame sent without one.
        body = message["payload"]
        if (message["cmd"] not in R.NO_VERSION_HEADER_CMDS
                and body[:3] in (b"3.3", b"3.4", b"3.5") and len(body) > 15):
            message["payload"] = body[15:]
        return message

    @staticmethod
    def _frame_size(buffer):
        """Total length of the frame at the head of buffer, or 0 when incomplete."""
        if len(buffer) < 4:
            return 0
        if buffer[:4] == R.PREFIX_6699:
            if len(buffer) < 18:
                return 0
            length = struct.unpack(">I", buffer[14:18])[0]
            return 18 + length + 4
        if buffer[:4] == R.PREFIX_55AA:
            if len(buffer) < 16:
                return 0
            length = struct.unpack(">I", buffer[12:16])[0]
            return 16 + length
        return -1

    @property
    def payload_styles(self):
        """Envelopes to try, in order. 3.4/3.5 want the modern one first."""
        if self.payload_style and self.payload_style != "auto":
            return [self.payload_style]
        if self.version in ("3.4", "3.5"):
            return ["modern5", "modern4", "standard", "gwid", "both"]
        return ["standard", "gwid", "both"]

    @property
    def control_candidates(self):
        """Control commands to try, in order.

        3.4/3.5 firmware commonly implements only CONTROL_NEW (0x0D) and silently
        ignores the legacy CONTROL (0x07), so try the modern one first there.
        """
        if self.control == "legacy":
            return [R.CMD_CONTROL]
        if self.control == "new":
            return [R.CMD_CONTROL_NEW]
        return ([R.CMD_CONTROL, R.CMD_CONTROL_NEW] if self.version == "3.3"
                else [R.CMD_CONTROL_NEW, R.CMD_CONTROL])

    def _send_raw(self, frame, key=None):
        self.last_sent = frame
        with self._write_lock:
            sock = self._sock
            if sock is None:
                raise ConsoleError("the device is not connected", status=409)
            sock.sendall(frame)

    # ------------------------------------------------------------ receive

    def _read_loop(self):
        while not self._stop.is_set():
            sock = self._sock
            if sock is None:
                return
            try:
                chunk = sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if not chunk:
                break
            self._buffer += chunk
            while True:
                size = self._frame_size(self._buffer)
                if size == 0:
                    break
                if size < 0:
                    self._buffer = b""
                    self.last_error = "garbage on the wire: not a Tuya frame"
                    break
                frame, self._buffer = self._buffer[:size], self._buffer[size:]
                try:
                    message = self._unpack(frame)
                except Exception as exc:  # noqa: BLE001 - report, keep the socket
                    self.last_error = "could not decode a frame: %s" % exc
                    self.received.append({"t": time.monotonic(), "cmd": None,
                                          "size": len(frame), "ok": False,
                                          "error": str(exc)})
                    continue
                self.received.append({"t": time.monotonic(), "cmd": message["cmd"],
                                      "size": len(frame), "ok": True})
                self._on_message(message)
        self._stop.set()

    def _read_message_raw(self, key):
        """Blocking read of one frame during the handshake, before the reader exists."""
        deadline = time.time() + self.timeout
        while True:
            size = self._frame_size(self._buffer)
            if size > 0:
                frame, self._buffer = self._buffer[:size], self._buffer[size:]
                try:
                    return self._unpack(frame, key=key)
                except Exception as exc:  # noqa: BLE001 - almost always a wrong key
                    raise HandshakeFailed(
                        "the device's handshake reply could not be decrypted with this "
                        "local key (%s). Check the 16-character key." % exc)
            if time.time() > deadline:
                raise DeviceTimeout(
                    "the device did not answer the handshake within %.0fs. A wrong local "
                    "key, or a different protocol version (try 3.3 or 3.4)." % self.timeout)
            try:
                chunk = self._sock.recv(4096)
            except socket.timeout:
                raise DeviceTimeout(
                    "the device did not answer the handshake within %.0fs. A wrong local "
                    "key, or a different protocol version (try 3.3 or 3.4)." % self.timeout)
            except OSError as exc:
                raise ConsoleError("the connection dropped during the handshake: %s" % exc)
            if not chunk:
                raise HandshakeFailed(
                    "the device closed the connection during the handshake. That is almost "
                    "always a wrong local key - the device could not decrypt the request - "
                    "or a device on a different protocol version (try 3.3 or 3.4).")
            self._buffer += chunk

    def _on_message(self, message):
        self.frames_in += 1
        if message.get("retcode") not in (None, 0):
            self.last_error = "device returned code %s for command 0x%02X" % (
                message["retcode"], message["cmd"])

        dps = self._extract_dps(message["payload"])
        if dps:
            self.last_dps.update(dps)
            self.raw_status = message["payload"].decode("utf-8", "replace")
            self.events.append({"t": time.monotonic(), "cmd": message["cmd"], "dps": dps})

        waiter = self._waiter
        if waiter is not None and message["cmd"] in waiter[0]:
            waiter[1].put(message)
        elif dps:
            self._stash.append(message)

    @staticmethod
    def _extract_dps(payload):
        if not payload or payload[:1] not in (b"{", b"["):
            return {}
        try:
            body = json.loads(payload.decode("utf-8", "replace"))
        except ValueError:
            return {}
        if not isinstance(body, dict):
            return {}
        if isinstance(body.get("data"), dict) and isinstance(body["data"].get("dps"), dict):
            return body["data"]["dps"]
        if isinstance(body.get("dps"), dict):
            return body["dps"]
        return {}

    # ------------------------------------------------------------ requests

    def request(self, command, payload, accepted, timeout=None, header=True):
        timeout = self.timeout if timeout is None else float(timeout)
        with self._req_lock:
            if not self.is_open:
                raise ConsoleError("the device is not connected", status=409)

            # Discard anything still in flight from the previous command. The
            # reader keeps consuming and only updates last_dps while no waiter is
            # installed, so this drains without losing state.
            if self.quiesce > 0:
                time.sleep(self.quiesce)
            self._stash.clear()

            waiting = queue.Queue()
            self._waiter = (set(accepted), waiting)
            try:
                self._send_raw(self._pack(command, payload, header=header))
                deadline = time.time() + timeout
                while True:
                    remaining = deadline - time.time()
                    if remaining <= 0:
                        raise DeviceTimeout(
                            "no reply to command 0x%02X within %.1fs" % (command, timeout))
                    try:
                        message = waiting.get(timeout=remaining)
                    except queue.Empty:
                        raise DeviceTimeout(
                            "no reply to command 0x%02X within %.1fs" % (command, timeout))
                    if message is None:
                        raise ConsoleError("the connection closed while waiting for a reply")
                    if message["cmd"] in accepted:
                        return message
            finally:
                self._waiter = None

    # ------------------------------------------------------------ operations

    def _await_push(self, seconds):
        """Wait briefly for an unsolicited status frame and return its dps."""
        while self._stash:
            dps = self._extract_dps(self._stash.pop()["payload"])
            if dps:
                return dps
        waiting = queue.Queue()
        with self._req_lock:
            if not self.is_open:
                return {}
            self._waiter = (STATUS_REPLIES, waiting)
            try:
                message = waiting.get(timeout=seconds)
            except queue.Empty:
                return {}
            finally:
                self._waiter = None
        if message is None:
            return {}
        return self._extract_dps(message["payload"])

    def _query_payload(self):
        payload = {
            "gwId": self.device_id,
            "devId": self.device_id,
            "uid": self.device_id,
            "t": int(time.time()),
        }
        if self.cid:
            payload["cid"] = self.cid
        return json.dumps(payload).encode()

    def get_status(self, timeout=None):
        message = self.request(self.query_command, self._query_payload(),
                              STATUS_REPLIES, timeout=timeout)
        dps = self._extract_dps(message["payload"])

        if not dps:
            # Some firmware answers the legacy query with "json obj data unvalid".
            alternate = (R.CMD_DP_QUERY_NEW if self.query_command == R.CMD_DP_QUERY
                         else R.CMD_DP_QUERY)
            try:
                message = self.request(alternate, self._query_payload(), STATUS_REPLIES, timeout=timeout)
                dps = self._extract_dps(message["payload"])
            except ConsoleError:
                pass

        if dps:
            self.last_dps.update(dps)
        return {
            "dps": dict(self.last_dps),
            "command": "0x%02X" % message["cmd"],
            "raw": message["payload"].decode("utf-8", "replace")[:2000],
        }

    def _read_state(self, timeout=None):
        """Current data points, falling back to what the device last pushed."""
        try:
            return self.get_status(timeout=timeout).get("dps") or {}
        except ConsoleError:
            return dict(self.last_dps)

    def _control_payload(self, dps, style="standard"):
        now = int(time.time())
        # 3.4 and 3.5 firmware expect the modern envelope:
        #   {"protocol": 5, "t": ..., "data": {"dps": {...}}}
        # The legacy {devId, uid, t, dps} shape is what 3.1-3.3 used. A 3.5
        # device that gets the legacy shape finds no data.dps and drops the
        # frame without a word - which looks exactly like a dead socket.
        if style in ("modern5", "modern4"):
            payload = {"protocol": 5 if style == "modern5" else 4,
                       "t": now, "data": {"dps": dps}}
            if self.cid:
                payload["cid"] = self.cid
            return payload
        if style == "gwid":
            payload = {"gwId": self.device_id, "uid": self.device_id, "t": now, "dps": dps}
        elif style == "both":
            payload = {"gwId": self.device_id, "devId": self.device_id,
                       "uid": self.device_id, "t": now, "dps": dps}
        else:
            payload = {"devId": self.device_id, "uid": self.device_id, "t": now, "dps": dps}
        if self.cid:
            payload["cid"] = self.cid
        return payload

    def _attempt_control(self, command, dps, header=True, payload_style="standard",
                         timeout=None):
        """Send one control frame and report exactly what the device did."""
        body = json.dumps(self._control_payload(dps, payload_style)).encode()
        before = dict(self.last_dps)
        attempt = {"command": "0x%02X" % command, "header": header,
                   "payload_style": payload_style, "acknowledged": False,
                   "reply": None, "state_after": None, "state_changed": False}
        try:
            message = self.request(command, body, CONTROL_ACKS,
                                   timeout=self.ack_timeout, header=header)
            attempt["reply"] = "0x%02X" % message["cmd"]
            reply_text = message["payload"].decode("utf-8", "replace")
            attempt["reply_payload"] = reply_text[:300]
            if _looks_like_error(reply_text):
                attempt["rejected"] = True
                attempt["error_reply"] = reply_text.strip()
            else:
                attempt["acknowledged"] = True
        except DeviceTimeout:
            pass
        except ConsoleError as exc:
            attempt["error"] = exc.message

        # Give the device a moment to push the new state before judging it. A
        # momentary relay answers, then reverts - that window is the whole
        # difference between "ignored the command" and "pulsed and dropped".
        if self.settle > 0:
            time.sleep(self.settle)

        state = self._read_state(timeout=timeout)
        attempt["state_after"] = dict(state)
        # "Changed" has to mean changed. If the plug was already in the state we
        # asked for, the read-back proves nothing and only an acknowledgement
        # counts - otherwise pressing ON on a plug that is already on would
        # report success for a frame the device ignored.
        matched_before = all(_same(before.get(str(k)), v) for k, v in dps.items())
        attempt["state_changed"] = (all(_same(state.get(str(k)), v) for k, v in dps.items())
                                    and not matched_before)
        return attempt

    def _switch_report(self, dps, state, attempts, acknowledged, started):
        # Did the device ever report the requested state, even briefly? A
        # momentary relay shows up here as true at 0.06s and false again at
        # 0.21s, which the settled state alone cannot tell you.
        saw_change = any(
            _same(e["dps"].get(str(k)), v)
            for e in self.events if e["t"] >= started
            for k, v in dps.items())

        confirmed = all(_same(state.get(str(k)), v) for k, v in dps.items())

        return {
            "acknowledged": acknowledged,
            "sent": dps,
            "status": {"dps": dict(state), "source": "read-back query"},
            "confirmed": confirmed,
            "control_command": "0x%02X" % self.control_command,
            "attempts": attempts,
            "events": [{"after_s": round(e["t"] - started, 2),
                        "command": "0x%02X" % e["cmd"],
                        "dps": e["dps"]}
                       for e in self.events if e["t"] >= started][:8],
            "received": [{"after_s": round(r["t"] - started, 2),
                          "command": ("0x%02X" % r["cmd"]) if r["cmd"] is not None else None,
                          "size": r["size"], "ok": r["ok"], "error": r.get("error")}
                         for r in self.received if r["t"] >= started][:12],
            "errors": [r["error"] for r in self.received
                       if r["t"] >= started and not r["ok"]][:6],
            # The device went to the requested state and then left it again: a
            # momentary / inching relay, or a toggle that fired twice.
            "rejected": [{"command": a["command"], "header": a["header"],
                          "payload_style": a["payload_style"],
                          "message": a.get("error_reply")}
                         for a in attempts if a.get("rejected")],
            "pulsed": bool(saw_change and not confirmed),
            "debug": {"sent_hex": self.last_sent[:24].hex()},
        }

    def set_dps(self, dps, timeout=None, wait_for_ack=True, header=None,
                payload_style=None, commands=None):
        started = time.monotonic()
        attempts = []
        acknowledged = False
        state = {}
        header_modes = ([bool(header)] if header is not None else [True, False])
        styles = (self.payload_styles if (not payload_style or payload_style == "auto")
                  else [payload_style])
        found = False

        for style in styles:
            for header_mode in header_modes:
                for command in (commands or self.control_candidates):
                    attempt = self._attempt_control(command, dps, header_mode, style, timeout)
                    attempts.append(attempt)
                    state = attempt["state_after"] or {}
                    if attempt["acknowledged"] or attempt["state_changed"]:
                        acknowledged = acknowledged or attempt["acknowledged"]
                        # Remember the shape this device accepts, so the next
                        # command goes out right the first time.
                        self.control_command = command
                        self.payload_style = style
                        self.header = header_mode
                        found = True
                        break
                if found:
                    break
            if found:
                break

        if not found and (not payload_style or payload_style == "auto"):
            # Nothing in the short list worked. Rather than reporting a failure,
            # search the whole space (including with and without the version
            # header) once, so a single click does the debugging.
            tried = {(a["command"], a["header"], a["payload_style"]) for a in attempts}
            wide = self.probe(dps, timeout=timeout, skip=tried)
            attempts.extend(wide["attempts"])
            if wide["status"]["dps"]:
                state = wide["status"]["dps"]
            acknowledged = acknowledged or wide["acknowledged"]
            if wide["winner"]:
                self.control_command = int(wide["winner"]["command"], 16)
                self.payload_style = wide["winner"]["payload_style"]
                self.header = wide["winner"]["header"]

        if not state:
            state = self._read_state(timeout=timeout)

        return self._switch_report(dps, state, attempts, acknowledged, started)

    def probe(self, dps, timeout=None, skip=None):
        """Try every plausible control shape and report what the device answers.

        Commands x version header x payload shape. Stops at the first shape the
        device reacts to. If none of them draws a response, the device is not
        refusing the encoding - something else is in the way (wrong data point,
        a gateway that owns the sub-device, or firmware that only accepts
        control from the app).
        """
        started = time.monotonic()
        attempts = []
        acknowledged = False
        state = {}
        winner = None

        combos = [(command, header, style)
                  for style in ("modern5", "modern4", "standard", "gwid", "both")
                  for header in (True, False)
                  for command in self.control_candidates]

        skip = skip or set()
        for command, header, style in combos:
            if ("0x%02X" % command, header, style) in skip:
                continue
            attempt = self._attempt_control(command, dps, header, style, timeout)
            attempts.append(attempt)
            state = attempt["state_after"] or {}
            if attempt["acknowledged"] or attempt["state_changed"]:
                winner = {"command": "0x%02X" % command, "header": header,
                          "payload_style": style}
                acknowledged = attempt["acknowledged"]
                self.control_command = command
                self.payload_style = style
                self.header = header
                break

        if not state:
            state = self._read_state(timeout=timeout)

        report = self._switch_report(dps, state, attempts, acknowledged, started)
        report["winner"] = winner
        report["combinations"] = len(attempts)
        return report

    def set_switch(self, on, dp=SWITCH_DP, timeout=None, header=None,
                   payload_style=None):
        return self.set_dps({str(dp): bool(on)}, timeout=timeout, header=header,
                            payload_style=payload_style)

    def send_raw(self, command, payload, timeout=None, accepted=None):
        if not isinstance(command, int) or not 0 <= command <= 0xFF:
            raise BadRequest("command must be an integer 0-255")
        body = payload.encode() if isinstance(payload, str) else bytes(payload or b"")
        accepted = accepted or {command, R.CMD_STATUS, R.CMD_DP_QUERY_NEW,
                                R.CMD_CONTROL, R.CMD_CONTROL_NEW}
        message = self.request(command, body, accepted, timeout=timeout)
        return {
            "command": "0x%02X" % message["cmd"],
            "retcode": message.get("retcode"),
            "payload": message["payload"].decode("utf-8", "replace"),
        }

    def info(self):
        return {
            "device_id": self.device_id,
            "host": self.host,
            "port": self.port,
            "version": self.version,
            "command_set": "new" if self.query_command == R.CMD_DP_QUERY_NEW else "legacy",
            "control_command": "0x%02X" % self.control_command,
            "control_mode": self.control,
            "payload_style": self.payload_style,
            "version_header": self.header,
            "session_key": self.handshake_done and self.version in ("3.4", "3.5"),
            "connected": self.is_open,
            "connected_at": self.connected_at,
            "frames_in": self.frames_in,
            "last_dps": dict(self.last_dps),
            "last_error": self.last_error,
        }


# --------------------------------------------------------------------------
# Device registry
# --------------------------------------------------------------------------
class Registry:
    def __init__(self):
        self._lock = threading.Lock()
        self._devices = {}

    def add(self, client):
        with self._lock:
            existing = self._devices.get(client.device_id)
            if existing is not None and existing.is_open and existing.host != client.host:
                existing.close()
            self._devices[client.device_id] = client
        return client

    def get(self, device_id):
        with self._lock:
            client = self._devices.get(device_id)
        if client is None:
            raise ConsoleError("no such device: %s" % device_id, status=404)
        return client

    def list(self):
        with self._lock:
            return [c.info() for c in self._devices.values()]

    def remove(self, device_id):
        with self._lock:
            client = self._devices.pop(device_id, None)
        if client is not None:
            client.close()
        return client is not None

    def close_all(self):
        with self._lock:
            clients = list(self._devices.values())
            self._devices.clear()
        for client in clients:
            client.close()


# --------------------------------------------------------------------------
# HTTP layer
# --------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "TuyaDeviceConsole/" + __version__
    registry = None
    api_key = None
    verbose = True

    # -------------------------------------------------------- plumbing

    def log_message(self, fmt, *args):
        if self.verbose:
            sys.stderr.write("  %s  %s\n" % (self.address_string(), fmt % args))

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")

    def _send(self, status, body, content_type="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, indent=2, default=str).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self._cors()
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass

    def _json(self, status, payload):
        self._send(status, payload)

    def _error(self, exc):
        if isinstance(exc, ConsoleError):
            payload = {"ok": False, "error": {"type": type(exc).__name__, "message": exc.message}}
            if exc.detail:
                payload["error"]["detail"] = exc.detail
            self._json(exc.status, payload)
        else:
            self._json(500, {"ok": False, "error": {
                "type": type(exc).__name__, "message": str(exc),
                "traceback": traceback.format_exc().splitlines()[-3:]}})

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise BadRequest("request body must be JSON: %s" % exc)
        if not isinstance(body, dict):
            raise BadRequest("request body must be a JSON object")
        return body

    def _authorised(self):
        if not self.api_key:
            return True
        supplied = self.headers.get("X-API-Key") or ""
        return hmac.compare_digest(supplied, self.api_key)

    # -------------------------------------------------------- verbs

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path == "/":
            return self._send(200, PAGE, "text/html")
        if path == "/api/health":
            return self._json(200, {"ok": True, "service": "tuya-device-console",
                                    "version": __version__,
                                    "auth_required": bool(self.api_key)})
        try:
            if not self._authorised():
                raise ConsoleError("missing or wrong X-API-Key", status=401)
            return self._route_get(path)
        except Exception as exc:  # noqa: BLE001
            return self._error(exc)

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        try:
            if not self._authorised():
                raise ConsoleError("missing or wrong X-API-Key", status=401)
            return self._route_post(path, self._body())
        except Exception as exc:  # noqa: BLE001
            return self._error(exc)

    def do_DELETE(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        try:
            if not self._authorised():
                raise ConsoleError("missing or wrong X-API-Key", status=401)
            parts = [p for p in path.split("/") if p]
            if len(parts) == 3 and parts[:2] == ["api", "devices"]:
                removed = self.registry.remove(parts[2])
                if not removed:
                    raise ConsoleError("no such device: %s" % parts[2], status=404)
                return self._json(200, {"ok": True, "disconnected": parts[2]})
            raise ConsoleError("no such endpoint: %s" % path, status=404)
        except Exception as exc:  # noqa: BLE001
            return self._error(exc)

    # -------------------------------------------------------- routes

    def _route_get(self, path):
        if path == "/api/devices":
            return self._json(200, {"ok": True, "devices": self.registry.list()})
        parts = [p for p in path.split("/") if p]
        if len(parts) >= 3 and parts[:2] == ["api", "devices"]:
            client = self.registry.get(parts[2])
            if len(parts) == 3:
                return self._json(200, {"ok": True, "device": client.info()})
            if len(parts) == 4 and parts[3] == "status":
                result = client.get_status()
                return self._json(200, {"ok": True, "device": client.info(), **result})
        raise ConsoleError("no such endpoint: %s" % path, status=404)

    def _route_post(self, path, body):
        if path == "/api/connect":
            return self._connect(body)
        if path == "/api/disconnect-all":
            self.registry.close_all()
            return self._json(200, {"ok": True})

        parts = [p for p in path.split("/") if p]
        if len(parts) == 4 and parts[:2] == ["api", "devices"]:
            client = self.registry.get(parts[2])
            action = parts[3]
            if action == "status":
                result = client.get_status(timeout=body.get("timeout"))
                return self._json(200, {"ok": True, "device": client.info(), **result})
            if action == "switch":
                if "on" not in body:
                    raise BadRequest("'on' is required (true or false)")
                if body.get("control") in ("auto", "legacy", "new"):
                    client.control = body["control"]
                result = client.set_switch(bool(body["on"]), dp=body.get("dp", SWITCH_DP),
                                           timeout=body.get("timeout"),
                                           header=body.get("header"),
                                           payload_style=body.get("payload_style"))
                return self._json(200, {"ok": True, "device": client.info(), **result})
            if action == "probe":
                result = client.probe({str(body.get("dp", SWITCH_DP)):
                                       bool(body.get("on", True))},
                                      timeout=body.get("timeout"))
                return self._json(200, {"ok": True, "device": client.info(), **result})
            if action == "dp":
                if "dp" not in body or "value" not in body:
                    raise BadRequest("'dp' and 'value' are required")
                result = client.set_dps({str(body["dp"]): body["value"]},
                                        timeout=body.get("timeout"))
                return self._json(200, {"ok": True, "device": client.info(), **result})
            if action == "command":
                result = client.send_raw(int(body.get("command", 0)), body.get("payload", ""),
                                         timeout=body.get("timeout"))
                return self._json(200, {"ok": True, **result})
            if action == "disconnect":
                client.close()
                self.registry.remove(parts[2])
                return self._json(200, {"ok": True, "disconnected": parts[2]})
        raise ConsoleError("no such endpoint: %s" % path, status=404)

    def _connect(self, body):
        missing = [k for k in ("host", "device_id", "local_key") if not body.get(k)]
        if missing:
            raise BadRequest("missing required field(s): %s" % ", ".join(missing))

        client = TuyaLocalClient(
            host=str(body["host"]).strip(),
            device_id=str(body["device_id"]).strip(),
            local_key=body["local_key"],
            version=str(body.get("version", "3.5")),
            port=body.get("port", DEFAULT_PORT),
            cid=body.get("cid"),
            command_set=body.get("command_set", "legacy"),
            control=body.get("control", "auto"),
            payload_style=body.get("payload_style", "auto"),
            trailer=body.get("trailer"),
            timeout=body.get("timeout", 6.0),
            ack_timeout=body.get("ack_timeout", 1.0),
            settle=body.get("settle", 0.3),
        )

        client.open()
        self.registry.add(client)

        try:
            status = client.get_status(timeout=body.get("timeout"))
        except ConsoleError as exc:
            # Connected but the query failed: still report the connection, because
            # that is itself the diagnostic (right key, wrong command set, etc.).
            status = {"dps": dict(client.last_dps), "warning": exc.message}

        return self._json(200, {"ok": True, "device": client.info(), **status})


# --------------------------------------------------------------------------
# Web page
# --------------------------------------------------------------------------
PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tuya LAN device console</title>
<style>
  :root { --bg:#0f1115; --card:#171a21; --line:#272c36; --ink:#e6e9ef; --dim:#98a2b3;
          --on:#2e9e5b; --off:#8a94a6; --accent:#4c8dff; --bad:#d9534f; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--ink);
         font:14px/1.5 ui-sans-serif,-apple-system,Segoe UI,Roboto,sans-serif; }
  .wrap { max-width:960px; margin:0 auto; padding:28px 20px 60px; }
  h1 { font-size:20px; margin:0 0 4px; font-weight:600; letter-spacing:-.01em; }
  .sub { color:var(--dim); margin:0 0 24px; font-size:13px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:10px;
          padding:18px; margin-bottom:16px; }
  .card h2 { font-size:13px; text-transform:uppercase; letter-spacing:.06em;
             color:var(--dim); margin:0 0 14px; font-weight:600; }
  .grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
  .grid .wide { grid-column:1 / -1; }
  label { display:block; font-size:12px; color:var(--dim); margin-bottom:5px; }
  input, select { width:100%; padding:9px 10px; background:#0e1116; color:var(--ink);
                  border:1px solid var(--line); border-radius:7px; font:inherit; }
  input:focus, select:focus { outline:none; border-color:var(--accent); }
  button { padding:9px 16px; border-radius:7px; border:1px solid var(--line);
           background:#212734; color:var(--ink); font:inherit; cursor:pointer; }
  button:hover:not(:disabled) { border-color:#3b4459; }
  button:disabled { opacity:.45; cursor:not-allowed; }
  button.primary { background:var(--accent); border-color:var(--accent); color:#fff; font-weight:600; }
  button.on { background:var(--on); border-color:var(--on); color:#fff; font-weight:600; }
  button.off { background:#2a3040; }
  .row { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
  .pill { display:inline-flex; align-items:center; gap:7px; padding:4px 11px; border-radius:999px;
          background:#0e1116; border:1px solid var(--line); font-size:12px; }
  .dot { width:8px; height:8px; border-radius:50%; background:var(--off); }
  .dot.live { background:var(--on); box-shadow:0 0 0 3px rgba(46,158,91,.18); }
  .dps { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; margin-top:6px; }
  .dp { background:#0e1116; border:1px solid var(--line); border-radius:8px; padding:11px 13px; }
  .dp .k { font-size:11px; color:var(--dim); text-transform:uppercase; letter-spacing:.05em; }
  .dp .v { font-size:17px; margin-top:3px; font-variant-numeric:tabular-nums; }
  .dp .v.true { color:#4cc47f; }
  #log { font:12px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace; background:#0b0d11;
         border:1px solid var(--line); border-radius:8px; padding:12px; height:190px;
         overflow:auto; white-space:pre-wrap; color:#b9c2d0; }
  .err { color:#ff8f8b; }
  .ok { color:#5fd68f; }
  code { background:#0e1116; border:1px solid var(--line); border-radius:4px;
         padding:1px 5px; font-size:12px; }
  .hint { color:var(--dim); font-size:12px; margin-top:10px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Tuya LAN device console</h1>
  <p class="sub">Connect to one plug on your LAN and prove it switches. Nothing leaves your network.</p>

  <div class="card">
    <h2>Device</h2>
    <div class="grid">
      <div><label>Host / IP</label><input id="host" placeholder="192.168.1.42"></div>
      <div><label>Port</label><input id="port" value="6668"></div>
      <div><label>Device ID</label><input id="device_id" placeholder="eb0123456789abcdefghij"></div>
      <div><label>Local key (16 chars)</label><input id="local_key" maxlength="32" placeholder="0123456789abcdef"></div>
      <div><label>Protocol version</label>
        <select id="version"><option>3.5</option><option>3.4</option><option>3.3</option></select></div>
      <div><label>Query command</label>
        <select id="command_set"><option value="legacy">legacy (0x0A)</option>
        <option value="new">new (0x10)</option></select></div>
      <div><label>Control command</label>
        <select id="control"><option value="auto">auto (0x0D, then 0x07)</option>
        <option value="new">new (0x0D)</option>
        <option value="legacy">legacy (0x07)</option></select></div>
      <div class="wide row" style="margin-top:4px">
        <button class="primary" id="btn-connect">Connect</button>
        <button id="btn-status" disabled>Refresh status</button>
        <button id="btn-disconnect" disabled>Disconnect</button>
        <span class="pill"><span class="dot" id="dot"></span><span id="state">not connected</span></span>
      </div>
    </div>
    <p class="hint">Discovery gives you the IP and device ID. The local key comes from the
      Smart Life / Tuya app's device info screen, or the cloud API.</p>
  </div>

  <div class="card">
    <h2>Switch</h2>
    <div class="row" style="margin-bottom:10px">
      <button class="on" id="btn-on" disabled>Turn ON</button>
      <button class="off" id="btn-off" disabled>Turn OFF</button>
      <button id="btn-probe" disabled title="try every control shape and report what the device answers">Probe</button>
      <label style="margin:0 0 0 8px">DP</label>
      <input id="dp" value="1" style="width:70px">
    </div>
    <div class="row" style="margin-bottom:14px">
      <label style="margin:0">Payload</label>
      <select id="payload_style" style="width:auto">
        <option value="auto">auto (try all)</option>
        <option value="modern5">modern, protocol 5</option>
        <option value="modern4">modern, protocol 4</option>
        <option value="standard">legacy (devId)</option>
        <option value="gwid">legacy (gwId)</option>
      </select>
      <label style="margin:0 0 0 10px">Version header</label>
      <select id="header_mode" style="width:auto">
        <option value="auto">as found</option>
        <option value="with">with</option>
        <option value="without">without</option>
      </select>
    </div>
    <div class="dps" id="dps"></div>
  </div>

  <div class="card">
    <h2>Log</h2>
    <div id="log"></div>
  </div>
</div>

<script>
const $ = (id) => document.getElementById(id);
let deviceId = null;
let busy = false;

function log(text, cls) {
  const el = document.createElement('div');
  if (cls) el.className = cls;
  el.textContent = new Date().toLocaleTimeString() + '  ' + text;
  $('log').appendChild(el);
  $('log').scrollTop = $('log').scrollHeight;
}

function setBusy(state) {
  busy = state;
  const connected = !!deviceId;
  $('btn-connect').disabled = state;
  $('btn-status').disabled = state || !connected;
  $('btn-disconnect').disabled = state || !connected;
  $('btn-on').disabled = state || !connected;
  $('btn-off').disabled = state || !connected;
  $('btn-probe').disabled = state || !connected;
}

function setConnected(info) {
  deviceId = info ? info.device_id : null;
  $('dot').className = 'dot' + (deviceId ? ' live' : '');
  $('state').textContent = deviceId
    ? (info.host + ':' + info.port + '  ' + info.version +
       (info.session_key ? '  session key' : '  local key'))
    : 'not connected';
  setBusy(false);
}

function renderDps(dps) {
  const box = $('dps');
  box.innerHTML = '';
  const keys = Object.keys(dps || {});
  if (!keys.length) {
    box.innerHTML = '<div class="dp"><div class="k">data points</div><div class="v">none reported</div></div>';
    return;
  }
  for (const k of keys.sort((a, b) => Number(a) - Number(b))) {
    const v = dps[k];
    const cell = document.createElement('div');
    cell.className = 'dp';
    cell.innerHTML = '<div class="k">DP ' + k + '</div><div class="v' +
      (v === true ? ' true' : '') + '">' + JSON.stringify(v) + '</div>';
    box.appendChild(cell);
  }
}

async function call(path, body) {
  const options = body === undefined ? {} : {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
  const response = await fetch(path, options);
  let payload;
  try { payload = await response.json(); }
  catch (e) { throw new Error('HTTP ' + response.status + ' (not JSON)'); }
  if (!response.ok || payload.ok === false) {
    const err = payload.error || {};
    throw new Error((err.type ? err.type + ': ' : '') + (err.message || 'HTTP ' + response.status));
  }
  return payload;
}

$('btn-connect').onclick = async () => {
  setBusy(true);
  const body = {
    host: $('host').value.trim(),
    port: Number($('port').value || 6668),
    device_id: $('device_id').value.trim(),
    local_key: $('local_key').value.trim(),
    version: $('version').value,
    command_set: $('command_set').value,
    control: $('control').value,
  };
  log('connecting to ' + body.host + ':' + body.port + ' as ' + body.device_id + ' (' + body.version + ')');
  try {
    const result = await call('/api/connect', body);
    setConnected(result.device);
    log('connected. reply ' + result.command, 'ok');
    renderDps(result.dps);
    if (result.warning) log('status query warning: ' + result.warning, 'err');
  } catch (e) {
    setConnected(null);
    log('connect failed: ' + e.message, 'err');
  }
};

$('btn-status').onclick = async () => {
  if (!deviceId) return;
  setBusy(true);
  try {
    const result = await call('/api/devices/' + encodeURIComponent(deviceId) + '/status', {});
    renderDps(result.dps);
    log('status ' + JSON.stringify(result.dps));
  } catch (e) {
    log('status failed: ' + e.message, 'err');
  }
  setBusy(false);
};

$('btn-on').onclick = () => switchTo(true);
$('btn-off').onclick = () => switchTo(false);

async function switchTo(on) {
  if (!deviceId) return;
  setBusy(true);
  log('sending ' + (on ? 'ON' : 'OFF') + ' to DP ' + $('dp').value);
  try {
    const result = await call('/api/devices/' + encodeURIComponent(deviceId) + '/switch',
      { on: on, dp: $('dp').value,
        header: $('header_mode').value === 'auto' ? null : ($('header_mode').value === 'with'),
        payload_style: $('payload_style').value });
    renderDps(result.status.dps);
    const dp = $('dp').value;
    const state = result.status.dps[dp];
    const tried = (result.attempts || []).map(a => a.command +
      (a.acknowledged ? ' acked ' + a.reply : ' no ack') +
      (a.state_changed ? ', state changed' : '')).join(' | ');

    if (result.confirmed) {
      log('CONFIRMED  DP ' + dp + ' = ' + JSON.stringify(state) + '   [' + tried + ']', 'ok');
    } else if (result.pulsed) {
      log('PULSED  the device reported ' + JSON.stringify(result.sent) +
          ' and then went back to ' + JSON.stringify(state) +
          ' - that is a momentary / inching relay, not a failed command', 'err');
    } else {
      log('NOT CONFIRMED  DP ' + dp + ' = ' + JSON.stringify(state) +
          '   [' + tried + ']', 'err');
      if (result.attempts && result.attempts.length) {
        log('no command was accepted - check the DP id, the control command, or DP 40 (relay mode)');
      }
    }
    if (result.events && result.events.length) {
      log('frames after the command: ' + JSON.stringify(result.events));
    }
  } catch (e) {
    log('switch failed: ' + e.message, 'err');
  }
  setBusy(false);
}

$('btn-probe').onclick = async () => {
  if (!deviceId) return;
  setBusy(true);
  const dp = $('dp').value;
  log('probing control shapes on DP ' + dp + ' - this sends several frames, give it a few seconds');
  try {
    const result = await call('/api/devices/' + encodeURIComponent(deviceId) + '/probe',
      { on: true, dp: dp });
    for (const a of (result.attempts || [])) {
      log('  ' + a.command +
          (a.header ? ' +header' : ' no header') +
          ' ' + a.payload_style +
          ' -> ' + (a.acknowledged ? 'ACK ' + a.reply : 'no ack') +
          (a.state_changed ? ', STATE CHANGED' : ''),
          (a.acknowledged || a.state_changed) ? 'ok' : 'err');
    }
    if (result.winner) {
      log('WORKS: ' + result.winner.command +
          (result.winner.header ? ' with' : ' without') + ' the version header, payload ' +
          result.winner.payload_style, 'ok');
    } else {
      log('no shape drew a response in ' + result.combinations + ' attempts. The device is ' +
          'not rejecting the encoding - check the DP id, or whether the plug only accepts ' +
          'control from the app.', 'err');
    }
    if ((result.received || []).length) {
      log('frames the device sent meanwhile: ' + JSON.stringify(result.received));
    }
    if ((result.errors || []).length) {
      log('frames we could not decode: ' + JSON.stringify(result.errors), 'err');
    }
    renderDps(result.status.dps);
  } catch (e) {
    log('probe failed: ' + e.message, 'err');
  }
  setBusy(false);
};

$('btn-disconnect').onclick = async () => {
  if (!deviceId) return;
  setBusy(true);
  try {
    await call('/api/devices/' + encodeURIComponent(deviceId) + '/disconnect', {});
    log('disconnected');
  } catch (e) {
    log('disconnect failed: ' + e.message, 'err');
  }
  setConnected(null);
  renderDps({});
};

(async () => {
  try {
    const health = await call('/api/health');
    log('console ready (v' + health.version + ')' +
        (health.auth_required ? ' - API key required for /api calls' : ''));
  } catch (e) { /* health needs no auth; ignore */ }
  setBusy(false);
})();
</script>
</body>
</html>
"""


# --------------------------------------------------------------------------
# Self test
# --------------------------------------------------------------------------
def selftest():
    """Drive the console's client against tools/fake_device.py, no hardware."""
    from fake_device import FakeDevice

    failures = []

    def check(name, condition, detail=""):
        print(("  PASS  " if condition else "  FAIL  ") + name +
              ("" if condition else "  " + str(detail)))
        if not condition:
            failures.append(name)

    # --- framing for the versions the simulator cannot serve -----------------
    # The fake device only speaks 3.5, so exercise 3.3 and 3.4 by packing a
    # device-style reply and decoding it with the same client code.
    for version in ("3.3", "3.4"):
        client = TuyaLocalClient("127.0.0.1", "eb0123456789abcdefghij",
                                 "0123456789abcdef", version=version, timeout=2.0)
        body = json.dumps({"devId": "eb0123456789abcdefghij", "dps": {"1": True}}).encode()

        frame = R.pack_55aa(client._key, 7, R.CMD_DP_QUERY_NEW, body, version,
                            retcode=0, hmac_mode=client.hmac_mode)
        message = client._unpack(frame)
        check("%s: query reply decodes" % version, message["payload"] == body, message)
        check("%s: return code read" % version, message["retcode"] == 0, message)
        check("%s: dps extracted" % version,
              client._extract_dps(message["payload"]) == {"1": True}, message)

        # CONTROL carries the 15-byte version header inside the ciphertext.
        control = json.dumps({"devId": "x", "dps": {"1": False}}).encode()
        frame = R.pack_55aa(client._key, 8, R.CMD_CONTROL, control, version,
                            retcode=0, hmac_mode=client.hmac_mode)
        message = client._unpack(frame)
        check("%s: control reply header stripped" % version, message["payload"] == control,
              message["payload"][:24])

        # An empty acknowledgement is just the return code.
        frame = R.pack_55aa(client._key, 9, R.CMD_CONTROL, b"", version,
                            retcode=0, hmac_mode=client.hmac_mode)
        message = client._unpack(frame)
        check("%s: bare ack decodes" % version, message["retcode"] == 0, message)

        # A tampered frame must not decrypt.
        bad = bytearray(R.pack_55aa(client._key, 10, R.CMD_DP_QUERY_NEW, body, version,
                                    retcode=0, hmac_mode=client.hmac_mode))
        bad[20] ^= 0xFF
        rejected = False
        try:
            client._unpack(bytes(bad))
        except Exception:  # noqa: BLE001
            rejected = True
        check("%s: tampered frame rejected" % version, rejected)

        # A control body with no version header, for firmware that parses the
        # payload strictly.
        raw = b'{"devId":"x","dps":{"1":true}}'
        frame = client._pack_without_header(client._key, 11, R.CMD_CONTROL, raw)
        message = client._unpack(frame)
        check("%s: no-header control round trips" % version,
              message["payload"] == raw, message["payload"][:20])

    print("")

    port = 16669
    key = b"0123456789abcdef"
    device_id = "eb0123456789abcdefghij"
    device = FakeDevice("127.0.0.1", port, key, device_id)
    threading.Thread(target=device.serve_forever, daemon=True).start()
    time.sleep(0.3)

    client = TuyaLocalClient("127.0.0.1", device_id, key.decode(), version="3.5",
                             port=port, timeout=5.0)
    client.open()
    check("handshake completes", client.handshake_done)
    check("session key is not the local key", client._key != client._local_key)

    status = client.get_status()
    check("status returns dps", "1" in status["dps"], status)
    check("switch starts off", status["dps"].get("1") is False, status["dps"])

    raw = b'{"devId":"x","dps":{"1":true}}'
    message = client._unpack(client._pack_without_header(
        client._key, 41, R.CMD_CONTROL, raw))
    check("3.5: no-header control round trips", message["payload"] == raw,
          message["payload"][:20])

    result = client.set_switch(True)
    check("switch on confirmed", result["confirmed"], result)
    check("received frames are logged", len(result["received"]) > 0, result.get("received"))
    check("no decode errors on a healthy device", result["errors"] == [], result["errors"])
    check("read-back is on", result["status"]["dps"].get("1") is True, result["status"])

    result = client.set_switch(False)
    check("switch off confirmed", result["confirmed"], result)
    check("read-back is off", result["status"]["dps"].get("1") is False, result["status"])

    raw = client.send_raw(R.CMD_HEART_BEAT, b"")
    check("raw heartbeat answered", raw["command"] == "0x09", raw)

    client.close()
    check("socket closed", not client.is_open)

    # A wrong key must fail cleanly and leave nothing half-open.
    bad = TuyaLocalClient("127.0.0.1", device_id, "wrongkeywrongkey", version="3.5",
                          port=port, timeout=5.0)
    rejected = False
    try:
        bad.open()
    except HandshakeFailed:
        rejected = True
    except Exception as exc:  # noqa: BLE001
        rejected = True
        print("      (rejected with %s: %s)" % (type(exc).__name__, exc))
    check("wrong local key is rejected", rejected)
    check("wrong-key socket is not left open", not bad.is_open)

    # Firmware that implements only the legacy CONTROL (0x07) and silently ignores
    # CONTROL_NEW (0x0D). Auto control mode must fall through to 0x07 rather than
    # give up - this is the exact shape of the failure seen on a real 3.5 plug.
    class LegacyOnlyDevice(FakeDevice):
        def handle_frame(self, connection, state, frame):
            message = R.unpack_6699(frame, state["key"], retcode_policy="never")
            if message["cmd"] == R.CMD_CONTROL_NEW:
                return  # decrypted fine, then ignored, like the real firmware
            return super().handle_frame(connection, state, frame)

    legacy_port = port + 2
    legacy = LegacyOnlyDevice("127.0.0.1", legacy_port, key, device_id)
    threading.Thread(target=legacy.serve_forever, daemon=True).start()
    time.sleep(0.3)

    picky = TuyaLocalClient("127.0.0.1", device_id, key.decode(), version="3.5",
                            port=legacy_port, timeout=5.0, ack_timeout=1.0)
    picky.open()
    result = picky.set_switch(True)
    check("auto control falls back to 0x07", result["control_command"] == "0x07",
          result["attempts"])
    check("fallback still confirms the switch", result["confirmed"] is True, result)
    check("fallback reports both attempts", len(result["attempts"]) == 2, result["attempts"])

    probe = picky.probe({"1": False})
    check("probe finds a working shape", probe["winner"] is not None, probe["attempts"])
    check("probe names 0x07 with the header",
          bool(probe["winner"]) and probe["winner"]["command"] == "0x07"
          and probe["winner"]["header"] is True, probe["winner"])
    check("probe confirms the new state", probe["confirmed"] is True, probe)
    picky.close()

    # A momentary (inching) relay: it takes the command, goes ON, then drops back
    # on its own. The console must report that as "pulsed", not as a failed
    # command, because the fix is a device setting, not a protocol bug.
    class MomentaryDevice(FakeDevice):
        def handle_frame(self, connection, state, frame):
            message = R.unpack_6699(frame, state["key"], retcode_policy="never")
            if message["cmd"] in (R.CMD_CONTROL, R.CMD_CONTROL_NEW):
                self.apply_control(message["payload"])
                self.send(connection, state, R.CMD_CONTROL, b"")
                self.push_status(connection, state)
                time.sleep(0.15)
                with self.lock:
                    for dp_id, value in list(self.dps.items()):
                        if isinstance(value, bool):
                            self.dps[dp_id] = False
                self.push_status(connection, state)
                return
            return super().handle_frame(connection, state, frame)

    pulse_port = port + 3
    pulsing = MomentaryDevice("127.0.0.1", pulse_port, key, device_id)
    threading.Thread(target=pulsing.serve_forever, daemon=True).start()
    time.sleep(0.3)

    moment = TuyaLocalClient("127.0.0.1", device_id, key.decode(), version="3.5",
                             port=pulse_port, timeout=5.0, ack_timeout=1.0)
    moment.open()
    result = moment.set_switch(True)
    check("momentary relay is not confirmed", result["confirmed"] is False, result)
    check("momentary relay is reported as pulsed", result["pulsed"] is True, result)
    check("the ON was seen in the frame log",
          any(e["dps"].get("1") is True for e in result["events"]), result["events"])
    moment.close()

    # Firmware that accepts only the modern 3.4/3.5 envelope and silently drops
    # the legacy {devId, uid, t, dps} one. This is what a real 3.5 plug does.
    class ModernDevice(FakeDevice):
        @staticmethod
        def _is_modern(payload):
            try:
                body = FakeDevice.strip_envelope(payload)
                data = json.loads(body.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                return False
            return (isinstance(data.get("data"), dict)
                    and isinstance(data["data"].get("dps"), dict))

        def handle_frame(self, connection, state, frame):
            message = R.unpack_6699(frame, state["key"], retcode_policy="never")
            if (message["cmd"] in (R.CMD_CONTROL, R.CMD_CONTROL_NEW)
                    and not self._is_modern(message["payload"])):
                return  # ignored, no ack, no push - exactly like the real device
            return super().handle_frame(connection, state, frame)

        def apply_control(self, payload):
            body = FakeDevice.strip_envelope(payload)
            data = json.loads(body.decode("utf-8"))
            with self.lock:
                for key, value in data["data"]["dps"].items():
                    self.dps[str(key)] = value

    modern_port = port + 4
    modern = ModernDevice("127.0.0.1", modern_port, key, device_id)
    threading.Thread(target=modern.serve_forever, daemon=True).start()
    time.sleep(0.3)

    mod = TuyaLocalClient("127.0.0.1", device_id, key.decode(), version="3.5",
                          port=modern_port, timeout=5.0, ack_timeout=1.0)
    mod.open()
    result = mod.set_switch(True)
    check("modern-envelope device switches", result["confirmed"] is True, result)
    check("modern envelope was chosen", mod.payload_style in ("modern5", "modern4"),
          mod.payload_style)
    check("legacy envelope was rejected by it",
          result["attempts"][0]["payload_style"] in ("modern5", "modern4"),
          result["attempts"][0])
    result = mod.set_switch(False)
    check("second switch reuses the winning shape", result["confirmed"] is True, result)
    check("second switch took one attempt", len(result["attempts"]) == 1, result["attempts"])
    mod.close()

    print("")
    if failures:
        print("%d FAILURES: %s" % (len(failures), ", ".join(failures)))
        return 1
    print("all console checks passed")
    return 0


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------
def sweep_bools(client, dps, skip=()):
    """Flip every on/off data point in turn and report the first that moves."""
    moved = None
    for dp_id in sorted(dps, key=lambda k: (len(k), k)):
        value = dps[dp_id]
        if not isinstance(value, bool) or dp_id in skip:
            continue
        target = not value
        result = client.set_dps({dp_id: target})
        changed = result["confirmed"]
        print("   DP %-3s -> %-5s  %s" % (dp_id, json.dumps(target),
                                          "CHANGED" if changed else
                                          ("answered, no change" if result["acknowledged"]
                                           else "no answer")))
        if changed:
            moved = (dp_id, result["status"]["dps"].get(dp_id))
            break
    return moved


def try_device(args):
    """Connect, switch, and say the result in plain words. No browser, one command."""
    print("")
    print("Tuya plug test  %s:%d" % (args.host, args.device_port))
    print("")

    client = TuyaLocalClient(args.host, args.device_id, args.key, version=args.version,
                             port=args.device_port, timeout=args.timeout)
    try:
        client.open()
    except ConsoleError as exc:
        print("FAILED to connect: %s" % exc.message)
        return 2
    print("Connected. The local key was accepted.")

    try:
        status = client.get_status()
    except ConsoleError as exc:
        print("Connected, but reading the data points failed: %s" % exc.message)
        client.close()
        return 2

    print("")
    print("What the plug reports now:")
    for dp_id in sorted(status["dps"], key=lambda k: (len(k), k)):
        print("   DP %-3s = %s" % (dp_id, json.dumps(status["dps"][dp_id])))

    dp = str(args.dp)
    wanted = not args.off
    print("")
    print("Trying to switch DP %s %s (it reads %s right now)."
          % (dp, "ON" if wanted else "OFF", json.dumps(status["dps"].get(dp))))
    print("Trying every way of asking - up to 40 seconds.")
    print("")

    commands = [int(args.command, 16)] if args.command else None
    result = client.set_dps(
        {dp: wanted},
        header=None if args.header == "auto" else (args.header == "with"),
        payload_style=args.style,
        commands=commands,
    )
    for index, attempt in enumerate(result["attempts"], 1):
        if attempt.get("rejected"):
            outcome = "rejected: %s" % attempt.get("error_reply")
        elif attempt["acknowledged"]:
            outcome = "answered %s" % attempt["reply"]
        else:
            outcome = "no answer"
        if attempt["state_changed"]:
            outcome += ", state changed"
        print("   [%2d/%d] %-8s %s %-11s  %s"
              % (index, len(result["attempts"]), attempt["payload_style"],
                 attempt["command"],
                 "with header" if attempt["header"] else "no header", outcome))

    print("")
    if result["confirmed"]:
        winner = result["attempts"][-1]
        print("   DP %s went from %s to %s."
              % (dp, json.dumps(status["dps"].get(dp)),
                 json.dumps(result["status"]["dps"].get(dp))))
        print("RESULT: it works. The plug is %s and reports DP %s = %s."
              % ("ON" if wanted else "OFF", dp,
                 json.dumps(result["status"]["dps"].get(dp))))
        print("")
        print("The shape that worked:")
        print("   payload style  : %s" % winner["payload_style"])
        print("   control command: %s" % winner["command"])
        print("   version header : %s" % ("with" if winner["header"] else "without"))
        client.close()
        return 0

    if result["pulsed"]:
        print("RESULT: the plug switched, then dropped back on its own.")
        print("That is a momentary (inching) mode set in the app - not a fault.")
        client.close()
        return 1

    if result["acknowledged"]:
        print("RESULT: the plug accepted the command, but DP %s did not change." % dp)
        print("So the message is right and the data point is wrong.")
        said = [a.get("reply_payload") for a in result["attempts"] if a.get("reply_payload")]
        print("")
        print("What it answered: %s" % (said[0] if said else "(empty reply)"))
        print("")
        print("Looking for the data point that does switch it:")
        print("")
        found = sweep_bools(client, status["dps"], skip={dp})
        if found:
            print("")
            print("RESULT: DP %s is the switch. It now reads %s."
                  % (found[0], json.dumps(found[1])))
            print("Use --dp %s from now on." % found[0])
            client.close()
            return 0
        print("")
        print("RESULT: none of the on/off data points switch it either.")
        print("The plug accepts commands and acts on none of them, so it wants the")
        print("app, a gateway, or the Tuya cloud for control.")
        client.close()
        return 1

    rejected = result.get("rejected") or []
    if rejected:
        print("RESULT: the plug answered %d of %d shapes and rejected every one."
              % (len(rejected), len(result["attempts"])))
        print("")
        print("Its complaint, and the shape it complained about:")
        for item in rejected[:6]:
            print("   %-8s %s %-11s -> %s"
                  % (item["payload_style"], item["command"],
                     "with header" if item["header"] else "no header", item["message"]))
        print("")
        print("So it hears us and dislikes the body. That is a payload problem, and")
        print("the shapes above are the ones it has already ruled out.")
    else:
        print("RESULT: the plug ignored all %d ways of asking." % len(result["attempts"]))
        print("")
        print("No acknowledgement at all, so the message itself is still wrong for this")
        print("device - not the data point.")
    if (result.get("errors") or []):
        print("Frames we could not read: %s" % "; ".join(result["errors"][:3]))
    client.close()
    return 1


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Local Tuya device console: web page + REST API to test on/off control.")
    parser.add_argument("--listen", default="127.0.0.1",
                        help="address to bind (use 0.0.0.0 to reach it from other machines)")
    parser.add_argument("--port", type=int, default=8080, help="HTTP port (default 8080)")
    parser.add_argument("--api-key", default=os.environ.get("TUYA_CONSOLE_API_KEY"),
                        help="require this key in the X-API-Key header for /api calls")
    parser.add_argument("--demo", action="store_true",
                        help="also start a fake 3.5 device on 127.0.0.1:16668 to click through")
    parser.add_argument("--selftest", action="store_true",
                        help="run the end-to-end check against the fake device and exit")
    parser.add_argument("--try", action="store_true", dest="try_mode",
                        help="connect to one plug, try to switch it, print the result, exit")
    parser.add_argument("--host", help="device IP for --try")
    parser.add_argument("--device-port", type=int, default=DEFAULT_PORT,
                        help="device TCP port for --try (default 6668)")
    parser.add_argument("--device-id", help="device id for --try")
    parser.add_argument("--key", help="16-character local key for --try")
    parser.add_argument("--version", default="3.5", help="protocol version for --try (3.3/3.4/3.5)")
    parser.add_argument("--dp", default=SWITCH_DP, help="data point to switch for --try (default 1)")
    parser.add_argument("--off", action="store_true", help="switch OFF instead of ON")
    parser.add_argument("--device-timeout", type=float, default=6.0,
                        help="socket timeout for --try")
    parser.add_argument("--style", default=None,
                        help="pin the payload style for --try (standard/gwid/both/modern5/modern4)")
    parser.add_argument("--header", default="auto", choices=["auto", "with", "without"],
                        help="pin the version header for --try")
    parser.add_argument("--command", default=None,
                        help="pin the control command for --try (e.g. 0x0D)")
    parser.add_argument("--quiet", action="store_true", help="no per-request logging")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if args.try_mode:
        missing = [name for name in ("host", "device_id", "key") if not getattr(args, name)]
        if missing:
            print("--try needs --host, --device-id and --key")
            return 2
        args.timeout = args.device_timeout
        return try_device(args)

    if args.demo:
        from fake_device import FakeDevice
        demo = FakeDevice("127.0.0.1", 16668, b"0123456789abcdef", "eb0123456789abcdefghij")
        threading.Thread(target=demo.serve_forever, daemon=True).start()
        print("demo device listening on 127.0.0.1:16668")
        print("  host      127.0.0.1")
        print("  port      16668")
        print("  device id eb0123456789abcdefghij")
        print("  local key 0123456789abcdef")
        print("  version   3.5")

    Handler.registry = Registry()
    Handler.api_key = args.api_key
    Handler.verbose = not args.quiet

    class ConsoleServer(ThreadingHTTPServer):
        daemon_threads = True
        # On Windows SO_REUSEADDR lets a second process bind a port that is
        # already listening, so an old copy of this console silently keeps
        # answering the browser and new code never runs. Refuse the second bind.
        allow_reuse_address = os.name != "nt"

    try:
        server = ConsoleServer((args.listen, args.port), Handler)
    except OSError as exc:
        print("")
        print("Could not start: %s" % exc)
        print("Port %d is already in use - an older copy of this console is probably"
              % args.port)
        print("still running. Close it (Ctrl+C in that window), or start with --port %d."
              % (args.port + 1))
        return 2
    shown = "127.0.0.1" if args.listen in ("0.0.0.0", "") else args.listen
    print("")
    print("Tuya device console v%s on http://%s:%d   (pid %d)"
          % (__version__, shown, args.port, os.getpid()))
    if args.api_key:
        print("API key required: send it as the X-API-Key header")
    print("POST /api/connect {host, device_id, local_key, version}")
    print("")
    print("No browser? one command does the same:")
    print("  python %s --try --host <ip> --device-id <id> --key <key>" % os.path.basename(__file__))
    print("POST /api/devices/{id}/switch {on: true}")
    print("press Ctrl+C to stop")
    print("")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping")
    finally:
        Handler.registry.close_all()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
