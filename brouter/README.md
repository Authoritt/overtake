# brouter (vendored)

On-device **offline routing engine**, vendored from [BRouter](https://github.com/abrensch/brouter)
**v1.7.10** — the engine behind OsmAnd / Locus / c:geo. **MIT licensed** (see `LICENSE`).

This module is the pure-Java core of BRouter with the Android app, standalone server and
map-creator tooling stripped out. It merges upstream's five core modules into one `java-library`
so OpenCfMoto embeds turn-by-turn offline routing in a **single APK** — no AIDL to a separately
installed BRouter app, no second process.

Merged upstream modules (all `btools.*`, no third-party deps):
`brouter-util` · `brouter-codec` · `brouter-expressions` · `brouter-mapaccess` · `brouter-core`.

Consumed by `dev.zanderp.opencfmoto.BrouterRouter`. Routing data = `.rd5` segment tiles
(5°×5° grid) fetched on demand into app-internal storage; the routing profile + `lookups.dat`
are bundled as app assets (`app/src/main/assets/brouter/`).

## Updating
Re-vendor from a tagged release: copy each module's `src/main/java/btools` tree here and refresh
`LICENSE`. Keep the bundled `lookups.dat` in sync with the same release so the `.rd5` lookup
version matches (a mismatch throws "lookup version mismatch (old rd5?)").
