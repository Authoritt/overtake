<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# Publishing Overtake

Overtake is published as **versioned Maven/Gradle artifacts** (the Java-world equivalent of a NuGet
package) so external consumers can depend on a coordinate from a repository instead of only wiring the
source via a Gradle composite build.

## Artifacts and coordinates

Three modules publish, all under group **`dev.overtake`**, one shared version (see below):

| Coordinate | Module | Kind | License | Why it publishes |
|---|---|---|---|---|
| `dev.overtake:overtake-maps` | `:overtake-maps` | Android AAR | AGPL-3.0-or-later | The map renderer + routing + place-search supplier library (the thing you usually want). |
| `dev.overtake:overtake` | `:overtake` | Android AAR | AGPL-3.0-or-later | The permission-light reader engine. `:overtake-maps` has an **`api`** dependency on it (the `OvertakeLog` seam), so its POM references this artifact; also usable standalone. |
| `dev.overtake:brouter` | `:brouter` | Java JAR | MIT (upstream) | The vendored pure-Java BRouter offline-routing core. `:overtake-maps` has an **`implementation`** dependency on it. |

**Version is a single source of truth**: `version=0.1.0` in [`gradle.properties`](gradle.properties)
(alongside `group=dev.overtake`). Gradle maps those special keys onto `project.group` / `project.version`
for every module, so bumping `version` there cuts a new release of all three artifacts at once. No
per-module version strings to keep in sync.

### Why three artifacts instead of one fat AAR?

The decision was **separate modular artifacts**, not a bundled fat-AAR. Rationale:

- **Robustness on AGP 9.x.** Fat-AAR (folding `:overtake` + `:brouter` classes into one AAR) has **no
  first-party AGP support** — it needs a community plugin (`com.kezong:fat-aar` and friends) that
  chronically lags AGP releases and almost certainly does not support AGP 9.2.1. Separate artifacts use
  only first-party `maven-publish` + AGP's built-in `singleVariant("release")` component — zero
  third-party publish machinery. This is how every mainstream multi-module Android library ships
  (AndroidX, OkHttp, MapLibre itself).
- **Keeps the module architecture honest.** `:overtake` stays independently consumable (a host that
  only needs the notification reader pulls it alone and stays permission-light), and `:brouter` keeps
  its **MIT** license cleanly separated — its own POM declares MIT; republishing under the `dev.overtake`
  group does not change brouter's terms.
- **Tradeoff:** the three artifacts must be co-published to the **same** repository. A consumer pulling
  `dev.overtake:overtake-maps` transitively resolves `dev.overtake:overtake` (compile scope) and
  `dev.overtake:brouter` (runtime scope) from that same repo. This is automatic — `publishToMavenLocal`
  publishes all three, and one `./gradlew publish` pushes all three to GitHub Packages — the only cost
  is remembering to publish them together, which the shared version and the aggregate commands below
  make a non-issue.

### Dependency scopes in the generated POM

`from(components["release"])` carries the dependency graph into the POM. `api(...)` deps land as
`<scope>compile</scope>` (on the consumer's compile classpath); `implementation(...)` deps as
`<scope>runtime</scope>` (present at runtime, not compile). The split is deliberate and evidence-based:
the public API (`OvertakeMaps.create()` → `MapProvider` → the `MapRenderer`/`Router`/`PlaceSearch`
contracts) exposes only Overtake's own model types **plus `okhttp3.OkHttpClient`** (via
`OvertakeMapsConfig.okHttpClientProvider`). So `okhttp` and `:overtake` are `api` (compile); osmdroid,
MapLibre, mapsforge, coroutines and `:brouter` are `implementation` (runtime) because they live only
behind the contracts (`DashMapEngine` and the other impls are `internal`).

---

## How to publish

All commands run from the repo root (`E:\Desarrollo\Activos\overtake` / `/e/Desarrollo/Activos/overtake`)
with `JAVA_HOME` on JDK 17 and `ANDROID_HOME` set.

### 1. Maven Local (`~/.m2`) — zero credentials, works now

