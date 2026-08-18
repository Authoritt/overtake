// SPDX-License-Identifier: AGPL-3.0-or-later
// The Overtake library module: com.android.library, compiled by AGP 9's built-in Kotlin (2.2.0).
// Dependencies are intentionally minimal — androidx.core + kotlinx-coroutines only — so a host app
// pulls in nothing heavy. The reader engine itself is pure Android framework + coroutines StateFlow.
plugins {
    id("com.android.library")
    id("maven-publish")
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

    // Publish the release variant as the Maven component (+ sources jar), same first-party AGP path as
    // :overtake-maps. This module is published on its own so a host that only needs the permission-light
    // reader can depend on `dev.overtake:overtake:<version>` directly, and so :overtake-maps' POM (which
    // has an api dependency on this module) resolves to a real artifact.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // The only two runtime dependencies: AndroidX core + coroutines StateFlow.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Maven publication (afterEvaluate — AGP creates components["release"] during its own pass). group and
// version come from gradle.properties; artifactId defaults to the module name (`overtake`).
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = "overtake"
                pom {
                    name.set("Overtake")
                    description.set(
                        "Overtake — permission-light Android reader library: now-playing music, " +
                            "turn-by-turn navigation, and call state as observable StateFlows, read " +
                            "without Android Auto.",
                    )
                    url.set("https://github.com/Authoritt/overtake")
                    licenses {
                        license {
                            name.set("AGPL-3.0-or-later")
                            url.set("https://www.gnu.org/licenses/agpl-3.0-standalone.html")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            name.set("Authoritt and the Overtake contributors")
                            url.set("https://github.com/Authoritt")
                        }
                    }
                    scm {
                        url.set("https://github.com/Authoritt/overtake")
                        connection.set("scm:git:https://github.com/Authoritt/overtake.git")
                        developerConnection.set("scm:git:ssh://git@github.com/Authoritt/overtake.git")
                    }
                }
            }
        }
    }
}
