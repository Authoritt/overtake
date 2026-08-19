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
    id("maven-publish")
}

// Publishing coordinates (group=dev.overtake, version — both in gradle.properties, the SINGLE source
// of truth for all three artifacts; artifactId defaults to the module name `overtake-maps`). They now
// serve TWO consumers:
//   1. Composite build — the OpenCfMoto cockpit fork pulls this module via `includeBuild`, which
//      auto-substitutes an external `dev.overtake:overtake-maps` dependency whenever the project's
//      identity (group:name) matches, so local iteration builds against THESE sources.
//   2. Maven publishing — the `maven-publish` config at the bottom of this file ships the release AAR
//      as `dev.overtake:overtake-maps:<version>` for external / release consumers.

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

    testOptions {
        // Plain-JVM unit tests for the PURE logic (search URL building, request pacing). android.jar
        // methods are stubs there; returning defaults instead of throwing keeps an incidental
        // android type inert rather than exploding the test — same setting the cockpit fork uses.
        unitTests.isReturnDefaultValues = true
    }

    // Expose a SINGLE variant (release) as the Maven component, with a matching sources jar. This is
    // AGP's first-party publishing (no fat-aar plugin), so it stays robust on AGP 9.x. The publication
    // that consumes components["release"] is registered in afterEvaluate below — AGP only creates that
    // software component during its own afterEvaluate pass.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

    // Mapsforge offline VECTOR maps, rendered through android.graphics.Canvas (NOT OpenGL): the 3rd
    // dash renderer. Canvas is the point — like osmdroid raster it keeps drawing with the phone screen
    // OFF (a GL surface does not), but with vector quality. Deliberately mapsforge-map-android, NOT the
    // GL `vtm*` line, which reintroduces the screen-off problem.
    //   Pinned to 0.21.0 to MATCH the osmdroid↔mapsforge bridge below: `osmdroid-mapsforge:6.1.20` was
    //   compiled against — and transitively pins — mapsforge-map/-themes/-core 0.21.0, so declaring the
    //   android artifact at the SAME version keeps the whole mapsforge graph aligned to what the bridge
    //   expects (avoids a 0.21-vs-latest API skew). Pulls mapsforge-map-reader + com.caverock:androidsvg.
    implementation("org.mapsforge:mapsforge-map-android:0.21.0")
    // The osmdroid↔mapsforge bridge: renders a Mapsforge `.map` file as an osmdroid TILE SOURCE, so the
    // nav overlays (route/puck/follow) on the SAME osmdroid MapView the raster engine already uses keep
    // working unchanged (MapsforgeController + DashMapController). Version MATCHES the pinned
    // osmdroid-android 6.1.20; it pulls mapsforge-map/-themes 0.21.0 transitively. All on mavenCentral.
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")

    // okhttp leaks into the public API (OvertakeMapsConfig.okHttpClientProvider) → api, so a consumer
    // can hand us its own configured client.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines: the same version :overtake uses (Router/PlaceSearch expose suspend functions).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // androidx.core — ContextCompat.getDrawable() for the renderer's puck / destination-pin bitmaps
    // (same version the :overtake reader module pins).
    implementation("androidx.core:core-ktx:1.18.0")

    // Plain-JVM unit tests (same JUnit 4 the consuming cockpit fork uses). Only the PURE seams are
    // covered here — the ones a wrong answer in would silently ship a bad request URL.
    testImplementation("junit:junit:4.13.2")
}

// ── Maven publication ────────────────────────────────────────────────────────────────────────────
// afterEvaluate: AGP publishes the `release` software component only after its own configuration pass.
// `from(components["release"])` carries the AAR, the sources jar, AND the dependency graph into the
// generated POM — `api(...)` deps land as <scope>compile</scope> (on the consumer's compile classpath:
// dev.overtake:overtake + okhttp, the only types on the public surface), `implementation(...)` deps as
// <scope>runtime</scope> (dev.overtake:brouter + osmdroid/MapLibre/mapsforge/coroutines, impl-only). The
// two sibling project deps resolve as real artifacts because :overtake and :brouter publish too (same
// group/version), so a consumer pulling dev.overtake:overtake-maps gets the whole graph from one repo.
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = "overtake-maps"
                pom {
                    name.set("Overtake Maps")
                    description.set(
                        "Overtake — offline, screen-off, moto-optimized map renderer + routing + " +
                            "place-search supplier library for Android",
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
