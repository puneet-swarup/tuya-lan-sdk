plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

// Pure JVM: the library has no Android APIs in it - no `import android.*`
// anywhere - so the same sources serve a Java product, a Kotlin desktop app and,
// through :tuya-lan, an Android app.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // On Android, org.json ships with the platform; on a plain JVM it does not,
    // and the cloud client needs it. This is the one dependency the AAR never
    // had to declare.
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "tuya-lan"
        }
    }
}
