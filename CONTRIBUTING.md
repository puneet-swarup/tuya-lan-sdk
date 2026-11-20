# Contributing

Thanks for taking the time. This is a small library with an unusual constraint —
it has to be right about a wire protocol — so the rules are mostly about
evidence.

## Getting set up

| what | needs |
|---|---|
| .NET client | [.NET 10 SDK](https://dotnet.microsoft.com/download) |
| Android client | JDK 17, Android SDK (compileSdk 36) |
| Python tools | Python 3.11+, `pip install -r tools/requirements.txt` |

## Running the tests

```bash
dotnet build dotnet/TuyaLan.sln
dotnet test  dotnet/TuyaLan.sln
```

```bash
cd android && ./gradlew :tuya-lan:test
```

```bash
python3 tools/reference_impl.py --selftest     # 19 checks: framing + signing
python3 tools/test_roundtrip.py                # 15 checks: handshake -> control
python3 tools/device_console.py --selftest     # 43 checks: everything, incl. envelopes
```

All three suites should be green before you open a pull request. CI runs the
same commands.

## Testing without hardware

`tools/fake_device.py` is a working 3.5 device simulator. Start it, then drive
either client at it:

```bash
python3 tools/fake_device.py --envelope modern
dotnet run --project dotnet/samples/TuyaLan.Demo -- toggle 127.0.0.1 eb0123456789abcdefghij 0123456789abcdef 3.5 1
```

`--envelope modern` makes the simulator reject the legacy control body, the way
real 3.4/3.5 firmware does. Keep it that way for control-path changes — the
permissive default cannot catch a regression.

For a real device, `tools/device_console.py` finds the shape a firmware accepts:

```bash
python3 tools/device_console.py --try --host 192.168.1.42 \
    --device-id eb0123456789abcdefghij --key 0123456789abcdef
```

## What a good change looks like

- **Bring evidence.** A firmware quirk needs a transcript from `device_console`
  or a log from the device. "It should work" is not enough — this protocol is
  full of firmware that disagrees with the documentation.
- **Keep the layers apart.** `TuyaFrameCodec` turns bytes into messages,
  `TuyaSession` owns keys and sequence numbers, `TuyaLocalDevice` owns the
  socket. Changes should sit in one of those, not leak across.
- **Both clients or neither.** C# and Kotlin are kept in step; a protocol change
  lands in both in the same pull request, plus the Python reference if the wire
  format moved.
- **Tests with the change.** Add to `tools/reference_impl.py --selftest` for
  framing, to `test_roundtrip.py` for session behaviour, or to
  `device_console.py --selftest` for control shapes.
- **Say what you did not verify.** Hardware coverage is uneven; claiming more
  than you tested is the one thing that really hurts here.

## Style

`.editorconfig` covers indentation and line endings; `.gitattributes` keeps the
repository LF. Public members get XML docs in C# and KDoc in Kotlin. Match the
comment style around you: explain *why* a line exists, especially where it works
around a firmware quirk.

## Commits and pull requests

Conventional-commit prefixes (`fix:`, `feat:`, `docs:`, `chore:`) are used in the
history. The pull request template asks which suites you ran — fill it in.

## Security

Do not open a public issue for a vulnerability. See [SECURITY.md](SECURITY.md).
Never paste a local key, device id or cloud secret into an issue, a test or a
commit.
