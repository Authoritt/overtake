// Vendored BRouter routing engine (MIT). See brouter/LICENSE and the repo NOTICE.
// Pure-Java, no third-party runtime deps — the 5 upstream core modules (brouter-core,
// brouter-mapaccess, brouter-expressions, brouter-codec, brouter-util) merged into one
// java-library so the Android app can embed on-device offline routing in a SINGLE APK
// (no AIDL to the installed BRouter app, no second process). Upstream: v1.7.10.
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Match upstream's `options.release = 11` exactly so nothing JDK-17-only leaks in.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    // Upstream ships with checkstyle/pmd; vendored here as plain sources — silence lint noise.
    options.compilerArgs.add("-Xlint:none")
}
