// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.render

import android.content.Context
import dev.overtake.maps.net.OvertakeHttp
import dev.overtake.maps.route.offline.CellularTileDownloader
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import java.io.File

/**
 * osmdroid setup for the raster (Canvas) map — the reliable off-screen renderer used by the host's
 * projected dash, and the plain phone MapView the host embeds directly ([createMapView]).
 *
 * Tiles are fetched interactively as you pan and cached on disk (normal, policy-compliant use with an
 * identifying User-Agent). We do NOT bulk pre-download OSM raster tiles: OSM's tile usage policy
 * forbids it. Google-style "download this area for offline" is handled by the MapLibre vector regions
 * (OpenFreeMap), which are built for that.
 *
 * When the host is bound to an internet-less uplink (e.g. a bike Wi‑Fi head unit), tile HTTP is pinned
 * to the host's chosen network via [CellularTileDownloader] (over [OvertakeHttp.openUrl]).
 *
 * Public because a host embeds a plain osmdroid [MapView] directly ([createMapView]) and manages the
 * raster tile cache ([configure] / [rasterCacheBytes] / [clearRasterCache]) outside the renderer.
 */
object GpxOsmdroid {

    /** `<filesDir>/osmdroid` — the osmdroid base path (config DB + tile cache live under it). */
    fun osmdroidBaseDir(filesDir: File): File = File(filesDir, "osmdroid")

    /**
     * The ONE definition of the interactive raster tile cache dir: `<filesDir>/osmdroid/tiles`. The
     * offline manager reads/clears the SAME directory through here, so the path is computed in a single
     * place across the module.
     */
    fun tileCacheDir(filesDir: File): File = File(osmdroidBaseDir(filesDir), "tiles")

    fun configure(context: Context) {
        val app = context.applicationContext
        val base = osmdroidBaseDir(app.filesDir)
        val cache = tileCacheDir(app.filesDir)
        base.mkdirs()
        cache.mkdirs()
        val cfg = Configuration.getInstance()
        // OSM tile policy requires an identifying User-Agent (the osmdroid default is blocked). The host
        // installs its UA via OvertakeMapsConfig.userAgent → OvertakeHttp.userAgent.
        cfg.userAgentValue = OvertakeHttp.userAgent
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = cache
        cfg.expirationOverrideDuration = 90L * 24 * 60 * 60 * 1000
        cfg.tileDownloadThreads = 2
        // Interactive tile cache ceiling before osmdroid trims oldest tiles.
        cfg.tileFileSystemCacheMaxBytes = 512L * 1024 * 1024
    }

    /**
     * Tile provider whose online downloads go through [OvertakeHttp] (so they ride the host's pinned
     * uplink even while the process default route has no internet).
     */
    fun cellularTileProvider(
        context: Context,
        tileSource: ITileSource = TileSourceFactory.MAPNIK,
    ): MapTileProviderBasic {
        return object : MapTileProviderBasic(context, tileSource) {
            override fun createDownloaderProvider(
                aNetworkAvailablityCheck: INetworkAvailablityCheck?,
                pTileSource: ITileSource?,
            ): MapTileDownloader {
                val downloader = super.createDownloaderProvider(aNetworkAvailablityCheck, pTileSource)
                downloader.setTileDownloader(CellularTileDownloader())
                return downloader
            }
        }
    }

    /** Build a [MapView] with uplink-pinned tile downloads. */
    fun createMapView(context: Context): MapView {
        configure(context)
        return MapView(context, cellularTileProvider(context))
    }

    /** Total bytes of the interactive raster tile cache on disk (host [Context] overload). */
    fun rasterCacheBytes(context: Context): Long =
        rasterCacheBytes(context.applicationContext.filesDir)

    /** Total bytes of the interactive raster tile cache on disk (filesDir overload). */
    fun rasterCacheBytes(filesDir: File): Long = dirSize(tileCacheDir(filesDir))

    /** Wipe the interactive raster tile cache (host [Context] overload). */
    fun clearRasterCache(context: Context) = clearRasterCache(context.applicationContext.filesDir)

    /** Wipe the interactive raster tile cache (filesDir overload). */
    fun clearRasterCache(filesDir: File) {
        val cache = tileCacheDir(filesDir)
        runCatching { cache.deleteRecursively(); cache.mkdirs() }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
