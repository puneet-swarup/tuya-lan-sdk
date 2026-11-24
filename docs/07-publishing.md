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

There is no `maven-publish` block in `kotlin/tuya-lan/build.gradle.kts` yet —
add the one below when you are ready. It is inert until a publish task runs, so
it is safe to commit early.

```kotlin
// kotlin/tuya-lan/build.gradle.kts
plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.tuyalan"
                artifactId = "tuya-lan"
                version = "2.0.0"

                pom {
                    name.set("Tuya LAN SDK for Android")
                    description.set("Tuya LAN protocol (3.3 / 3.4 / 3.5) and cloud API client.")
                    url.set("https://github.com/puneet-swarup/tuya-lan-sdk")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("puneet-swarup")
                            name.set("Puneet Swarup")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/puneet-swarup/tuya-lan-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/puneet-swarup/tuya-lan-sdk.git")
                        url.set("https://github.com/puneet-swarup/tuya-lan-sdk")
                    }
                }
            }
        }
    }
}
```

### One-time setup for Central

Maven Central does not take uploads directly any more; it goes through the
Central Portal.

1. Create a namespace at [central.sonatype.com](https://central.sonatype.com/)
   and verify it (a GitHub repository under your account is the usual proof for
   `io.github.<user>`).
2. Generate a user token in the portal.
3. Add `MAVEN_USERNAME` and `MAVEN_PASSWORD` as repository secrets.
4. Add signing: Central requires every artifact to be GPG-signed. Export a key,
   add `SIGNING_KEY` (ASCII-armoured) and `SIGNING_PASSWORD` as secrets, and
   apply `signing` to the publication.

A simpler first step, if you want something published today: GitHub Packages
accepts the same `maven-publish` block pointed at
`https://maven.pkg.github.com/puneet-swarup/tuya-lan-sdk`, authenticated with
`GITHUB_TOKEN`. No namespace or signing required.

### Releasing

```bash
cd android
./gradlew :tuya-lan:publishReleasePublicationToMavenLocal   # smoke test
./gradlew :tuya-lan:publish                                 # to the configured repository
```

## After the first publish

Add the version badges to `README.md`:

```markdown
[![NuGet](https://img.shields.io/nuget/v/TuyaLan)](https://www.nuget.org/packages/TuyaLan)
[![Maven Central](https://img.shields.io/maven-central/v/com.tuyalan/tuya-lan)](https://central.sonatype.com/artifact/com.tuyalan/tuya-lan)
```

They render as broken images until the packages exist, which is why they are not
in the README yet.
