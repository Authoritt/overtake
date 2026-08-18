// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.model

/**
 * A computed road route and its turn-by-turn guidance. Mirrors the fork's `OsrmRouter.Route` /
 * `RouteStep` / `Lane`, with the geometry re-typed from `GpxPoint` to the neutral [GeoPoint].
 */
data class Route(
    /** The full polyline of the route, start to end. */
    val points: List<GeoPoint>,
    val distanceM: Double,
    val durationSec: Double,
    val steps: List<RouteStep> = emptyList(),
    /** Chip label: Fast / Fun / Alt / Circuit. */
    val label: String = "",
) {
    fun withLabel(label: String): Route = copy(label = label)
}

/** A single guidance step: the maneuver to take and the road you travel afterwards. */
data class RouteStep(
    val maneuverType: String,
    val modifier: String?,
    val roadName: String,
    val maneuverLat: Double,
    val maneuverLon: Double,
    val distanceM: Double,
    val exit: Int?,
    val lanes: List<Lane>?,
)

/** One turn lane from the routing intersections: which turns it allows + whether it's usable here. */
data class Lane(
    val valid: Boolean,
    val indications: List<String>,
)

/**
 * The outcome of a routing request: the [routes] found (primary first), whether the router could
 * actually honour the avoid-tolls / avoid-highways knobs ([avoidHonored] — the public OSRM demo
 * can't, ORS can), and an optional human-readable [warning] (e.g. a downgrade the caller may surface).
 */
data class RouteResult(
    val routes: List<Route>,
    val avoidHonored: Boolean = true,
    val warning: String? = null,
)
