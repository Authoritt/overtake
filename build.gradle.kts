// SPDX-License-Identifier: AGPL-3.0-or-later
// Root build file. Declares the Android Gradle Plugin version shared by the module below.
// Kotlin is compiled by AGP 9's built-in Kotlin support (Kotlin 2.2.0, bundled with AGP 9.2.x) —
// the same toolchain as the OpenCfMoto cockpit this library was extracted from, so no separate
// Kotlin Gradle plugin is applied.
plugins {
    id("com.android.library") version "9.2.1" apply false
    // Note: `maven-publish` is a CORE Gradle plugin (no version, already on the classpath). Each
    // module that publishes applies it directly in its own build file; it must NOT be declared here
    // with `apply false` (that is a no-op error for core plugins).
}

// Shared publish TARGETS for every module that applies `maven-publish`. Publications themselves live
// in each module (their component + POM differ), but the remote repository + credential handling is
// identical, so it is wired once here to avoid drift. `publishToMavenLocal` needs nothing declared —
// it is always available once maven-publish is applied and targets ~/.m2.
subprojects {
    plugins.withId("maven-publish") {
        // Read GitHub Packages credentials the standard two ways: Gradle properties (gpr.user/gpr.key,
        // e.g. in ~/.gradle/gradle.properties) OR the CI env vars (GITHUB_ACTOR/GITHUB_TOKEN). Via the
        // providers API so it is configuration-cache safe. `orNull` → the repo is only registered when
        // BOTH are present, so a missing token never breaks configuration or `publishToMavenLocal`.
        val gprUser = providers.gradleProperty("gpr.user")
            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
        val gprKey = providers.gradleProperty("gpr.key")
            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                if (gprUser != null && gprKey != null) {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/Authoritt/overtake")
                        credentials {
                            username = gprUser
                            password = gprKey
                        }
                    }
                }
            }
        }
    }
}
