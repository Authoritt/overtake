// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.model

/**
 * A named point of interest returned by [dev.overtake.maps.contract.PlaceSearch] and drawn as a pin
 * by [dev.overtake.maps.contract.MapRenderer]. [category]/[subtitle] are optional presentation hints
 * (e.g. "fuel", or a street line) that a host may show but need not.
 */
data class MapPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: String? = null,
    val subtitle: String? = null,
)
