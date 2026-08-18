// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.render

import android.app.Application
import android.content.Context
import dev.overtake.maps.MapsLog
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File

/**
 * The Mapsforge tile layer: offline VECTOR maps rendered by mapsforge through
 * `android.graphics.Canvas` (NOT OpenGL) and surfaced to osmdroid as a tile source, so the SAME
 * osmdroid [org.osmdroid.views.MapView] + nav overlays that [DashMapController] already drives keep
 * working unchanged. Canvas rendering is the whole point: like the osmdroid raster engine it keeps
 * drawing with the phone screen OFF (unlike a GL surface), but with vector quality. This is why the
 * runbook chose `mapsforge-map-android` over `vtm*` (VTM is GL and has the screen-off problem).
 *
 * This object owns ONLY the tile-source concern — where the `.map` files live, the one-time mapsforge
 * graphics init, and building the osmdroid tile provider from `.map` files. The View, camera, route
 * lines, puck and follow logic are entirely [DashMapController]'s; nothing is reinvented here.
 *
 * Bridge: `org.osmdroid:osmdroid-mapsforge` (pinned to the same 6.1.20 as `osmdroid-android`; it
 * pulls `org.mapsforge:mapsforge-*:0.21.0` transitively — the exact version it was compiled against).
 */
internal object MapsforgeController {

    /**
     * Where a rider's offline Mapsforge `.map` vector files live: `<filesDir>/mapsforge`. A sibling of
     * the BRouter `.rd5` routing data and the osmdroid raster cache, all under the host's filesDir.
     * Created on first inspection so it is easy to find (adb / file manager) when a `.map` has to be
     * dropped in manually while the auto-download is a follow-up (see [mapFiles]).
     */
    fun mapsDir(filesDir: File): File = File(filesDir, "mapsforge").also { it.mkdirs() }

    /** Every `.map` vector file the rider has for offline Mapsforge rendering (may be empty). */
    fun mapFiles(filesDir: File): List<File> =
        (
            mapsDir(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".map", ignoreCase = true) }
                ?: emptyArray()
            ).sortedBy { it.name }

    /** True when ≥1 offline `.map` is present (so Mapsforge can render instead of the raster fallback). */
    fun hasMaps(filesDir: File): Boolean = mapFiles(filesDir).isNotEmpty()

    @Volatile
    private var graphicsReady = false

    /**
     * One-time mapsforge graphics init (`AndroidGraphicFactory`, via the bridge's
     * [MapsForgeTileSource.createInstance]). MUST run once per process before any
     * [MapsForgeTileSource.createFromFiles] — the bridge throws otherwise. Idempotent and null-safe:
     * returns false if the [Context] resolves to no [Application] (the caller then falls back to
     * raster instead of crashing).
     */
    private fun ensureGraphics(context: Context): Boolean {
        if (graphicsReady) return true
        return synchronized(this) {
            if (graphicsReady) return@synchronized true
            val app = context.applicationContext as? Application ?: return@synchronized false
            runCatching { MapsForgeTileSource.createInstance(app) }
                .onFailure { MapsLog.w("map", "[MAP] Mapsforge graphics init failed: ${it.message}", it) }
                .onSuccess { graphicsReady = true }
            graphicsReady
        }
    }

    /**
     * Build the osmdroid tile provider that renders [files] (`.map`) via mapsforge, or null when init
     * fails / no files / the files can't be read (the caller then falls back to raster). Uses the
     * built-in OSMARENDER render theme; day/night is applied at DRAW time by [DashMapController] via
     * osmdroid's colour-invert filter, so no separate night render theme (or re-render) is needed and
     * the on-disk tile cache stays keyed to a single theme.
     */
    fun buildTileProvider(context: Context, files: List<File>): MapTileProviderBase? {
        if (files.isEmpty()) return null
        if (!ensureGraphics(context)) return null
        return runCatching {
            val source = MapsForgeTileSource.createFromFiles(files.toTypedArray())
            MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null)
        }.onFailure {
            MapsLog.w("map", "[MAP] Mapsforge tile provider build failed: ${it.message}", it)
        }.getOrNull()
    }
}
