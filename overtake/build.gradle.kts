// SPDX-License-Identifier: AGPL-3.0-or-later
// The Overtake library module: com.android.library, compiled by AGP 9's built-in Kotlin (2.2.0).
// Dependencies are intentionally minimal — androidx.core + kotlinx-coroutines only — so a host app
// pulls in nothing heavy. The reader engine itself is pure Android framework + coroutines StateFlow.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.overtake"
    // API 36.1 (compileSdk 36 with minor API level 1) — matches the installed android-36.1 platform.
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The only two runtime dependencies: AndroidX core + coroutines StateFlow.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
