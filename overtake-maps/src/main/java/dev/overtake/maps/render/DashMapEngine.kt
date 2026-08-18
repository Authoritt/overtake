// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.render

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import dev.overtake.maps.MapsLog
import dev.overtake.maps.OvertakeMapsConfig
import dev.overtake.maps.RendererKind
import dev.overtake.maps.contract.MapRenderer
import dev.overtake.maps.model.MapPlace
import dev.overtake.maps.net.OvertakeHttp
import org.maplibre.android.maps.MapView as LibreMapView

/**
 * The library's [MapRenderer] implementation: it owns a map View inside a host [ViewGroup] and fans
 * every call out to whichever backend is live — MapLibre GL vector ([MapLibreDashController]) or the
 * osmdroid Canvas engine ([DashMapController], online raster OR offline Mapsforge vector tiles).
 *
 * Backend choice (decided in [attach], when the host [Context] is known):
 *  - **Phone preview** (an [Activity] context) → always MapLibre (vector + 3D tilt); the phone control
 *    skips any encode path, so there is no reason not to show the premium look.
 *  - **Off-screen / projected host** → [OvertakeMapsConfig.rendererKind] decides: MAPLIBRE (default),
 *    OSMDROID online raster, or MAPSFORGE offline VECTOR `.map` tiles (Canvas — screen-OFF like the
 *    raster engine, but vector; falls back to raster when the rider has no `.map`). All first-class.
 *
 * History: MapLibre GL was empirically proven to render cleanly — and to keep rendering with the phone
 * screen OFF — to an encoder-backed off-screen host, so it is the default; osmdroid stays selectable
 * ([RendererKind.OSMDROID]). [forceLibre] is a dev-probe override that pins MapLibre regardless of
 * config/context (see [dev.overtake.maps.OvertakeMaps.createDevProbeRenderer]).
 *
 * The renderer took its `Context` + host in the fork's constructor; the [MapRenderer] contract can't, so
 * hosting is the first call ([attach]). One instance hosts ONE View in ONE host; the library hands out a
 * fresh renderer per [dev.overtake.maps.OvertakeMaps.create], so each host surface gets its own.
 */
