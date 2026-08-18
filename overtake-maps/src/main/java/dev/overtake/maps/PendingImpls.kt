// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import dev.overtake.maps.contract.MapRenderer
import dev.overtake.maps.model.MapPlace

/**
 * Placeholder renderer that [OvertakeMaps.create] wires into a [MapProvider.Native]. Constructing it
 * is free — it throws ONLY if a method is actually invoked — so a host that just wants
 * [MapProvider.Native.search] (or the now-real [MapProvider.Native.router]) never trips it. Stage 3
 * (renderer) replaces this with the real implementation moved from the fork; the router landed in
 * Stage 2 ([dev.overtake.maps.route.RouterChain]).
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
