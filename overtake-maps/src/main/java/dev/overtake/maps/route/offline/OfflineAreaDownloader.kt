// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.route.offline

import android.content.Context
import dev.overtake.maps.MapsLog
import dev.overtake.maps.OfflineManager
import dev.overtake.maps.route.BrouterRouter
import dev.overtake.maps.route.OfflineRoadGraph
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Reusable orchestration to download / delete a named offline area, so every host (the native map
 * hub screens and the classic map activity alike) drives the EXACT same engine:
 *  - MapLibre vector tiles ([MapOfflineManager]) for both day + night styles,
 *  - the on-device routing data — BRouter `.rd5` segment tiles ([BrouterRouter]) as the primary
 *    engine, the Overpass-built [OfflineRoadGraph] as the fallback for areas with no `.rd5`,
 *  - the named-area registry ([OfflineAreasStore]) a host lists.
 *
 * Nothing here re-implements a download: the heavy engines stay shared. All engines are
 * [Context]-based, so no Activity is required. Callbacks fire on MapLibre's callback thread (tiles)
 * and on a worker thread (routing build); the caller marshals them to its UI thread.
 *
 * Internal: hosts reach this through the [OfflineManager] facade returned by
 * [dev.overtake.maps.OvertakeMaps.create], never directly.
 */
internal object OfflineAreaDownloader {

    /** Detail → max zoom: Standard (15) or High (16). */
    fun zoomMax(highDetail: Boolean): Int =
        if (highDetail) OfflineAreasStore.AREA_ZOOM_HIGH_MAX else OfflineAreasStore.AREA_ZOOM_STANDARD_MAX

    /**
     * Approx bounding box for a [radiusKm] circle around a point (111.32 km/deg approximation, with
     * longitude scaled by cos(lat), clamped so poles don't blow up).
     */
    fun bboxAround(lat: Double, lon: Double, radiusKm: Int): OfflineManager.Bbox {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return OfflineManager.Bbox(lat + dLat, lat - dLat, lon + dLon, lon - dLon)
    }

    /**
     * Download the MapLibre vector region (day + night) for [bbox], then fetch/build the offline
     * routing data for it, then persist it to [OfflineAreasStore].
     *
     * @param styleDayUrl / [styleNightUrl] the MapLibre GL styles to download tiles for (host config).
     * @param onPhase called when the visible phase changes (tiles -> routing).
     * @param onProgress aggregate 0..100 + downloaded bytes.
     * @param onDone final result: `ok` = tiles downloaded (area persisted iff true); `routed` = offline
     *   turn-by-turn routing data was obtained (a routing failure still leaves a usable map area,
     *   only offline routing is missing); `message` a human status line.
     */
    fun download(
        ctx: Context,
        name: String,
        bbox: OfflineManager.Bbox,
        highDetail: Boolean,
        styleDayUrl: String,
        styleNightUrl: String,
        onPhase: (OfflineManager.Phase) -> Unit,
        onProgress: (percent: Int, bytes: Long) -> Unit,
        onDone: (ok: Boolean, routed: Boolean, message: String) -> Unit,
    ) {
        val zMax = zoomMax(highDetail)
        val bounds = LatLngBounds.Builder()
            .include(LatLng(bbox.north, bbox.east))
            .include(LatLng(bbox.south, bbox.west))
            .build()
        val styles = listOf(
            styleDayUrl to false,
            styleNightUrl to true,
        )
        onPhase(OfflineManager.Phase.TILES)
        MapOfflineManager.downloadArea(
            ctx,
            name,
            bounds,
            OfflineAreasStore.AREA_ZOOM_MIN.toDouble(),
            zMax.toDouble(),
            styles,
            onProgress = onProgress,
            onDone = { ok, message ->
                if (!ok) {
                    onDone(false, false, message)
                } else {
                    // Tiles are down -> fetch the offline ROUTING data for this bbox. Primary engine
                    // is BRouter: download its .rd5 segment tile(s) (a real routing engine). Only if
                    // that yields no usable data do we fall back to the weak Overpass-built graph, so
                    // the area is still routable offline where possible.
                    onPhase(OfflineManager.Phase.ROUTING)
                    kotlin.concurrent.thread(name = "offline-route-build") {
                        val seg = runCatching {
                            BrouterRouter.downloadSegmentsForBbox(
                                ctx, bbox.south, bbox.west, bbox.north, bbox.east,
                            ) { done, total, bytes ->
                                onProgress(if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0, bytes)
                            }
                        }.onFailure {
                            MapsLog.w("route", "[route] BRouter segment fetch failed: ${it.message}")
                        }.getOrNull()

                        val routed = if (seg != null && seg.hasRouting) {
                            true
                        } else {
                            // No .rd5 for the area (all failed / offline) -> Overpass graph fallback.
                            runCatching {
                                OfflineRoadGraph.buildForArea(
                                    ctx, name, bbox.south, bbox.west, bbox.north, bbox.east,
                                    onProgress = {},
                                )
                                true
                            }.getOrElse { e ->
                                MapsLog.w("route", "[route] offline routing build failed: ${e.message}")
                                false
                            }
                        }
                        OfflineAreasStore.add(
                            ctx,
                            OfflineAreasStore.Area(
                                name = name,
                                north = bbox.north, south = bbox.south, east = bbox.east, west = bbox.west,
                                zoomMax = zMax, vector = true, raster = false,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        onDone(
                            true,
                            routed,
                            if (routed) {
                                "Área \"$name\" lista (mapa + rutas sin conexión)"
                            } else {
                                "Área \"$name\" lista (mapa; rutas sin conexión no disponibles)"
                            },
                        )
                    }
                }
            },
        )
    }

    /**
     * Delete every artifact of a named area: the MapLibre regions ([MapOfflineManager]), the offline
     * routing graph ([OfflineRoadGraph]), the BRouter `.rd5` tiles not shared with another area
     * ([BrouterRouter.pruneSegments]) and the registry entry ([OfflineAreasStore]). [onDone] fires on
     * MapLibre's callback thread.
     */
    fun delete(ctx: Context, name: String, onDone: (ok: Boolean) -> Unit) {
        MapOfflineManager.deleteArea(ctx, name) { ok ->
            OfflineAreasStore.remove(ctx, name)
            OfflineRoadGraph.deleteForArea(ctx, name)
            // Prune .rd5 tiles no remaining area needs (they're shared 5° cells — ref-count by bbox).
            runCatching {
                BrouterRouter.pruneSegments(
                    ctx,
                    OfflineAreasStore.list(ctx).map { BrouterRouter.Bbox(it.south, it.west, it.north, it.east) },
                )
            }
            onDone(ok)
        }
    }
}
