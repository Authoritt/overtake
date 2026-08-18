// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.model

/** How to pick roads: Fast = quickest normal roads; Fun = twistier / scenic detours. */
enum class RouteMode(val label: String) {
    FAST("Fast"),
    FUN("Fun"),
}

/**
 * Routing knobs passed into every [dev.overtake.maps.contract.Router] request. Mirrors the fork's
 * `RouteOptions` (fed from its `MapPrefs`): mode, the two avoid toggles, how many alternatives to ask
 * for, and the target loop length for [dev.overtake.maps.contract.Router.circuit].
 */
data class RouteOptions(
    val mode: RouteMode = RouteMode.FAST,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    /** Extra alternatives beyond the primary (OSRM `alternatives=N` -> up to N+1 total). */
    val maxAlternatives: Int = 4,
    /** Target length for Fun -> Create circuit (km). */
    val circuitKm: Int = 60,
) {
    val wantsAvoids: Boolean get() = avoidTolls || avoidHighways
}
