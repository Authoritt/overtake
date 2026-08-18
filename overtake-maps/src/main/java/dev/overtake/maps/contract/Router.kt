// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.contract

import dev.overtake.maps.model.GeoPoint
import dev.overtake.maps.model.RouteOptions
import dev.overtake.maps.model.RouteResult

/**
 * Computes road routes. Implementations pick the backend by availability: an offline BRouter graph
 * when present (see [hasOfflineGraph]), otherwise the online OSRM/ORS path. Suspending; call from a
 * coroutine off the main thread.
 */
interface Router {

    /** The primary road route from [from] to [to] under [options]. */
    suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): RouteResult

    /** The route plus up to [RouteOptions.maxAlternatives] alternatives. */
    suspend fun alternatives(from: GeoPoint, to: GeoPoint, options: RouteOptions): RouteResult

    /**
     * An out-and-back route: travel from [from] to [to], then return to [from] (a via at the
     * turnaround), delivered as a single route. This is the fork's "Circuit" / there-and-back ride.
     *
     * (The scaffold originally typed this as a destination-less scenic loop keyed on
     * [RouteOptions.circuitKm]; the only live fork feature is the out-and-back to a picked place, so
     * the contract takes a [to]. The km-target loop generators — `FunRoutePlanner.circuitWaypoints`,
     * `OrsRouter.roundTrip` — moved in with the engine but stay unwired, exactly as in the fork.)
     */
    suspend fun circuit(from: GeoPoint, to: GeoPoint, options: RouteOptions): RouteResult

    /** True if an on-device offline routing graph covers both endpoints (no network needed). */
    fun hasOfflineGraph(from: GeoPoint, to: GeoPoint): Boolean
}
