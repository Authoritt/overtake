// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.route

import dev.overtake.maps.MapsLog
import dev.overtake.maps.model.GeoPoint
import dev.overtake.maps.model.Route
import dev.overtake.maps.model.RouteStep
import dev.overtake.maps.net.OvertakeHttp

import android.content.Context
import btools.mapaccess.OsmNode
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * On-device **offline routing** via the vendored [BRouter](https://github.com/abrensch/brouter)
 * engine (module `:brouter`, MIT). This is the primary offline engine behind
 * [OfflineRouter.offlineOnly] — a real routing engine (the one behind OsmAnd / Locus / c:geo),
 * replacing the weak Overpass-built [OfflineRoadGraph] (kept only as a secondary fallback for areas
 * that have no `.rd5` data).
 *
 * Data model: BRouter routes over `.rd5` **segment tiles** on a 5°×5° world grid, downloaded on
 * demand from https://brouter.de/brouter/segments4/ into app-internal storage
 * ([segmentsDir]) as the offline area is downloaded (see [OfflineAreaDownloader]). The routing
 * **profile** (stock `fastbike`) and the tag table (`lookups.dat`) ship as app assets and are
 * extracted next to each other into [profileDir] on first use (BRouter reads `lookups.dat` from the
 * profile's own directory).
 *
 * F1 uses the STOCK `fastbike` profile as-is (faithful; F2 adds the custom moto-curvy profile).
 * Consequences to know: `fastbike` **excludes motorways** (so a rider's "avoid highways" is always
 * honored offline, but a plain "fast" offline route also won't take a motorway), and — like any
 * bike profile — it may prefer a cycleway a motorcycle can't legally use. Both are F2 concerns.
 *
 * Internal: the offline-download orchestration ([dev.overtake.maps.route.offline.OfflineAreaDownloader])
 * moved in-lib in Stage 2 Pass 2, so nothing cross-module calls this any more — it is driven only by
 * [OfflineRouter] and the in-lib downloader.
 */
internal object BrouterRouter {
    private const val ASSET_DIR = "brouter"
    private const val PROFILE_FILE = "fastbike.brf"
    private const val LOOKUPS_FILE = "lookups.dat"
    private const val ASSET_MARKER = ".assets_version"
    private const val SEGMENTS_BASE_URL = "https://brouter.de/brouter/segments4/"
    private const val USER_AGENT = "OpenCfMoto/BRouter"

    /**
     * Version stamp for the extracted profile assets ([PROFILE_FILE] + [LOOKUPS_FILE]); [ensureAssets]
     * re-extracts when it changes. In the consuming fork this was the app's `VERSION_CODE:GIT_HASH`
     * (re-extract on every build) — but as a library the profile assets ship with and change only with
     * THIS module, so a stable, module-owned key is both correct and cheaper. Bump it whenever the
     * vendored `fastbike.brf` / `lookups.dat` change (F1 uses the STOCK profile, which rarely does).
     */
    private const val ASSET_VERSION = "overtake-maps/1"

    /** Minimal bounding box for [pruneSegments] ref-counting; the host maps its own area type to this. */
    data class Bbox(val south: Double, val west: Double, val north: Double, val east: Double)

    /** Cap a single A* run so a pathological request can't hang the route thread. */
    private const val MAX_RUN_MS = 60_000L

    /** Minimum bearing change (deg) that becomes its own guidance step. Mirrors [OfflineRoadGraph]. */
    private const val TURN_DEG = 25.0

    private const val MICRO = 1_000_000.0

    // ---- storage layout: filesDir/brouter/{segments,profiles} -------------------------------------

    private fun baseDir(ctx: Context): File = File(ctx.filesDir, "brouter").apply { mkdirs() }

    /** Folder BRouter scans for `*.rd5` segment tiles. */
    fun segmentsDir(ctx: Context): File = File(baseDir(ctx), "segments").apply { mkdirs() }

    /** Folder holding the extracted profile + `lookups.dat` (BRouter needs both side by side). */
    private fun profileDir(ctx: Context): File = File(baseDir(ctx), "profiles").apply { mkdirs() }

    // ---- 5°×5° tile math (matches brouter-mapaccess NodesCache.fileForSegment) --------------------

    /** Lower-left corner (a multiple of 5, can be negative) of the 5° cell containing [deg]. */
    private fun floor5(deg: Double): Int = (floor(deg / 5.0) * 5.0).toInt()

    /** `.rd5` file name for a cell whose lower-left corner is ([llLon],[llLat]), e.g. `E5_N45.rd5`. */
    private fun tileName(llLon: Int, llLat: Int): String {
        val slon = if (llLon < 0) "W${-llLon}" else "E$llLon"
        val slat = if (llLat < 0) "S${-llLat}" else "N$llLat"
        return "${slon}_$slat.rd5"
    }

    /** All `.rd5` tile names whose 5° cells intersect the bbox. */
    fun tilesForBbox(south: Double, west: Double, north: Double, east: Double): List<String> {
        val lon0 = floor5(minOf(west, east))
        val lon1 = floor5(maxOf(west, east))
        val lat0 = floor5(minOf(south, north))
        val lat1 = floor5(maxOf(south, north))
        val out = LinkedHashSet<String>()
        var lon = lon0
        while (lon <= lon1) {
            var lat = lat0
            while (lat <= lat1) {
                out.add(tileName(lon, lat))
                lat += 5
            }
            lon += 5
        }
        return out.toList()
    }

    private fun tileFor(lat: Double, lon: Double): String = tileName(floor5(lon), floor5(lat))

    private fun tilePresent(ctx: Context, name: String): Boolean =
        File(segmentsDir(ctx), name).let { it.isFile && it.length() > 0 }

    /**
     * True when the segment tiles containing both endpoints are on disk — the minimum for BRouter to
     * even snap start/end. If an *intermediate* tile is missing the route simply fails and
     * [OfflineRouter] falls back; this gate just avoids constructing the engine for nothing.
     */
    fun hasSegmentsForRoute(
        ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): Boolean = tilePresent(ctx, tileFor(fromLat, fromLon)) && tilePresent(ctx, tileFor(toLat, toLon))

    // ---- bundled assets (profile + lookups.dat) ---------------------------------------------------

    /** Extract the bundled profile + lookup table into [profileDir] (idempotent, re-copies on app update). */
    private fun ensureAssets(ctx: Context): Boolean = runCatching {
        val dir = profileDir(ctx)
        val marker = File(dir, ASSET_MARKER)
        val want = ASSET_VERSION
        val fresh = marker.takeIf { it.exists() }?.readText() == want &&
            File(dir, PROFILE_FILE).exists() && File(dir, LOOKUPS_FILE).exists()
        if (!fresh) {
            copyAsset(ctx, PROFILE_FILE)
            copyAsset(ctx, LOOKUPS_FILE)
            marker.writeText(want)
        }
        true
    }.getOrElse {
        MapsLog.w("route", "[route] BRouter asset extract failed: ${it.message}")
        false
    }

    private fun copyAsset(ctx: Context, name: String) {
        ctx.assets.open("$ASSET_DIR/$name").use { input ->
            File(profileDir(ctx), name).outputStream().use { input.copyTo(it) }
        }
    }

    // ---- routing ----------------------------------------------------------------------------------

    /**
     * Compute an offline route from →to with BRouter, or null when there's no `.rd5` for the area,
     * the profile/assets can't be prepared, or the engine finds no path (caller then falls back to
     * [OfflineRoadGraph]). Returns an [Route] so nav consumes it unchanged.
     *
     * Runs synchronously on the caller's thread (call from a worker — [OfflineRouter] already does).
     */
    fun route(
        ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): Route? {
        if (!hasSegmentsForRoute(ctx, fromLat, fromLon, toLat, toLon)) return null
        if (!ensureAssets(ctx)) return null
        return runCatching { routeInternal(ctx, fromLat, fromLon, toLat, toLon) }
            .onFailure { MapsLog.w("route", "[route] BRouter route error: ${it.message}") }
            .getOrNull()
    }

    private fun routeInternal(
        ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): Route? {
        val rc = RoutingContext()
        rc.localFunction = File(profileDir(ctx), PROFILE_FILE).absolutePath
        val waypoints = ArrayList<OsmNodeNamed>(2).apply {
            add(waypoint(fromLat, fromLon, "from"))
            add(waypoint(toLat, toLon, "to"))
        }
        // outfileBase/logfileBase = null → engine keeps the track in memory, writes no files.
        val engine = RoutingEngine(null, null, segmentsDir(ctx), waypoints, rc)
        // With a null outfile BRouter otherwise dumps the whole GPX to System.out (→ logcat).
        engine.quite = true
        engine.doRun(MAX_RUN_MS)
        engine.getErrorMessage()?.let {
            MapsLog.w("route", "[route] BRouter: $it")
            return null
        }
        val track = engine.getFoundTrack() ?: return null
        val nodes = track.nodes
        if (nodes == null || nodes.size < 2) return null

        val pts = ArrayList<GeoPoint>(nodes.size)
        for (n in nodes) {
            val lat = n.getILat() / MICRO - 90.0
            val lon = n.getILon() / MICRO - 180.0
            val ele = if (n.getSElev().toInt() != Short.MIN_VALUE.toInt()) n.getElev() else null
            pts.add(GeoPoint(lat = lat, lon = lon, ele = ele))
        }
        val distM = track.distance.toDouble()
        val durationSec = track.getTotalSeconds().toDouble().let { if (it > 0.0) it else distM / 13.9 }
        MapsLog.w("route", "[route] BRouter track ${pts.size} pts / ${distM.toInt()}m")
        return Route(
            points = pts,
            distanceM = distM,
            durationSec = durationSec,
            steps = buildSteps(pts),
        )
    }

    private fun waypoint(lat: Double, lon: Double, name: String): OsmNodeNamed {
        val ilon = ((lon + 180.0) * MICRO + 0.5).toInt()
        val ilat = ((lat + 90.0) * MICRO + 0.5).toInt()
        return OsmNodeNamed(OsmNode(ilon, ilat)).apply { this.name = name }
    }

    /**
     * Synthesize guidance steps from the route geometry (bearing changes), same shape/logic as
     * [OfflineRoadGraph] so nav renders identical turn cues. Road names aren't carried (offline),
     * which [GpxNav] already tolerates.
     */
    private fun buildSteps(pts: List<GeoPoint>): List<RouteStep> {
        if (pts.size < 2) return emptyList()
        val turns = ArrayList<Pair<Int, String?>>()
        turns.add(0 to null)
        for (i in 1 until pts.size - 1) {
            val bIn = bearing(pts[i - 1], pts[i])
            val bOut = bearing(pts[i], pts[i + 1])
            var d = bOut - bIn
            while (d > 180) d -= 360
            while (d < -180) d += 360
            val ad = abs(d)
            if (ad < TURN_DEG) continue
            val modifier = when {
                d > 0 && ad < 60 -> "slight right"
                d > 0 && ad <= 150 -> "right"
                d > 0 -> "sharp right"
                ad < 60 -> "slight left"
                ad <= 150 -> "left"
                else -> "sharp left"
            }
            turns.add(i to modifier)
        }
        turns.add((pts.size - 1) to null)

        val steps = ArrayList<RouteStep>(turns.size)
        for (t in turns.indices) {
            val (pi, modifier) = turns[t]
            val type = when (t) {
                0 -> "depart"
                turns.size - 1 -> "arrive"
                else -> "turn"
            }
            val legM = if (t < turns.size - 1) legLength(pts, pi, turns[t + 1].first) else 0.0
            steps.add(
                RouteStep(
                    maneuverType = type,
                    modifier = modifier,
                    roadName = "",
                    maneuverLat = pts[pi].lat,
                    maneuverLon = pts[pi].lon,
                    distanceM = legM,
                    exit = null,
                    lanes = null,
                ),
            )
        }
        return steps
    }

    private fun legLength(pts: List<GeoPoint>, a: Int, b: Int): Double {
        var m = 0.0
        for (k in a until b) m += haversineM(pts[k].lat, pts[k].lon, pts[k + 1].lat, pts[k + 1].lon)
        return m
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.min(1.0, kotlin.math.sqrt(s)))
    }

    // ---- segment (.rd5) download + cleanup --------------------------------------------------------

    /** Outcome of fetching the tiles for one area. */
    data class SegmentFetch(
        val requested: Int,
        val fetched: Int,   // newly downloaded (had data)
        val present: Int,   // already on disk
        val noData: Int,    // 404 on server (e.g. ocean cell) — legitimately absent
        val failed: Int,    // network / HTTP error
        val bytes: Long,
        val error: String?,
    ) {
        /** No hard failures and at least one usable tile → the area has BRouter routing data. */
        val hasRouting: Boolean get() = error == null && failed == 0 && (fetched + present) > 0
    }

    /**
     * Ensure every `.rd5` tile covering the bbox is on disk (download the missing ones). A `404` is
     * treated as "no data for that cell" (not a failure). [onProgress] fires per tile:
     * (tilesDone, tilesTotal, bytesSoFar).
     */
    fun downloadSegmentsForBbox(
        ctx: Context,
        south: Double, west: Double, north: Double, east: Double,
        onProgress: (done: Int, total: Int, bytes: Long) -> Unit,
    ): SegmentFetch {
        val tiles = tilesForBbox(south, west, north, east)
        val dir = segmentsDir(ctx)
        var fetched = 0
        var present = 0
        var noData = 0
        var failed = 0
        var bytes = 0L
        var firstErr: String? = null
        for ((idx, name) in tiles.withIndex()) {
            val dst = File(dir, name)
            if (dst.isFile && dst.length() > 0) {
                present++
                onProgress(idx + 1, tiles.size, bytes)
                continue
            }
            val r = runCatching { downloadTile(name, dst) }.getOrElse { e ->
                MapsLog.w("route", "[route] BRouter segment $name failed: ${e.message}")
                if (firstErr == null) firstErr = e.message ?: "download failed"
                failed++
                onProgress(idx + 1, tiles.size, bytes)
                continue
            }
            when {
                r == RESULT_NO_DATA -> noData++
                else -> {
                    fetched++
                    bytes += r
                }
            }
            onProgress(idx + 1, tiles.size, bytes)
        }
        MapsLog.w("route", 
            "[route] BRouter segments: $fetched fetched, $present present, $noData none, $failed failed " +
                "(${bytes / (1024 * 1024)}MB)",
        )
        return SegmentFetch(tiles.size, fetched, present, noData, failed, bytes, firstErr)
    }

    private const val RESULT_NO_DATA = -1L

    /** Stream one `.rd5` to disk (temp + rename). Returns bytes, or [RESULT_NO_DATA] on 404. */
    private fun downloadTile(name: String, dst: File): Long {
        OvertakeHttp.throttle("brouter.de", 400)
        val conn = OvertakeHttp.openUrl(SEGMENTS_BASE_URL + name).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code == 404) {
                MapsLog.w("route", "[route] BRouter no segment $name (404 — no data for this cell)")
                return RESULT_NO_DATA
            }
            if (code !in 200..299) throw IOException("HTTP $code for $name")
            val tmp = File(dst.parentFile, "$name.part")
            conn.inputStream.use { input ->
                BufferedOutputStream(tmp.outputStream()).use { out -> input.copyTo(out, 64 * 1024) }
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            return dst.length()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Delete `.rd5` tiles not needed by any [keep] bbox (call AFTER removing the deleted area from
     * the store). Tiles are shared 5° cells, so this reference-counts by remaining bbox coverage. The
     * host passes the boxes of the areas it still keeps (see [Bbox]) — decoupled from any host-side
     * area-registry type.
     */
    fun pruneSegments(ctx: Context, keep: List<Bbox>) {
        val needed = HashSet<String>()
        for (a in keep) needed.addAll(tilesForBbox(a.south, a.west, a.north, a.east))
        val dir = segmentsDir(ctx)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".rd5") }?.forEach { f ->
            if (f.name !in needed) {
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    MapsLog.w("route", "[route] BRouter pruned segment ${f.name}")
                }
            }
        }
    }
}
