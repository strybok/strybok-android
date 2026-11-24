plugins {
    id("com.android.library")
}

group = "com.strybok"
version = "0.1.0"

android {
    namespace = "com.strybok.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all { test ->
            test.testLogging {
                events("failed", "skipped")
                showStandardStreams = false
            }
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Flow is part of the public API.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar only contains stubs of org.json; JVM unit tests need the real thing.
    testImplementation("org.json:json:20250517")
}
