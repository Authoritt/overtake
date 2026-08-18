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

    /** A scenic loop that starts and ends at [from], targeting [RouteOptions.circuitKm]. */
    suspend fun circuit(from: GeoPoint, options: RouteOptions): RouteResult

    /** True if an on-device offline routing graph covers both endpoints (no network needed). */
    fun hasOfflineGraph(from: GeoPoint, to: GeoPoint): Boolean
}
