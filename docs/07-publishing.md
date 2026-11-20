# Publishing

Two packages: `TuyaLan` on NuGet, `com.tuyalan:tuya-lan` on Maven Central (or
GitHub Packages). Registration is a one-time account step; after that a tag is
all it takes.

## Before the first release

- [ ] `Version` in `dotnet/src/TuyaLan/TuyaLan.csproj` and the version in
      `android/tuya-lan/build.gradle.kts` agree, and match `CHANGELOG.md`.
- [ ] `LICENSE` carries your name.
- [ ] The `RepositoryUrl` and package metadata point at the real repository.
- [ ] `assets/icon.png` is 512×512 and reads well at 64 px.
- [ ] All three test suites are green.

## NuGet

### One-time setup

1. Create an account at [nuget.org](https://www.nuget.org/) and sign in.
2. **Account → API keys → Create**, scope *Push new packages and package versions*,
   glob `TuyaLan`, expiry as you prefer.
3. Add the key as a repository secret named `NUGET_API_KEY`
   (Settings → Secrets and variables → Actions). `.github/workflows/cd-release.yml`
   already reads it.

### Releasing

```bash
git tag v2.0.0
git push origin v2.0.0
```

Then publish a release on GitHub for that tag. The `cd-release.yml` workflow
builds, packs and pushes to NuGet with `--skip-duplicate`.

To pack locally first and check what goes in:

```bash
dotnet pack dotnet/src/TuyaLan/TuyaLan.csproj -c Release -o ./nupkg
unzip -l nupkg/TuyaLan.2.0.0.nupkg
```

The package should contain `lib/net10.0/TuyaLan.dll`, the XML docs, `README.md`
and `icon.png`.

## Maven Central

There is no `maven-publish` block in `android/tuya-lan/build.gradle.kts` yet —
add the one below when you are ready. It is inert until a publish task runs, so
it is safe to commit early.

```kotlin
// android/tuya-lan/build.gradle.kts
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
