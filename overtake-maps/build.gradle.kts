// SPDX-License-Identifier: AGPL-3.0-or-later
// The Overtake map/routing/search library: the HEAVY sibling of :overtake. It carries the map stack
// (osmdroid raster + MapLibre GL vector), okhttp, and the vendored BRouter offline engine, so a host
// that only needs the notification reader can depend on :overtake alone and stay permission-light.
// Namespace dev.overtake.maps; compiled by AGP 9's built-in Kotlin (2.2.0), same toolchain as
// :overtake and the OpenCfMoto cockpit this was extracted from. This is the SUPPLIER library
// open-cfmoto consumes via Gradle; Stages 1-4 move the real renderer/router/search impls in behind
// the contracts defined here.
plugins {
    id("com.android.library")
}

// Publishing coordinates. A consumer (the OpenCfMoto cockpit fork) pulls this module via a Gradle
// composite build (`includeBuild`), which auto-substitutes an external `dev.overtake:overtake-maps`
// dependency for THIS project only when the project's identity (group:name) matches. `name` is the
// module path (`overtake-maps`); `group` must be set explicitly (the default is empty). No maven
// publishing is configured — the coordinates exist purely so composite substitution resolves.
group = "dev.overtake"
version = "0.1.0-dev"

android {
    namespace = "dev.overtake.maps"
    // API 36.1 (compileSdk 36 with minor API level 1) — matches :overtake and the installed platform.
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

    // JDK 17 for the heavy map stack (osmdroid / MapLibre). :overtake stays on 11; each module targets
    // its own level independently and a 17 consumer reads the 11 reader module fine.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The permission-light reader module — reused for its OvertakeLog sink (map logging routes there).
    // `api`, not `implementation`: `dev.overtake.OvertakeLog` is part of the PUBLIC seam surface a host
    // wires at startup (`OvertakeLog.logger = { ... }`). A maps-only consumer (the OpenCfMoto cockpit
    // fork) depends on :overtake-maps alone, so the sink must be on its COMPILE classpath, not just
    // runtime — otherwise the host can't reference OvertakeLog to install its logger.
    api(project(":overtake"))
    // Vendored offline routing engine (MIT, pure Java) — the offline graph backend for Router.
    implementation(project(":brouter"))

    // Map renderers: osmdroid raster + MapLibre GL vector (OpenGL backend — reliable on VirtualDisplay).
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.maplibre.gl:android-sdk-opengl:13.3.1")

    // okhttp leaks into the public API (OvertakeMapsConfig.okHttpClientProvider) → api, so a consumer
    // can hand us its own configured client.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines: the same version :overtake uses (Router/PlaceSearch expose suspend functions).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
