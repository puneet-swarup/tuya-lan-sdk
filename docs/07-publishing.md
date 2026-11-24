# Publishing

Two packages: `TuyaLan` on NuGet, `com.tuyalan:tuya-lan` on Maven Central (or
GitHub Packages). Registration is a one-time account step; after that a tag is
all it takes.

## Before the first release

- [ ] `Version` in `csharp/src/TuyaLan/TuyaLan.csproj` and the version in
      `kotlin/tuya-lan/build.gradle.kts` agree, and match `CHANGELOG.md`.
- [ ] `LICENSE` carries your name.
- [ ] The `RepositoryUrl` and package metadata point at the real repository.
- [ ] `assets/icon.png` is 512×512 and reads well at 64 px.
- [ ] All three test suites are green.

## NuGet

### One-time setup: trusted publishing (recommended)

No API key is stored anywhere. GitHub issues a signed OIDC token describing this
repository and workflow file; nuget.org checks it against a policy and hands back
a key that lives for one hour.

1. Sign in at [nuget.org](https://www.nuget.org/) and create an account if you
   have not already.
2. Open your username menu → **Trusted Publishing** → **Create policy**.
3. Fill it in exactly:

   | field | value |
   |---|---|
   | Name | `GitHub Actions cd-release` |
   | Package Owner | your account |
   | Repository Owner | `puneet-swarup` |
   | Repository | `tuya-lan-sdk` |
   | Workflow File | `cd-release.yml` |
   | Environment | leave empty |

   The workflow file is the **file name only**, without `.github/workflows/`.
   Renaming the file breaks publishing until the policy is updated to match.
4. Add a repository secret `NUGET_USER` = your nuget.org **profile name**, not
   your email (Settings → Secrets and variables → Actions).

`.github/workflows/cd-release.yml` is already written for this: it requests
`id-token: write`, calls `NuGet/login@v1`, and pushes with the key that comes
back.

### One-time setup: API key (fallback)

If you would rather not use OIDC:

1. **Account → API keys → Create**.
2. Scope *Push new packages and package versions*; glob `TuyaLan` (the exact
   package id — `*` would let the key push any package you own).
3. Add the key as a repository secret named `NUGET_API_KEY`.
4. Change the last two steps of `cd-release.yml` back to a single push using
   `--api-key ${{ secrets.NUGET_API_KEY }}`.

### Releasing

```bash
git tag v2.0.0
git push origin v2.0.0
```

Then publish a release on GitHub for that tag (Releases → Draft a new release →
choose the tag → paste the release notes → Publish). The `cd-release.yml`
workflow builds, packs and pushes to NuGet with `--skip-duplicate`.

Keep it as a **draft** until the policy exists: publishing fires the workflow
immediately, and a job that fails on a live release is harder to reason about
than a draft that sits there.

To pack locally first and check what goes in:

```bash
dotnet pack csharp/src/TuyaLan/TuyaLan.csproj -c Release -o ./nupkg
unzip -l nupkg/TuyaLan.2.0.0.nupkg
```

The package should contain `lib/net10.0/TuyaLan.dll`, the XML docs, `README.md`
and `icon.png`.

## Maven Central

**Live.** `io.github.puneet-swarup:tuya-lan` is published — 3.2.0 was the first
release, and the pipeline does it automatically from then on.

### How it is wired

| piece | where |
|---|---|
| module | `kotlin/tuya-lan-core` — a plain JVM library, so Central gets a jar, not an AAR |
| plugin | `com.vanniktech.maven.publish` 0.37.0 |
| coordinates | `io.github.puneet-swarup:tuya-lan`, version from `VERSION_NAME` |
| POM metadata | name, description, URL, MIT licence, developer, SCM — Central rejects a POM missing any of it |
| signing | `signAllPublications()`, key from `signingInMemoryKey` + password |
| release | **manual** — the plugin uploads, Central validates, a human presses Publish on central.sonatype.com |

### Secrets the workflow needs

Set as repository secrets; the workflow maps them to the property names the
plugin reads.

| secret | plugin property |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` (portal **token** username, not your login) |
| `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` |
| `GPG_PRIVATE_KEY` | `signingInMemoryKey` (`gpg --armor --export-secret-keys <fingerprint>`) |
| `GPG_PASSPHRASE` | `signingInMemoryKeyPassword` |

### Cutting a release

1. Set `VERSION_NAME` in `kotlin/gradle.properties` to the new version.
2. Tag `v<same version>` and publish the GitHub release.
3. The workflow refuses to publish if the tag and `VERSION_NAME` disagree —
   Central never allows the same coordinates twice, so a mismatch must fail early.
4. It uploads, waits for Central's validation, and fails the build if the
   deployment is rejected.
5. Press **Publish** in the portal. Sync to Maven Central takes 10–30 minutes.

Once published, a version is permanent. A mistake means publishing the next
version, never re-uploading the same one.

## After the first publish

Add the version badges to `README.md`:

```markdown
[![NuGet](https://img.shields.io/nuget/v/TuyaLan)](https://www.nuget.org/packages/TuyaLan)
[![Maven Central](https://img.shields.io/maven-central/v/com.tuyalan/tuya-lan)](https://central.sonatype.com/artifact/com.tuyalan/tuya-lan)
```

They render as broken images until the packages exist, which is why they are not
in the README yet.
