// Vendored BRouter routing engine (MIT). See brouter/LICENSE and the repo NOTICE.
// Pure-Java, no third-party runtime deps — the 5 upstream core modules (brouter-core,
// brouter-mapaccess, brouter-expressions, brouter-codec, brouter-util) merged into one
// java-library so the Android app can embed on-device offline routing in a SINGLE APK
// (no AIDL to the installed BRouter app, no second process). Upstream: v1.7.10.
plugins {
    id("java-library")
    id("maven-publish")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    // Ship a sources jar alongside the classes jar (MIT keeps this trivially shareable).
    withSourcesJar()
}

// Match upstream's `options.release = 11` exactly so nothing JDK-17-only leaks in.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    // Upstream ships with checkstyle/pmd; vendored here as plain sources — silence lint noise.
    options.compilerArgs.add("-Xlint:none")
}

// Maven publication. Published as its own artifact `dev.overtake:brouter:<version>` (group/version from
// gradle.properties) so :overtake-maps' POM can carry a normal runtime dependency on it instead of a
// fat-aar bundle — see PUBLISHING.md for the rationale. It keeps its UPSTREAM MIT license in the POM;
// republishing under the dev.overtake group does not change brouter's own terms. `components["java"]`
// exists eagerly for a java-library (no afterEvaluate needed) and already includes the sources jar.
publishing {
    publications {
        register<MavenPublication>("java") {
            from(components["java"])
            artifactId = "brouter"
            pom {
                name.set("BRouter (vendored)")
                description.set(
                    "Vendored pure-Java core of the BRouter offline routing engine (upstream v1.7.10) " +
                        "— the on-device routing backend embedded by Overtake.",
                )
                url.set("https://github.com/Authoritt/overtake")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        name.set("BRouter contributors; vendored by the Overtake contributors")
                        url.set("https://github.com/abrensch/brouter")
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
