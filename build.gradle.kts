plugins {
    // Kotlin support is built into the Android Gradle plugin since 9.0,
    // so no separate Kotlin plugin is applied.
    id("com.android.library") version "9.0.1" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
