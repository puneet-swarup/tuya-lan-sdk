plugins {
    id("com.android.library")
}

// Packaging only: the code lives in :tuya-lan-core, which produces the .jar a
// Java or desktop project consumes. This module exists so Android apps get an
// .aar, which is the only thing the Android Gradle plugin can consume.

android {
    namespace = "com.tuyalan.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    // api, not implementation: every class must reach an app that depends on the AAR.
    api(project(":tuya-lan-core"))
}
