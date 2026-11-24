plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    // The JVM module needs its own Kotlin plugin version: AGP brings Kotlin for
    // the Android modules, but a plain kotlin("jvm") module does not get it.
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
}
