plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

group = "com.strybok"
version = "0.4.0"

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

    // Only PlayIntegrityTokenProvider uses it, and only to compile: the published artifact does
    // not depend on Google's libraries. An app that wants attestation adds the library itself.
    compileOnly("com.google.android.play:integrity:1.4.0")

    // Only the install referrer sources of deferred links use these two, and only to compile:
    // an app that wants a source adds its store's library itself.
    compileOnly("com.android.installreferrer:installreferrer:2.2")
    compileOnly("store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer:4.0.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar only contains stubs of org.json; JVM unit tests need the real thing.
    testImplementation("org.json:json:20250517")
}

// Publishing to Maven Central. Credentials and the signing key come from Gradle properties
// (ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password, ...signingInMemoryKey / ...KeyPassword),
// which only the release workflow has; without them `publishToMavenLocal` still works, unsigned.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("com.strybok", "strybok-android", version.toString())
    pom {
        name.set("Strybok Android SDK")
        description.set("Receives Android App Links, resolves them through the Strybok API to an in-app route and payload, and delivers each link exactly once.")
        url.set("https://strybok.com")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("strybok")
                name.set("Strybok")
                url.set("https://strybok.com")
            }
        }
        scm {
            url.set("https://github.com/strybok/strybok-android")
            connection.set("scm:git:https://github.com/strybok/strybok-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/strybok/strybok-android.git")
        }
    }
}
