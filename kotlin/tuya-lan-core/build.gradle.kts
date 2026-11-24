plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

// The library itself. Pure JVM: there is no `import android.*` anywhere in it, so
// the same sources serve a Java product, a Kotlin desktop app and - through
// :tuya-lan - an Android app.

group = "io.github.puneet-swarup"
version = providers.gradleProperty("VERSION_NAME").getOrElse("3.2.0")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // On Android org.json ships with the platform; on a plain JVM it does not.
    api("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

mavenPublishing {
    coordinates("io.github.puneet-swarup", "tuya-lan", version.toString())

    // Manual release: the plugin uploads and Central validates, then somebody
    // presses Publish on the portal. Nothing appears publicly without that click.
    publishToMavenCentral()

    // Central refuses unsigned artefacts.
    signAllPublications()

    pom {
        name.set("Tuya LAN SDK")
        description.set(
            "Control Tuya smart plugs and switches on your own network - no cloud, " +
                "no account. Protocol 3.3, 3.4 and 3.5, with a Java-first API."
        )
        url.set("https://github.com/puneet-swarup/tuya-lan-sdk")
        licenses {
            license {
                name.set("MIT")
                url.set("https://github.com/puneet-swarup/tuya-lan-sdk/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("puneet-swarup")
                name.set("Puneet Swarup")
                email.set("puneetswarup@gmail.com")
                url.set("https://github.com/puneet-swarup")
            }
        }
        scm {
            url.set("https://github.com/puneet-swarup/tuya-lan-sdk")
            connection.set("scm:git:git://github.com/puneet-swarup/tuya-lan-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/puneet-swarup/tuya-lan-sdk.git")
        }
    }
}
