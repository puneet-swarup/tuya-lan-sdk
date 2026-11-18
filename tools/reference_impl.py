#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Reference implementation of the Tuya LAN protocol (3.3 / 3.4 / 3.5) and the
Tuya Cloud API request signer.

This file is NOT the deliverable SDK. It exists so the C# and Kotlin
implementations can be checked against an independently executable model of
the same wire format, and so that the shared test vectors in
``docs/05-test-vectors.md`` / ``tools/test_vectors.json`` are generated from
one source of truth rather than typed by hand.

Usage:
    python3 reference_impl.py --selftest
    python3 reference_impl.py --emit-vectors tools/test_vectors.json

Protocol facts encoded here are documented in docs/01-protocol-3.5.md, which
cites the public sources they were derived from.
"""

import argparse
import binascii
import hashlib
import hmac
import json
import os
import struct
import sys

# --------------------------------------------------------------------------
# AES primitives
# --------------------------------------------------------------------------
try:  # pragma: no cover - environment dependent
    from cryptography.hazmat.primitives.ciphers import Cipher, modes
    from cryptography.hazmat.primitives.ciphers.algorithms import AES as _AESAlg

    _BACKEND = "cryptography"

    def _ecb(key, data, encrypt):
        c = Cipher(_AESAlg(key), modes.ECB())
        op = c.encryptor() if encrypt else c.decryptor()
        return op.update(data) + op.finalize()

    def _gcm_encrypt(key, iv, plaintext, aad):
        c = Cipher(_AESAlg(key), modes.GCM(iv))
        op = c.encryptor()
        if aad:
            op.authenticate_additional_data(aad)
        ct = op.update(plaintext) + op.finalize()
        return ct, op.tag

    def _gcm_decrypt(key, iv, ciphertext, tag, aad):
        c = Cipher(_AESAlg(key), modes.GCM(iv, tag))
        op = c.decryptor()
        if aad:
            op.authenticate_additional_data(aad)
        return op.update(ciphertext) + op.finalize()

except ImportError:  # pragma: no cover
    from Crypto.Cipher import AES as _AES

    _BACKEND = "pycryptodome"

    def _ecb(key, data, encrypt):
        cipher = _AES.new(key, _AES.MODE_ECB)
        return cipher.encrypt(data) if encrypt else cipher.decrypt(data)

    def _gcm_encrypt(key, iv, plaintext, aad):
        cipher = _AES.new(key, _AES.MODE_GCM, nonce=iv)
        if aad:
            cipher.update(aad)
        return cipher.encrypt_and_digest(plaintext)

    def _gcm_decrypt(key, iv, ciphertext, tag, aad):
        cipher = _AES.new(key, _AES.MODE_GCM, nonce=iv)
        if aad:
            cipher.update(aad)
        return cipher.decrypt_and_verify(ciphertext, tag)


# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------
PREFIX_55AA = b"\x00\x00\x55\xaa"
SUFFIX_55AA = b"\x00\x00\xaa\x55"
PREFIX_6699 = b"\x00\x00\x66\x99"
SUFFIX_6699 = b"\x00\x00\x99\x66"

HEADER_FMT_55AA = ">4I"          # prefix, seq, cmd, length
HEADER_FMT_6699 = ">IHIII"       # prefix, reserved, seq, cmd, length
END_FMT_55AA = ">2I"             # crc32, suffix
END_FMT_HMAC = ">32sI"           # hmac-sha256, suffix
END_FMT_6699 = ">16sI"           # gcm tag, suffix

HEADER_LEN_55AA = struct.calcsize(HEADER_FMT_55AA)   # 16
HEADER_LEN_6699 = struct.calcsize(HEADER_FMT_6699)   # 18

# Commands
CMD_SESS_KEY_NEG_START = 0x03
CMD_SESS_KEY_NEG_RESP = 0x04
CMD_SESS_KEY_NEG_FINISH = 0x05
CMD_CONTROL = 0x07
CMD_STATUS = 0x08
CMD_HEART_BEAT = 0x09
CMD_DP_QUERY = 0x0A
CMD_CONTROL_NEW = 0x0D
CMD_DP_QUERY_NEW = 0x10
CMD_UPDATEDPS = 0x12
CMD_UDP_NEW = 0x13
CMD_BOARDCAST_LPV34 = 0x23
CMD_REQ_DEVINFO = 0x25
CMD_LAN_EXT_STREAM = 0x40

# Commands that do NOT carry the 15-byte "3.x" version header.
NO_VERSION_HEADER_CMDS = {
    CMD_DP_QUERY,
    CMD_DP_QUERY_NEW,
    CMD_UPDATEDPS,
    CMD_HEART_BEAT,
    CMD_SESS_KEY_NEG_START,
    CMD_SESS_KEY_NEG_RESP,
    CMD_SESS_KEY_NEG_FINISH,
    CMD_LAN_EXT_STREAM,
}

# Well-known key that encrypts LAN discovery broadcasts (v3.3 AES-ECB and
# v3.5 AES-GCM alike). It is public by design - it only protects the
# discovery advertisement, never device control.
UDP_KEY = hashlib.md5(b"yGAdlopoPVldABfn").digest()

UDP_PORT_LEGACY = 6666
UDP_PORT_ENCRYPTED = 6667
UDP_PORT_APP = 7000
TCP_PORT = 6668


# --------------------------------------------------------------------------
# Padding / digests
# --------------------------------------------------------------------------
def pkcs7_pad(data, block=16):
    n = block - len(data) % block
    return data + bytes([n]) * n


def pkcs7_unpad(data, block=16):
    if not data or len(data) % block:
        raise ValueError("invalid padded length: %d" % len(data))
    n = data[-1]
    if n < 1 or n > block:
        raise ValueError("invalid padding length byte: %d" % n)
    if data[-n:] != bytes([n]) * n:
        raise ValueError("invalid padding data")
    return data[:-n]


def crc32(data):
    return binascii.crc32(data) & 0xFFFFFFFF


def version_header(version):
    """15-byte '3.x' header: 3 ASCII bytes + 12 zero bytes."""
    return version.encode("ascii") + b"\x00" * 12


# --------------------------------------------------------------------------
# 3.5 - 6699 frame with AES-128-GCM
# --------------------------------------------------------------------------
def pack_6699(key, seq, cmd, payload, retcode=None, iv=None, aad_override=None):
    """Build a complete 0x00006699 frame.

    ``payload`` is the plaintext (JSON) body. ``retcode`` is only included for
    device->client frames. Returns the frame bytes.
    """
    if len(key) != 16:
        raise ValueError("local key must be 16 bytes")
    raw = payload if retcode is None else struct.pack(">I", retcode) + payload
    length = 12 + len(raw) + 16
    header = struct.pack(HEADER_FMT_6699, 0x00006699, 0, seq, cmd, length)
    aad = header[4:] if aad_override is None else aad_override
    iv = iv if iv is not None else os.urandom(12)
    ct, tag = _gcm_encrypt(key, iv, raw, aad)
    return header + iv + ct + tag + SUFFIX_6699


def unpack_6699(frame, key, retcode_policy="auto"):
    """Parse a 0x00006699 frame. Returns a dict."""
    if frame[:4] != PREFIX_6699:
        raise ValueError("not a 6699 frame")
    prefix, reserved, seq, cmd, length = struct.unpack(
        HEADER_FMT_6699, frame[:HEADER_LEN_6699]
    )
    total = HEADER_LEN_6699 + length + 4
    if len(frame) < total:
        raise ValueError("truncated 6699 frame: need %d, have %d" % (total, len(frame)))
    if frame[total - 4:total] != SUFFIX_6699:
        raise ValueError("bad 6699 suffix")
    aad = frame[4:HEADER_LEN_6699]
    iv = frame[HEADER_LEN_6699:HEADER_LEN_6699 + 12]
    ct = frame[HEADER_LEN_6699 + 12:HEADER_LEN_6699 + length - 16]
    tag = frame[HEADER_LEN_6699 + length - 16:HEADER_LEN_6699 + length]
    raw = _gcm_decrypt(key, iv, ct, tag, aad)
    retcode, body = split_retcode(raw, retcode_policy)
    return {
        "prefix": prefix,
        "reserved": reserved,
        "seq": seq,
        "cmd": cmd,
        "length": length,
        "iv": iv,
        "retcode": retcode,
        "payload": body,
    }


def split_retcode(raw, policy="auto"):
    """Decide whether a decrypted body carries a leading 4-byte return code."""
    if policy == "always":
        return struct.unpack(">I", raw[:4])[0], raw[4:]
    if policy == "never":
        return None, raw
    # auto: a body that already starts with JSON or a 3.x header has no retcode
    if raw[:1] == b"{" or raw[3:4] == b"." or raw[:1] == b"[":
        return None, raw
    if len(raw) > 4 and (raw[4:5] in (b"{", b"[") or raw[7:8] == b"."):
        return struct.unpack(">I", raw[:4])[0], raw[4:]
    return None, raw


# --------------------------------------------------------------------------
# 3.3 / 3.4 - 55AA frame
# --------------------------------------------------------------------------
def pack_55aa(key, seq, cmd, payload, version, retcode=None, hmac_mode=False,
              header_mode="inside"):
    """Build a complete 0x000055AA frame.

    version 3.3 -> AES-128-ECB with the static local key, CRC32 integrity.
    version 3.4 -> AES-128-ECB with the session key, HMAC-SHA256 integrity.
    ``header_mode`` controls whether the 15-byte version header is encrypted
    with the body ("inside") or prepended in the clear ("prepended").
    """
    if len(key) != 16:
        raise ValueError("local key must be 16 bytes")
    body = payload
    if cmd not in NO_VERSION_HEADER_CMDS:
        body = version_header(version) + body
    ciphertext = _ecb(key, pkcs7_pad(body), True)
    clear_header = b""
    if cmd not in NO_VERSION_HEADER_CMDS and header_mode == "prepended":
        clear_header = version_header(version)
        ciphertext = _ecb(key, pkcs7_pad(payload), True)
    prefix_bytes = b""
    if retcode is not None:
        prefix_bytes = struct.pack(">I", retcode)
    end_len = struct.calcsize(END_FMT_HMAC if hmac_mode else END_FMT_55AA)
    length = len(prefix_bytes) + len(clear_header) + len(ciphertext) + end_len
    header = struct.pack(HEADER_FMT_55AA, 0x000055AA, seq, cmd, length)
    data = header + prefix_bytes + clear_header + ciphertext
    if hmac_mode:
        digest = hmac.new(key, data, hashlib.sha256).digest()
        return data + digest + SUFFIX_55AA
    return data + struct.pack(">I", crc32(data)) + SUFFIX_55AA


def unpack_55aa(frame, key, version, hmac_mode=False, retcode_policy="auto",
                header_mode="inside"):
    if frame[:4] != PREFIX_55AA:
        raise ValueError("not a 55AA frame")
    prefix, seq, cmd, length = struct.unpack(HEADER_FMT_55AA, frame[:HEADER_LEN_55AA])
    total = HEADER_LEN_55AA + length
    if len(frame) < total:
        raise ValueError("truncated 55AA frame")
    end_len = struct.calcsize(END_FMT_HMAC if hmac_mode else END_FMT_55AA)
    body_end = total - end_len
    retcode = None
    if retcode_policy != "never":
        retcode = struct.unpack(">I", frame[HEADER_LEN_55AA:HEADER_LEN_55AA + 4])[0]
        body_start = HEADER_LEN_55AA + 4
    else:
        body_start = HEADER_LEN_55AA
    ciphertext = frame[body_start:body_end]
    if hmac_mode:
        expect = hmac.new(key, frame[:body_end], hashlib.sha256).digest()
        if expect != frame[body_end:body_end + 32]:
            raise ValueError("HMAC mismatch")
    else:
        expect = struct.unpack(">I", frame[body_end:body_end + 4])[0]
        if expect != crc32(frame[:body_end]):
            raise ValueError("CRC32 mismatch")
    if frame[total - 4:total] != SUFFIX_55AA:
        raise ValueError("bad 55AA suffix")
    body = pkcs7_unpad(_ecb(key, ciphertext, False))
    if header_mode == "inside" and cmd not in NO_VERSION_HEADER_CMDS and len(body) > 15:
        body = body[15:]
    return {
        "prefix": prefix,
        "seq": seq,
        "cmd": cmd,
        "length": length,
        "retcode": retcode,
        "payload": body,
    }


# --------------------------------------------------------------------------
# Session key negotiation (3.4 / 3.5)
# --------------------------------------------------------------------------
def derive_session_key_35(real_key, client_nonce, device_nonce):
    """3.5: GCM-encrypt xor(nonces) with the real key, IV = client_nonce[:12],
    and take the 16-byte ciphertext (bytes 12..28 of iv||ct||tag)."""
    tmp = bytes(a ^ b for a, b in zip(client_nonce, device_nonce))
    ct, _tag = _gcm_encrypt(real_key, client_nonce[:12], tmp, b"")
    return ct


def derive_session_key_34(real_key, client_nonce, device_nonce):
    """3.4: AES-ECB-encrypt xor(nonces) with the real key."""
    tmp = bytes(a ^ b for a, b in zip(client_nonce, device_nonce))
    return _ecb(real_key, tmp, True)


def neg_hmac(real_key, nonce):
    return hmac.new(real_key, nonce, hashlib.sha256).digest()


# --------------------------------------------------------------------------
# Cloud API signer
# --------------------------------------------------------------------------
def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def build_string_to_sign(method, body_bytes, signature_headers, url):
    """stringToSign = METHOD \\n SHA256(body) \\n optional-key-lines \\n URL"""
    optional = ""
    for key, value in signature_headers:
        optional += "%s:%s\n" % (key, value)
    return "%s\n%s\n%s\n%s" % (method.upper(), sha256_hex(body_bytes or b""), optional, url)


def sign_token_request(client_id, secret, t, nonce, string_to_sign):
    """Token management APIs: str = client_id + t + nonce + stringToSign"""
    payload = "%s%s%s%s" % (client_id, t, nonce, string_to_sign)
    return hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest().upper()


def sign_business_request(client_id, access_token, secret, t, nonce, string_to_sign):
    """Business APIs: str = client_id + access_token + t + nonce + stringToSign"""
    payload = "%s%s%s%s%s" % (client_id, access_token, t, nonce, string_to_sign)
    return hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest().upper()


# --------------------------------------------------------------------------
# Self test
# --------------------------------------------------------------------------
TEST_KEY = b"0123456789abcdef"
TEST_IV = b"0123456789ab"
TEST_JSON = b'{"devId":"eb0123456789abcdefghij","t":1700000000,"dps":{"1":true}}'


def selftest():
    failures = []

    def check(name, cond, detail=""):
        if cond:
            print("  PASS  %s" % name)
        else:
            print("  FAIL  %s %s" % (name, detail))
            failures.append(name)

    print("AES backend: %s" % _BACKEND)

    # --- 6699 round trip
    frame = pack_6699(TEST_KEY, 7, CMD_CONTROL, TEST_JSON, iv=TEST_IV)
    check("6699 frame length field", struct.unpack(HEADER_FMT_6699, frame[:18])[4] == 12 + len(TEST_JSON) + 16)
    check("6699 total size", len(frame) == 18 + 12 + len(TEST_JSON) + 16 + 4)
    parsed = unpack_6699(frame, TEST_KEY)
    check("6699 payload round trip", parsed["payload"] == TEST_JSON, parsed["payload"])
    check("6699 seq/cmd", parsed["seq"] == 7 and parsed["cmd"] == CMD_CONTROL)
    check("6699 no retcode", parsed["retcode"] is None)

    # --- 6699 with retcode
    frame_rc = pack_6699(TEST_KEY, 8, CMD_STATUS, TEST_JSON, retcode=0, iv=TEST_IV)
    parsed_rc = unpack_6699(frame_rc, TEST_KEY, "auto")
    check("6699 retcode stripped", parsed_rc["retcode"] == 0 and parsed_rc["payload"] == TEST_JSON)

    # --- tag tampering must fail
    bad = bytearray(frame)
    bad[30] ^= 0xFF
    try:
        unpack_6699(bytes(bad), TEST_KEY)
        check("6699 tag rejects tampering", False)
    except Exception:
        check("6699 tag rejects tampering", True)

    # --- wrong key must fail
    try:
        unpack_6699(frame, b"fedcba9876543210")
        check("6699 wrong key rejected", False)
    except Exception:
        check("6699 wrong key rejected", True)

    # --- 3.4 round trip
    f34 = pack_55aa(TEST_KEY, 3, CMD_CONTROL, TEST_JSON, "3.4", hmac_mode=True)
    p34 = unpack_55aa(f34, TEST_KEY, "3.4", hmac_mode=True, retcode_policy="never")
    check("3.4 hmac round trip", p34["payload"] == TEST_JSON, p34["payload"])

    # --- 3.3 round trip
    f33 = pack_55aa(TEST_KEY, 4, CMD_DP_QUERY, TEST_JSON, "3.3")
    p33 = unpack_55aa(f33, TEST_KEY, "3.3", retcode_policy="never")
    check("3.3 crc round trip", p33["payload"] == TEST_JSON, p33["payload"])

    # --- version header present for CONTROL, absent for DP_QUERY
    check("3.3 control has version header",
          unpack_55aa(f33, TEST_KEY, "3.3", retcode_policy="never")["payload"] == TEST_JSON)
    f33c = pack_55aa(TEST_KEY, 5, CMD_CONTROL, TEST_JSON, "3.3")
    p33c = unpack_55aa(f33c, TEST_KEY, "3.3", retcode_policy="never")
    check("3.3 control body stripped of header", p33c["payload"] == TEST_JSON, p33c["payload"])

    # --- session key derivation is symmetric / deterministic
    cn = b"0123456789abcdef"
    dn = b"fedcba9876543210"
    sk = derive_session_key_35(TEST_KEY, cn, dn)
    check("3.5 session key is 16 bytes", len(sk) == 16)
    check("3.5 session key deterministic", sk == derive_session_key_35(TEST_KEY, cn, dn))
    check("3.4 session key differs from 3.5", derive_session_key_34(TEST_KEY, cn, dn) != sk)

    # --- handshake HMAC
    check("neg hmac length", len(neg_hmac(TEST_KEY, cn)) == 32)

    # --- cloud signer against the published Tuya example
    # https://developer.tuya.com/en/docs/iot/new-singnature
    client_id = "1KAD46OrT9HafiKdsXeg"
    secret = "4OHBOnWOqaEC1mWXOpVL3yV50s0qGSRC"
    t = "1588925778000"
    nonce = "5138cc3a9033d69856923fd07b491173"
    sts = ("GET\n"
           "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n"
           "area_id:29a33e8796834b1efa6\n"
           "call_id:8afdb70ab2ed11eb85290242ac130003\n"
           "\n"
           "/v1.0/token?grant_type=1")
    got = sign_token_request(client_id, secret, t, nonce, sts).lower()
    want = "9e48a3e93b302eeecc803c7241985d0a34eb944f40fb573c7b5c2a82158af13e"
    check("cloud token sign matches published vector", got == want, got)

    built = build_string_to_sign(
        "GET", b"",
        [("area_id", "29a33e8796834b1efa6"), ("call_id", "8afdb70ab2ed11eb85290242ac130003")],
        "/v1.0/token?grant_type=1")
    check("stringToSign construction matches", built == sts, repr(built))

    print("")
    if failures:
        print("%d FAILURES: %s" % (len(failures), ", ".join(failures)))
        return 1
    print("all checks passed")
    return 0


# --------------------------------------------------------------------------
# Test vector export
# --------------------------------------------------------------------------
def emit_vectors(path):
    vectors = {
        "_comment": "Generated by tools/reference_impl.py. Consumed by the C# and Kotlin test suites.",
        "key_hex": TEST_KEY.hex(),
        "iv_hex": TEST_IV.hex(),
        "udp_key_hex": UDP_KEY.hex(),
        "plaintext_hex": TEST_JSON.hex(),
        "plaintext_utf8": TEST_JSON.decode(),
        "crc32": {
            "input_hex": TEST_JSON.hex(),
            "expected": crc32(TEST_JSON),
        },
        "frame_6699_control": {
            "seq": 7,
            "cmd": CMD_CONTROL,
            "frame_hex": pack_6699(TEST_KEY, 7, CMD_CONTROL, TEST_JSON, iv=TEST_IV).hex(),
        },
        "frame_6699_status_with_retcode": {
            "seq": 8,
            "cmd": CMD_STATUS,
            "retcode": 0,
            "frame_hex": pack_6699(TEST_KEY, 8, CMD_STATUS, TEST_JSON, retcode=0, iv=TEST_IV).hex(),
        },
        "frame_55aa_v34": {
            "seq": 3,
            "cmd": CMD_CONTROL,
            "frame_hex": pack_55aa(TEST_KEY, 3, CMD_CONTROL, TEST_JSON, "3.4", hmac_mode=True).hex(),
        },
        "frame_55aa_v33": {
            "seq": 4,
            "cmd": CMD_DP_QUERY,
            "frame_hex": pack_55aa(TEST_KEY, 4, CMD_DP_QUERY, TEST_JSON, "3.3").hex(),
        },
        "session_key_35": {
            "real_key_hex": TEST_KEY.hex(),
            "client_nonce_hex": b"0123456789abcdef".hex(),
            "device_nonce_hex": b"fedcba9876543210".hex(),
            "session_key_hex": derive_session_key_35(TEST_KEY, b"0123456789abcdef", b"fedcba9876543210").hex(),
        },
        "session_key_34": {
            "real_key_hex": TEST_KEY.hex(),
            "client_nonce_hex": b"0123456789abcdef".hex(),
            "device_nonce_hex": b"fedcba9876543210".hex(),
            "session_key_hex": derive_session_key_34(TEST_KEY, b"0123456789abcdef", b"fedcba9876543210").hex(),
        },
        "cloud_sign_token": {
            "client_id": "1KAD46OrT9HafiKdsXeg",
            "secret": "4OHBOnWOqaEC1mWXOpVL3yV50s0qGSRC",
            "t": "1588925778000",
            "nonce": "5138cc3a9033d69856923fd07b491173",
            "string_to_sign": "GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\narea_id:29a33e8796834b1efa6\ncall_id:8afdb70ab2ed11eb85290242ac130003\n\n/v1.0/token?grant_type=1",
            "expected_sign": "9E48A3E93B302EEECC803C7241985D0A34EB944F40FB573C7B5C2A82158AF13E",
        },
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(vectors, fh, indent=2)
        fh.write("\n")
    print("wrote %s" % path)


def main(argv):
    ap = argparse.ArgumentParser(description="Tuya LAN protocol reference implementation")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--emit-vectors", metavar="PATH")
    args = ap.parse_args(argv)
    rc = 0
    if args.selftest or not args.emit_vectors:
        rc = selftest()
    if args.emit_vectors:
        emit_vectors(args.emit_vectors)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