```bash
./gradlew publishToMavenLocal
```

Publishes all three modules to `~/.m2/repository/dev/overtake/…`. For `:overtake-maps` you get the AAR,
the `.pom`, and the `-sources.jar`. Use it to test the published shape locally, or to let another local
project consume the release artifact via `mavenLocal()`.

### 2. GitHub Packages

Wired but not pushed (needs a token). Credentials are read two ways — Gradle properties
`gpr.user` / `gpr.key` (e.g. in `~/.gradle/gradle.properties`) **or** env vars
`GITHUB_ACTOR` / `GITHUB_TOKEN` — and the remote repository is only registered when **both** are
present, so a missing token never breaks configuration or `publishToMavenLocal`. The token needs the
`write:packages` scope.

```bash
export GITHUB_ACTOR=your-username
export GITHUB_TOKEN=ghp_your_token_with_write_packages
./gradlew publish        # publishes all three modules to GitHub Packages
```

Target repo: `https://maven.pkg.github.com/Authoritt/overtake` (wired once in the root `build.gradle.kts`).

### 3. JitPack — zero publish infra, easiest public path

JitPack builds the library from a git tag on first request. Just tag and push:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The first time a consumer requests the artifact, JitPack checks out the tag, runs the build in
[`jitpack.yml`](jitpack.yml) (JDK 17 + `publishToMavenLocal`), and serves the result. No account or
token needed by you or the consumer.

---

## Consumer snippets

The dependency line is the **same coordinate** for Maven Local and GitHub Packages
(`dev.overtake:overtake-maps:0.1.0`); only the `repositories {}` block differs. JitPack re-hosts under
its own `com.github.*` group.

### From Maven Local

```kotlin
// settings.gradle.kts (or the consumer's repositories block)
repositories {
    mavenLocal()
    google()
    mavenCentral()
}
// build.gradle.kts
dependencies {
    implementation("dev.overtake:overtake-maps:0.1.0")
    // :overtake and :brouter resolve transitively from mavenLocal — no extra lines.
}
```

### From GitHub Packages

GitHub Packages requires auth even to **read** (a PAT with `read:packages`).

```kotlin
repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Authoritt/overtake")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
dependencies {
    implementation("dev.overtake:overtake-maps:0.1.0")
}
```

### From JitPack

JitPack re-hosts multi-module builds under `com.github.<User>.<Repo>` with the module name as the
artifactId:

```kotlin
repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    implementation("com.github.Authoritt.overtake:overtake-maps:v0.1.0")
}
```

> The exact JitPack coordinate (and its rewriting of the internal `:overtake` / `:brouter` deps to the
> `com.github.*` group) is confirmed by the JitPack build log on the first request — verify there after
> the first tag. GitHub Packages and Maven Local are the guaranteed paths.

---

## Recommended workflow: `includeBuild` for dev, published artifact for releases

Keep **both**. They are not redundant — they serve different phases.

`includeBuild("../overtake")` in the fork's `settings.gradle.kts` is a Gradle **composite build**: the
Gradle-blessed way to develop a library and its consumer together. It is **not** a vendored copy and
**not** a static DLL. Gradle substitutes the external coordinate `dev.overtake:overtake-maps` with the
**live** sibling project on disk, so every edit to the library source is picked up by the next build of
the consumer — no publish step, no version bump, no stale binary. Use it while iterating on both repos
at once.

```kotlin
// fork/settings.gradle.kts — LOCAL DEVELOPMENT override
includeBuild("../overtake")

// fork/app/build.gradle.kts — same coordinate whether local or published
dependencies {
    implementation("dev.overtake:overtake-maps:0.1.0")   // substituted to ../overtake while includeBuild is present
}
```

Switch to the **published** artifact (drop the `includeBuild` line and add one of the repositories
above) for CI, for external consumers, or to pin a specific released version. The dependency
coordinate string is identical either way — only the resolution source changes. That is the whole point
of matching the composite substitution to the published coordinates: local iteration and release
consumption use one and the same `dev.overtake:overtake-maps` line.
