// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import dev.overtake.maps.contract.MapRenderer
import dev.overtake.maps.contract.Router
import dev.overtake.maps.model.GeoPoint
import dev.overtake.maps.model.MapPlace
import dev.overtake.maps.model.RouteOptions
import dev.overtake.maps.model.RouteResult

/**
 * Placeholder renderer/router that [OvertakeMaps.create] wires into a [MapProvider.Native] for the
 * search-only path (Stage 1). Constructing them is free — they throw ONLY if a method is actually
 * invoked — so a host that just wants [MapProvider.Native.search] never trips them. Stage 3 (renderer)
 * and Stage 2 (router) replace these with the real implementations moved from the fork.
 */

private fun pending(stage: String): Nothing =
    throw NotImplementedError("Overtake maps: this capability lands in $stage; only search is wired (Stage 1).")

internal class PendingRenderer : MapRenderer {
    override fun attach(context: Context, host: ViewGroup) = pending("Stage 3 (renderer)")
    override fun onCreate(savedInstanceState: Bundle?) = pending("Stage 3 (renderer)")
    override fun onResume() = pending("Stage 3 (renderer)")
    override fun onPause() = pending("Stage 3 (renderer)")
    override fun onStop() = pending("Stage 3 (renderer)")
    override fun onDestroy() = pending("Stage 3 (renderer)")
    override fun setBuildings3d(enabled: Boolean) = pending("Stage 3 (renderer)")
    override fun applyTheme(night: Boolean) = pending("Stage 3 (renderer)")
    override fun setRouteRemain(points: List<Pair<Double, Double>>) = pending("Stage 3 (renderer)")
    override fun setRouteDone(points: List<Pair<Double, Double>>) = pending("Stage 3 (renderer)")
    override fun setPreviewRoute(points: List<Pair<Double, Double>>) = pending("Stage 3 (renderer)")
    override fun setPreviewRoutes(
        selected: List<Pair<Double, Double>>,
        alternatives: List<List<Pair<Double, Double>>>,
    ) = pending("Stage 3 (renderer)")
    override fun clearPreviewRoute() = pending("Stage 3 (renderer)")
    override fun setToDest(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) = pending("Stage 3 (renderer)")
    override fun clearToDest() = pending("Stage 3 (renderer)")
    override fun clearRoutes() = pending("Stage 3 (renderer)")
    override fun setMe(lat: Double, lon: Double, bearingDeg: Float) = pending("Stage 3 (renderer)")
    override fun setDestination(place: MapPlace?) = pending("Stage 3 (renderer)")
    override fun setPins(places: List<MapPlace>) = pending("Stage 3 (renderer)")
    override fun follow(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        zoom: Double,
        headingUp: Boolean,
        moving: Boolean,
    ) = pending("Stage 3 (renderer)")
    override fun setCenter(lat: Double, lon: Double, zoom: Double) = pending("Stage 3 (renderer)")
    override fun zoomToRoute(points: List<Pair<Double, Double>>, padPx: Int) = pending("Stage 3 (renderer)")
    override fun zoomToRoutes(routes: List<List<Pair<Double, Double>>>) = pending("Stage 3 (renderer)")
    override fun zoomBy(delta: Double) = pending("Stage 3 (renderer)")
    override fun resetNorth() = pending("Stage 3 (renderer)")
    override fun setOnLongPress(cb: (Double, Double) -> Unit) = pending("Stage 3 (renderer)")
}

internal class PendingRouter : Router {
    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): RouteResult = pending("Stage 2 (router)")
    override suspend fun alternatives(from: GeoPoint, to: GeoPoint, options: RouteOptions): RouteResult = pending("Stage 2 (router)")
    override suspend fun circuit(from: GeoPoint, options: RouteOptions): RouteResult = pending("Stage 2 (router)")
    override fun hasOfflineGraph(from: GeoPoint, to: GeoPoint): Boolean = pending("Stage 2 (router)")
}
