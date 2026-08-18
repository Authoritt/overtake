// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import dev.overtake.maps.route.offline.OfflineAreasStore

/**
 * The offline-data seam a host drives to manage downloaded map areas: list what's downloaded (with
 * on-disk size), download a new area (around the device or a searched place, at a detail level),
 * delete an area, and read/clear the bike dashboard raster tile cache. Obtained from
 * [MapProvider.Native.offline] (see [OvertakeMaps.create]); it holds the app context + config, so
 * calls take no [android.content.Context].
 *
 * The heavy engines behind it — MapLibre vector regions, the BRouter `.rd5` / Overpass offline
 * routing data, the area registry — are the extracted library engines, so every host drives the
 * identical download/route-build/delete flow.
 */
interface OfflineManager {

    /** Which phase of a download the progress callbacks are reporting. */
    enum class Phase { TILES, ROUTING }

    /** A geographic box (north/south latitudes, east/west longitudes). */
    data class Bbox(val north: Double, val south: Double, val east: Double, val west: Double)

    /** Every stored offline area (registry metadata; synchronous, no sizes). */
    fun storedAreas(): List<OfflineAreasStore.Area>

    /**
     * Real on-disk vector tile size per area name, from the MapLibre offline region DB. Async: the
     * result arrives on MapLibre's callback thread — marshal to your UI thread before rendering.
     */
    fun areaSizes(onResult: (Map<String, Long>) -> Unit)

    /**
     * Approx bounding box for a [radiusKm] circle around ([lat],[lon]) — the target for an
     * "around me" / "around a searched place" download.
     */
    fun bboxAround(lat: Double, lon: Double, radiusKm: Int): Bbox

    /**
     * Download the vector region for [bbox] (day + night styles) at Standard/High [highDetail], then
     * fetch/build its offline routing data, then persist the area. Callbacks fire on the engines'
     * threads — marshal to your UI thread.
     *
     * @param onDone `ok` = tiles downloaded (area persisted iff true); `routed` = offline turn-by-turn
     *   data obtained (a routing failure still leaves a usable map area); `message` a human status line.
     */
    fun download(
        name: String,
        bbox: Bbox,
        highDetail: Boolean,
        onPhase: (Phase) -> Unit,
        onProgress: (percent: Int, bytes: Long) -> Unit,
        onDone: (ok: Boolean, routed: Boolean, message: String) -> Unit,
    )

    /** Delete every artifact of a named area (tiles + routing data + registry entry). */
    fun delete(name: String, onDone: (ok: Boolean) -> Unit)

    /** Total bytes of the interactive bike-dashboard raster (osmdroid) tile cache on disk. */
    fun rasterCacheBytes(): Long

    /** Wipe the interactive bike-dashboard raster tile cache. */
    fun clearRasterCache()
}
