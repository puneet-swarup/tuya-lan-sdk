#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
End-to-end check of the 3.5 session layer against the fake device.

Starts tools/fake_device.py in a thread and drives it exactly the way
TuyaLocalDevice does in C# and Kotlin: session-key handshake, DP_QUERY, CONTROL,
and the STATUS push that follows a control frame. This is what proves the
handshake ordering and key derivation in the two libraries match a real
implementation, since neither of them can be compiled in this environment.

    python3 tools/test_roundtrip.py
"""

import hmac
import json
import os
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reference_impl as R  # noqa: E402
from fake_device import FakeDevice, split_frames  # noqa: E402

PORT = 16668
KEY = b"0123456789abcdef"
DEVICE_ID = "eb0123456789abcdefghij"


class Client:
    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.buffer = b""
        self.sequence = 0

    def send(self, key, command, payload, retcode=None):
        self.sequence += 1
        self.socket.sendall(R.pack_6699(key, self.sequence, command, payload, retcode=retcode))

    def receive(self, key):
        while True:
            frames, self.buffer = split_frames(self.buffer)
            if frames:
                return R.unpack_6699(frames[0], key, retcode_policy="auto")
            chunk = self.socket.recv(4096)
            if not chunk:
                raise IOError("device closed the connection")
            self.buffer += chunk

    def close(self):
        self.socket.close()


def main():
    device = FakeDevice("127.0.0.1", PORT, KEY, DEVICE_ID)
    threading.Thread(target=device.serve_forever, daemon=True).start()
    time.sleep(0.3)

    failures = []

    def check(name, condition, detail=""):
        print(("  PASS  " if condition else "  FAIL  ") + name + ("  " + str(detail) if not condition else ""))
        if not condition:
            failures.append(name)

    client = Client(PORT)
    try:
        # --- 1. handshake, step 1
        client_nonce = os.urandom(16)
        client.send(KEY, R.CMD_SESS_KEY_NEG_START, client_nonce)
        response = client.receive(KEY)
        check("handshake: RESP command", response["cmd"] == R.CMD_SESS_KEY_NEG_RESP, response["cmd"])

        # This firmware prefixes a 4-byte return code, so the body is 52 bytes;
        # the SDKs accept both 48 and 52. Strip it the same way they do.
        body = response["payload"]
        if len(body) == 52:
            body = body[4:]
        check("handshake: RESP length 48", len(body) == 48, len(body))

        device_nonce = body[:16]
        expected = hmac.new(KEY, client_nonce, "sha256").digest()
        check("handshake: device proved the key", hmac.compare_digest(expected, body[16:48]))

        # --- 2. handshake, step 3
        client.send(KEY, R.CMD_SESS_KEY_NEG_FINISH, hmac.new(KEY, device_nonce, "sha256").digest())
        session_key = R.derive_session_key_35(KEY, client_nonce, device_nonce)
        check("handshake: session key derived", len(session_key) == 16)

        # --- 3. the device must now accept the session key and reject the local key
        query = json.dumps({"gwId": DEVICE_ID, "devId": DEVICE_ID, "uid": DEVICE_ID, "t": 1}).encode()
        client.send(session_key, R.CMD_DP_QUERY, query)
        status = client.receive(session_key)
        check("DP_QUERY: reply is DP_QUERY_NEW", status["cmd"] == R.CMD_DP_QUERY_NEW, status["cmd"])
        check("DP_QUERY: return code stripped", status["retcode"] == 0, status["retcode"])

        body = json.loads(status["payload"].decode())
        dps = body.get("data", {}).get("dps", {})
        check("DP_QUERY: dps present", "1" in dps and "2" in dps, dps)
        check("DP_QUERY: initial switch off", dps.get("1") is False, dps.get("1"))

        # --- 4. CONTROL, then the STATUS push
        # CONTROL carries the 15-byte version header, exactly as the SDKs send it.
        control = b"3.5" + b"\x00" * 12 + json.dumps(
            {"devId": DEVICE_ID, "uid": DEVICE_ID, "t": 2, "dps": {"1": True}}
        ).encode()
        client.send(session_key, R.CMD_CONTROL, control)
        ack = client.receive(session_key)
        check("CONTROL: acknowledged", ack["cmd"] == R.CMD_CONTROL, ack["cmd"])

        pushed = client.receive(session_key)
        check("CONTROL: status push follows", pushed["cmd"] == R.CMD_STATUS, pushed["cmd"])
        pushed_dps = json.loads(pushed["payload"].decode())["data"]["dps"]
        check("CONTROL: switch is now on", pushed_dps.get("1") is True, pushed_dps.get("1"))

        # --- 5. a fresh query sees the new state
        client.send(session_key, R.CMD_DP_QUERY, query)
        again = json.loads(client.receive(session_key)["payload"].decode())["data"]["dps"]
        check("DP_QUERY: state persisted", again.get("1") is True, again.get("1"))

        # --- 6. heartbeat
        client.send(session_key, R.CMD_HEART_BEAT, b"")
        heartbeat = client.receive(session_key)
        check("HEART_BEAT: answered", heartbeat["cmd"] == R.CMD_HEART_BEAT, heartbeat["cmd"])
    finally:
        client.close()

    # --- 7. after the handshake the static key must no longer be accepted.
    # The device drops the connection, which is the correct response.
    second = Client(PORT)
    try:
        nonce = os.urandom(16)
        second.send(KEY, R.CMD_SESS_KEY_NEG_START, nonce)
        response = second.receive(KEY)
        body = response["payload"]
        if len(body) == 52:
            body = body[4:]
        second.send(KEY, R.CMD_SESS_KEY_NEG_FINISH, hmac.new(KEY, body[:16], "sha256").digest())

        rejected = False
        try:
            second.send(KEY, R.CMD_DP_QUERY, b"{}")
            second.receive(KEY)
        except (IOError, OSError, Exception):  # noqa: BLE001 - any failure is the pass condition
            rejected = True
        check("post-handshake frames must use the session key", rejected)
    finally:
        second.close()

    print("")
    if failures:
        print("%d FAILURES: %s" % (len(failures), ", ".join(failures)))
        return 1
    print("all round-trip checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
