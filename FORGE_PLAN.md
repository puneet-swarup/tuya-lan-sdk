# Forge Plan — Tuya LAN SDK

## Goal
Maintain the cross-platform Tuya LAN SDK for .NET 10 and Android/Kotlin, with reproducible protocol validation and clearly documented hardware-verification status.

## Progress
- [x] Review repository, branch, remote, baseline commit, and working-tree changes.
- [x] Update .NET projects and CI/CD workflow to .NET 10.
- [x] Update Android compile/target SDK compatibility to API 36.
- [x] Update README and C# guide framework references; repository search found no remaining .NET 8/net8.0 references.
- [x] Run .NET Release tests: 24 passed, 0 failed, 0 skipped.
- [x] Run Android library unit-test task, release AAR assembly, and sample-app debug APK assembly: BUILD SUCCESSFUL.
- [x] Run Python protocol reference self-test: 19 checks passed.
- [x] Run simulated-device protocol round trips, including session negotiation, DP_QUERY, CONTROL, status push, heartbeat, and invalid session-key rejection: passed.
- [x] Run git diff --check: no whitespace errors.
- [x] Build NuGet package successfully: TuyaLan.1.0.1.nupkg.
- [ ] Review final staged changes and commit only intended SDK files and this plan.
- [ ] Push main and verify remote commit and GitHub Actions results.
- [ ] Verify repository description, topics, visibility, access, and Actions secrets; GitHub CLI is unavailable in the current terminal.
- [ ] Choose a new NuGet package version before any future release; existing tag v1.0.1 matches current package version 1.0.1. Do not republish this version.
- [ ] Decide/configure Android AAR publication target and credentials; current release workflow publishes NuGet only.
- [ ] Verify NuGet publishing secret NUGET_API_KEY through GitHub settings; never display or retrieve its value.
- [ ] Test against physical Tuya hardware and record device model, firmware, protocol version, and device-specific DP behavior.

## Verified repository state
- Repository: D:\puneet\Projects\tuya-lan-sdk
- Branch: main
- Baseline commit: dc16db97222201d81d6bfc8325f7760f533ef642
- Remote origin: https://github.com/puneet-swarup/tuya-lan-sdk.git
- At last remote check, origin/main still pointed to baseline commit dc16db97222201d81d6bfc8325f7760f533ef642.
- Existing release tag: v1.0.1.
- Working-tree SDK changes are not yet committed or pushed.
- NuGet package build succeeded for version 1.0.1. Because that version is already tagged, do not publish it as a new release.
- GitHub CLI was not found on PATH; repository settings, secrets, and Actions runs have not been verified.
- Automated simulator/build tests passed locally. Physical hardware compatibility remains unverified.

## Scope and safety
- Only modify this Tuya LAN SDK repository.
- Preserve all pre-existing user changes.
- Root-level untracked browser/runtime artifacts are not SDK source and must not be staged or committed. Keep SESSION_CONTEXT.md and other unrelated untracked files out unless their purpose is explicitly confirmed.
- Do not publish secrets, local keys, or contents of local configuration files.
- Do not change repository visibility or collaborator permissions without explicit approval.
- Do not publish NuGet packages, create release tags, or claim hardware verification without the required authorization/evidence.
