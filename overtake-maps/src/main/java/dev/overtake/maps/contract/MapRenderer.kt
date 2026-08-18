// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.contract

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import dev.overtake.maps.model.MapPlace

/**
 * The View-host map renderer: it owns a map View inside a host [ViewGroup] and follows the rider.
 *
 * The method surface is a VERBATIM extraction of the fork's `DashMapEngine` public API (which fans a
 * single call out to whichever backend is live — MapLibre GL vector or osmdroid raster). The one
 * addition is [attach]: `DashMapEngine` took its `Context` + host in the constructor, but an
 * interface can't, so hosting is a first call instead. All coordinate pairs are (lat, lon), matching
 * the fork.
 *
 * Lifecycle ([onCreate]..[onDestroy]) mirrors the Android map-view lifecycle the backends require;
 * the host forwards its own Activity (or projected-display host-window) lifecycle here.
 */
interface MapRenderer {

    /** Create the backing map View and add it to [host]. Call once before any other method. */
    fun attach(context: Context, host: ViewGroup)

    fun onCreate(savedInstanceState: Bundle?)
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()

    /** Pitch the map and show extruded buildings when the active style supports them. */
    fun setBuildings3d(enabled: Boolean)

    /** Swap the day/night style (MapLibre) or tint (osmdroid). */
    fun applyTheme(night: Boolean)

    /** The not-yet-travelled part of the active route. */
    fun setRouteRemain(points: List<Pair<Double, Double>>)

    /** The already-travelled part of the active route (drawn dimmed behind the remainder). */
    fun setRouteDone(points: List<Pair<Double, Double>>)

    /** A single route shown before the rider commits to it (route preview / picker). */
    fun setPreviewRoute(points: List<Pair<Double, Double>>)

    /** A selected preview route plus its dimmed alternatives (multi-route picker). */
    fun setPreviewRoutes(
        selected: List<Pair<Double, Double>>,
        alternatives: List<List<Pair<Double, Double>>>,
    )

    fun clearPreviewRoute()

    /** Draw a straight guide line from (fromLat, fromLon) to (toLat, toLon) — the pre-route beeline. */
    fun setToDest(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double)

    fun clearToDest()

    fun clearRoutes()

    /** Place the "me" puck and point it [bearingDeg] degrees clockwise from north. */
    fun setMe(lat: Double, lon: Double, bearingDeg: Float = 0f)

    fun setDestination(place: MapPlace?)

    fun setPins(places: List<MapPlace>)

    /**
     * Camera-follow the rider. [headingUp] rotates the map to the direction of travel (vs north-up);
     * [moving] lets the backend pick a smoother animation while under way.
     */
    fun follow(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        zoom: Double,
        headingUp: Boolean = true,
        moving: Boolean = true,
    )

    fun setCenter(lat: Double, lon: Double, zoom: Double = 16.0)

    /** Fit the camera to a single route with [padPx] screen padding. */
    fun zoomToRoute(points: List<Pair<Double, Double>>, padPx: Int = 72)

    /** Fit the camera to several routes at once (the preview picker's overview). */
    fun zoomToRoutes(routes: List<List<Pair<Double, Double>>>)

    fun zoomBy(delta: Double)

    fun resetNorth()

    /** Report a long-press on the map as (lat, lon) — the host turns it into a destination. */
    fun setOnLongPress(cb: (Double, Double) -> Unit)
}
