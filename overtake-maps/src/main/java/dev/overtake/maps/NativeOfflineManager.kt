// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import android.content.Context
import dev.overtake.maps.route.offline.MapOfflineManager
import dev.overtake.maps.route.offline.OfflineAreaDownloader
import dev.overtake.maps.route.offline.OfflineAreasStore
import java.io.File

/**
 * The [OfflineManager] the library hands back from [MapProvider.Native]. Holds the app [context]
 * (MapLibre offline region DB + BRouter `.rd5`/graph dirs + the osmdroid raster cache all under
 * `filesDir`) and the host [config] (the day/night style URLs to download tiles for, and the
 * `filesDir` the raster cache lives under). Delegates every operation to the extracted engines
 * ([OfflineAreaDownloader] / [MapOfflineManager] / [OfflineAreasStore]).
 */
internal class NativeOfflineManager(
    private val context: Context,
    private val config: OvertakeMapsConfig,
) : OfflineManager {

    override fun storedAreas(): List<OfflineAreasStore.Area> = OfflineAreasStore.list(context)

    override fun areaSizes(onResult: (Map<String, Long>) -> Unit) {
        MapOfflineManager.listAreas(context) { summaries ->
            onResult(summaries.associate { it.name to it.sizeBytes })
        }
    }

    override fun bboxAround(lat: Double, lon: Double, radiusKm: Int): OfflineManager.Bbox =
        OfflineAreaDownloader.bboxAround(lat, lon, radiusKm)

    override fun download(
        name: String,
        bbox: OfflineManager.Bbox,
        highDetail: Boolean,
        onPhase: (OfflineManager.Phase) -> Unit,
        onProgress: (percent: Int, bytes: Long) -> Unit,
        onDone: (ok: Boolean, routed: Boolean, message: String) -> Unit,
    ) {
        OfflineAreaDownloader.download(
            context,
            name,
            bbox,
            highDetail,
            config.styleDayUrl,
            config.styleNightUrl,
            onPhase = onPhase,
            onProgress = onProgress,
            onDone = onDone,
        )
    }

    override fun delete(name: String, onDone: (ok: Boolean) -> Unit) {
        OfflineAreaDownloader.delete(context, name, onDone)
    }

    override fun rasterCacheBytes(): Long = dirSize(rasterCacheDir())

    override fun clearRasterCache() {
        val cache = rasterCacheDir()
        runCatching { cache.deleteRecursively(); cache.mkdirs() }
    }

    /** The osmdroid interactive tile cache — `filesDir/osmdroid/tiles`, matching the host's setup. */
    private fun rasterCacheDir(): File = File(File(config.filesDir, "osmdroid"), "tiles")

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
