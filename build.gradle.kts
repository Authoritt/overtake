// SPDX-License-Identifier: AGPL-3.0-or-later
// Root build file. Declares the Android Gradle Plugin version shared by the module below.
// Kotlin is compiled by AGP 9's built-in Kotlin support (Kotlin 2.2.0, bundled with AGP 9.2.x) —
// the same toolchain as the OpenCfMoto cockpit this library was extracted from, so no separate
// Kotlin Gradle plugin is applied.
plugins {
    id("com.android.library") version "9.2.1" apply false
}
