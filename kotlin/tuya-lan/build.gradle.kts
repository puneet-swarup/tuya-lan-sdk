plugins {
    id("com.android.library")
}

// The library itself is pure JVM and lives in :tuya-lan-core, which produces the
// .jar a Java or desktop project consumes. This module exists only to package the
// same code as an .aar for Android, because the Android Gradle plugin cannot
// consume a plain .jar module as a library dependency of an app.

android {
    namespace = "com.tuyalan.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // The rules travel with the code they describe.
        consumerProguardFiles("../tuya-lan-core/consumer-rules.pro")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // api, not implementation: the classes must reach an app that depends on the
    // AAR, exactly as they did when the sources lived in this module.
    api(project(":tuya-lan-core"))
}
