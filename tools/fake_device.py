#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
A fake Tuya 3.5 device, for testing the C# and Kotlin libraries without hardware.

It speaks the real protocol: 0x00006699 frames, AES-128-GCM, the three-message
session key handshake, DP_QUERY / CONTROL / HEART_BEAT, and it pushes a STATUS
frame when a data point changes. Point the SDK at 127.0.0.1:6668 with the key
below and the full connect/getStatus/setDp path runs end to end.

    python3 tools/fake_device.py
    python3 tools/fake_device.py --port 6668 --key 0123456789abcdef

Then, from the C# demo:

    dotnet run --project dotnet/samples/TuyaLan.Demo -- \
        status 127.0.0.1 eb0123456789abcdefghij 0123456789abcdef 3.5

On Android the emulator reaches the host at 10.0.2.2.

Note: this simulator is deliberately permissive - it accepts either a return
code or none, and it acknowledges every command. It is a protocol sanity check,
not a conformance suite.
"""

import argparse
import hmac
import json
import os
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reference_impl as R  # noqa: E402


DEFAULT_KEY = b"0123456789abcdef"
DEFAULT_DEVICE_ID = "eb0123456789abcdefghij"
DEFAULT_PRODUCT_KEY = "keydeadbeef12345"


def split_frames(buffer):
    """Pull every complete frame out of buffer. Returns (frames, remainder)."""
    frames = []
    while True:
        if len(buffer) < 4:
            return frames, buffer
        prefix = buffer[:4]
        if prefix == R.PREFIX_6699:
            if len(buffer) < 18:
                return frames, buffer
            length = struct.unpack(">I", buffer[14:18])[0]
            total = 18 + length + 4
        elif prefix == R.PREFIX_55AA:
            if len(buffer) < 16:
                return frames, buffer
            length = struct.unpack(">I", buffer[12:16])[0]
            total = 16 + length
        else:
            raise ValueError("unknown prefix %r" % prefix)
        if len(buffer) < total:
            return frames, buffer
        frames.append(buffer[:total])
        buffer = buffer[total:]


class FakeDevice:
    def __init__(self, host="0.0.0.0", port=6668, real_key=DEFAULT_KEY,
                 device_id=DEFAULT_DEVICE_ID, product_key=DEFAULT_PRODUCT_KEY):
        self.host = host
        self.port = port
        self.real_key = real_key
        self.device_id = device_id
        self.product_key = product_key
        self.dps = {"1": False, "2": 500, "3": "white", "20": True}
        self.lock = threading.Lock()

    # ---------------------------------------------------------------- serving

    def serve_forever(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((self.host, self.port))
        server.listen(4)
        print("fake Tuya 3.5 device listening on %s:%d" % (self.host, self.port))
        print("  device id : %s" % self.device_id)
        print("  local key : %s" % self.real_key.decode("utf-8", "replace"))
        print("  data points: %s" % json.dumps(self.dps))
        print("")

        while True:
            connection, address = server.accept()
            print("client connected from %s:%d" % address)
            thread = threading.Thread(target=self.handle, args=(connection, address), daemon=True)
            thread.start()

    def handle(self, connection, address):
        state = {"key": self.real_key, "client_nonce": None, "device_nonce": None, "seq": 1}
        buffer = b""
        connection.settimeout(120)

        try:
            while True:
                chunk = connection.recv(4096)
                if not chunk:
                    break
                buffer += chunk
                frames, buffer = split_frames(buffer)
                for frame in frames:
                    self.handle_frame(connection, state, frame)
        except (ConnectionResetError, socket.timeout, OSError):
            pass
        except Exception as exc:  # noqa: BLE001 - a simulator should report, not die
            print("  error: %s: %s" % (type(exc).__name__, exc))
        finally:
            connection.close()
            print("client %s:%d disconnected" % address)

    # ------------------------------------------------------------- protocol

    def handle_frame(self, connection, state, frame):
        message = R.unpack_6699(frame, state["key"], retcode_policy="auto")
        command = message["cmd"]
        payload = message["payload"]
        name = COMMAND_NAMES.get(command, "0x%02X" % command)
        print("  <- %s (%d bytes)" % (name, len(payload)))

        if command == R.CMD_SESS_KEY_NEG_START:
            state["client_nonce"] = payload[:16]
            state["device_nonce"] = os.urandom(16)
            response = state["device_nonce"] + hmac.new(
                self.real_key, state["client_nonce"], "sha256"
            ).digest()
            self.send(connection, state, R.CMD_SESS_KEY_NEG_RESP, response)
            print("  -> session key response")
            return

        if command == R.CMD_SESS_KEY_NEG_FINISH:
            expected = hmac.new(self.real_key, state["device_nonce"], "sha256").digest()
            if not hmac.compare_digest(expected, payload):
                print("  !! client failed to prove the local key; closing")
                connection.close()
                return
            state["key"] = R.derive_session_key_35(
                self.real_key, state["client_nonce"], state["device_nonce"]
            )
            print("  -> handshake complete, session key %s" % state["key"].hex())
            return

        if command == R.CMD_HEART_BEAT:
            self.send(connection, state, R.CMD_HEART_BEAT, b"")
            return

        if command in (R.CMD_DP_QUERY, R.CMD_DP_QUERY_NEW):
            self.send(connection, state, R.CMD_DP_QUERY_NEW, self.status_payload())
            return

        if command in (R.CMD_CONTROL, R.CMD_CONTROL_NEW):
            self.apply_control(payload)
            self.send(connection, state, R.CMD_CONTROL, b"")
            self.push_status(connection, state)
            return

        print("  -> unhandled command, replying 'json obj data unvalid'")
        self.send(connection, state, R.CMD_DP_QUERY_NEW, b"json obj data unvalid")

    @staticmethod
    def strip_envelope(payload):
        """Drop an optional return code and an optional 15-byte version header.

        Order matters: the version header starts with "3.", not "{", so a naive
        return-code strip would eat its first four bytes.
        """
        versions = (b"3.3", b"3.4", b"3.5")
        if payload[:3] in versions:
            return payload[15:]
        if len(payload) > 7 and payload[4:7] in versions:
            return payload[19:]
        if payload[:1] != b"{":
            return payload[4:]
        return payload

    def apply_control(self, payload):
        body = self.strip_envelope(payload)
        try:
            data = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            print("  !! could not parse control payload: %r" % body[:64])
            return

        with self.lock:
            for key, value in data.get("dps", {}).items():
                self.dps[str(key)] = value
        print("  dp update: %s" % json.dumps(data.get("dps", {})))

    def status_payload(self):
        with self.lock:
            snapshot = dict(self.dps)
        return json.dumps({
            "devId": self.device_id,
            "t": int(time.time()),
            "data": {"dps": snapshot},
        }).encode("utf-8")

    def push_status(self, connection, state):
        self.send(connection, state, R.CMD_STATUS, self.status_payload())

    def send(self, connection, state, command, payload):
        frame = R.pack_6699(state["key"], state["seq"], command, payload, retcode=0)
        state["seq"] += 1
        connection.sendall(frame)


COMMAND_NAMES = {
    R.CMD_SESS_KEY_NEG_START: "SESS_KEY_NEG_START",
    R.CMD_SESS_KEY_NEG_RESP: "SESS_KEY_NEG_RESP",
    R.CMD_SESS_KEY_NEG_FINISH: "SESS_KEY_NEG_FINISH",
    R.CMD_CONTROL: "CONTROL",
    R.CMD_STATUS: "STATUS",
    R.CMD_HEART_BEAT: "HEART_BEAT",
    R.CMD_DP_QUERY: "DP_QUERY",
    R.CMD_CONTROL_NEW: "CONTROL_NEW",
    R.CMD_DP_QUERY_NEW: "DP_QUERY_NEW",
}


def main(argv):
    parser = argparse.ArgumentParser(description="Fake Tuya 3.5 device")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=6668)
    parser.add_argument("--key", default=DEFAULT_KEY.decode())
    parser.add_argument("--device-id", default=DEFAULT_DEVICE_ID)
    args = parser.parse_args(argv)

    key = args.key.encode("utf-8")
    if len(key) != 16:
        parser.error("--key must be exactly 16 characters")

    FakeDevice(args.host, args.port, key, args.device_id).serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
