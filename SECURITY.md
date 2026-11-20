# Security policy

## Reporting a vulnerability

Use GitHub's private reporting: **Security → Advisories → Report a vulnerability**
on this repository. That opens a private thread with the maintainer.

Please do not open a public issue, and do not include a working exploit against
someone else's device.

Expect an acknowledgement within a few days. This is a single-maintainer project,
so please allow reasonable time before disclosing publicly.

## What is in scope

- The wire protocol implementation: framing, crypto, session-key negotiation.
- The cloud client: request signing, token handling.
- Anything that could leak a credential — local keys, cloud secrets, access tokens.

## What is out of scope

- Devices you do not own. Use this on hardware you control.
- Physical or network access an attacker already has. If someone is on your LAN,
  the local key is not your only problem.
- Tuya's own cloud or firmware.

## Credentials this library handles

| secret | where it lives | notes |
|---|---|---|
| Local key (16 chars) | your configuration | decrypts every frame to the device; treat it like a password |
| Cloud access id / secret | your configuration | signs cloud requests |
| Access token | memory, and whatever you do with it | short-lived |

The library never logs a key and never sends one over the network — the local key
stays on the LAN and the session key is derived per connection. It does not
encrypt anything at rest, so do not commit configuration containing a key.

If you believe a key has leaked, rotate it: re-pair the device or regenerate the
cloud project's authorisation key.

## Supported versions

The latest release on `main` is supported. Fixes for security issues are released
as a patch version and noted in [CHANGELOG.md](CHANGELOG.md).