internal class DashMapEngine(
    private val config: OvertakeMapsConfig,
    /**
     * DEV PROBE ONLY — leave false in production. Forces MapLibre regardless of [config]/context so a
     * probe can measure its output in isolation on an off-screen host. Production honors
     * [OvertakeMapsConfig.rendererKind].
     */
    private val forceLibre: Boolean = false,
) : MapRenderer {

    private var libre: MapLibreDashController? = null
    private var osm: DashMapController? = null

    override fun attach(context: Context, host: ViewGroup) {
        val useLibre =
            context is Activity || forceLibre || config.rendererKind == RendererKind.MAPLIBRE
        if (useLibre) {
            OvertakeHttp.ensureCellularUplink()
            // Pin MapLibre style/tile HTTP to the host's uplink (e.g. cellular while bound to an
            // internet-less Wi-Fi). The host supplies its configured OkHttpClient via config.
            config.okHttpClientProvider?.let { provider ->
                try {
                    org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(provider())
                } catch (_: Exception) {
                }
            }
            val mv = LibreMapView(context)
            host.addView(
                mv, 0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            libre = MapLibreDashController(context, mv, config.styleDayUrl, config.styleNightUrl)
            osm = null
            val why = when {
                context is Activity -> "phone preview"
                forceLibre -> "dev probe (forceLibre)"
                else -> "config=MAPLIBRE"
            }
            MapsLog.w("map", "[MAP] engine=MapLibre ($why)")
        } else {
            libre = null
            osm = attachOsmdroidFamily(context, host)
        }
    }

    /**
     * The off-screen Canvas family (both keep rendering with the phone screen OFF, unlike GL):
     *  - MAPSFORGE → offline VECTOR `.map` tiles when the rider has them; if none is present (or the
     *    files can't be read) it degrades GRACEFULLY to the online osmdroid raster and logs where to
     *    drop a `.map`, never crashing on a missing offline source.
     *  - OSMDROID → always the online osmdroid raster.
     * Both are the SAME [DashMapController] (same MapView + nav overlays); only the tile source differs.
     */
    private fun attachOsmdroidFamily(context: Context, host: ViewGroup): DashMapController {
        if (config.rendererKind == RendererKind.MAPSFORGE) {
            val files = MapsforgeController.mapFiles(config.filesDir)
            val provider = MapsforgeController.buildTileProvider(context, files)
            if (provider != null) {
                MapsLog.w("map", "[MAP] engine=Mapsforge (${files.size} .map, Canvas/screen-off)")
                return DashMapController(context, host, tileProvider = provider)
            }
            MapsLog.w(
                "map",
                "[MAP] engine=osmdroid raster (config=MAPSFORGE fallback — no readable .map in " +
                    "${MapsforgeController.mapsDir(config.filesDir).absolutePath}; drop a Mapsforge " +
                    ".map there for offline vector)",
            )
            return DashMapController(context, host)
        }
        MapsLog.w("map", "[MAP] engine=osmdroid (config=OSMDROID)")
        return DashMapController(context, host)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        libre?.onCreate(savedInstanceState)
        osm?.onCreate(savedInstanceState)
    }

    override fun onResume() {
        libre?.onResume()
        osm?.onResume()
    }

    override fun onPause() {
        libre?.onPause()
        osm?.onPause()
    }

    override fun onStop() {
        libre?.onStop()
        osm?.onStop()
    }

    override fun onDestroy() {
        try { libre?.onDestroy() } catch (_: Exception) {}
        try { osm?.onDestroy() } catch (_: Exception) {}
    }

    override fun setBuildings3d(enabled: Boolean) {
        libre?.setBuildings3d(enabled)
        osm?.setBuildings3d(enabled)
    }

    override fun applyTheme(night: Boolean) {
        libre?.applyTheme(night)
        osm?.applyTheme(night)
    }

    override fun setRouteRemain(points: List<Pair<Double, Double>>) {
        libre?.setRouteRemain(points)
        osm?.setRouteRemain(points)
    }

    override fun setRouteDone(points: List<Pair<Double, Double>>) {
        libre?.setRouteDone(points)
        osm?.setRouteDone(points)
    }

    override fun setPreviewRoute(points: List<Pair<Double, Double>>) {
        libre?.setPreviewRoute(points)
        osm?.setPreviewRoute(points)
    }

    override fun setPreviewRoutes(
        selected: List<Pair<Double, Double>>,
        alternatives: List<List<Pair<Double, Double>>>,
    ) {
        libre?.setPreviewRoutes(selected, alternatives)
        osm?.setPreviewRoutes(selected, alternatives)
    }

    override fun clearPreviewRoute() {
        libre?.clearPreviewRoute()
        osm?.clearPreviewRoute()
    }

    override fun setToDest(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        libre?.setToDest(fromLat, fromLon, toLat, toLon)
        osm?.setToDest(fromLat, fromLon, toLat, toLon)
    }

    override fun clearToDest() {
        libre?.clearToDest()
        osm?.clearToDest()
    }

    override fun clearRoutes() {
        libre?.clearRoutes()
        osm?.clearRoutes()
    }

    override fun setMe(lat: Double, lon: Double, bearingDeg: Float) {
        libre?.setMe(lat, lon, bearingDeg)
        osm?.setMe(lat, lon, bearingDeg)
    }

    override fun setDestination(place: MapPlace?) {
        libre?.setDestination(place)
        osm?.setDestination(place)
    }

    override fun setPins(places: List<MapPlace>) {
        libre?.setPins(places)
        osm?.setPins(places)
    }

    override fun follow(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        zoom: Double,
        headingUp: Boolean,
        moving: Boolean,
    ) {
        libre?.follow(lat, lon, bearingDeg, zoom, headingUp, moving)
        osm?.follow(lat, lon, bearingDeg, zoom, headingUp, moving)
    }

    override fun setCenter(lat: Double, lon: Double, zoom: Double) {
        libre?.setCenter(lat, lon, zoom)
        osm?.setCenter(lat, lon, zoom)
    }

    override fun zoomToRoute(points: List<Pair<Double, Double>>, padPx: Int) {
        libre?.zoomToRoute(points, padPx)
        osm?.zoomToRoute(points, padPx)
    }

    override fun zoomToRoutes(routes: List<List<Pair<Double, Double>>>) {
        libre?.zoomToRoutes(routes)
        osm?.zoomToRoutes(routes)
    }

    override fun zoomBy(delta: Double) {
        libre?.zoomBy(delta)
        osm?.zoomBy(delta)
    }

    override fun resetNorth() {
        libre?.resetNorth()
        osm?.resetNorth()
    }

    override fun setOnLongPress(cb: (Double, Double) -> Unit) {
        libre?.setOnLongPress(cb)
        osm?.setOnLongPress(cb)
    }
}
