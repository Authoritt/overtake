// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.model

/**
 * A named point of interest returned by [dev.overtake.maps.contract.PlaceSearch] and drawn as a pin
 * by [dev.overtake.maps.contract.MapRenderer]. [category]/[subtitle] are presentation hints (e.g.
 * "fuel", or a street line) that a host may show; both default to the empty string (never null) so
 * the persistence + search code paths that fold/trim them stay null-safe.
 *
 * Field shape mirrors the consuming fork's `MapPlace` EXACTLY (name, lat, lon, category, subtitle,
 * prominence) so the fork can `typealias` its own model to this one and every existing positional /
 * named construction, save/load and scorer call compiles unchanged.
 */
data class MapPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: String = "",
    val subtitle: String = "",
    /**
     * Transient ranking signal (0..~1) carried from the search source's own prominence — the
     * geocoder's `importance` + settlement extent — so a merge can rank a well-known POI / main road
     * above an obscure node. NOT persisted: a host's save/load writes only the five durable fields, so
     * a reloaded favourite/recent comes back at 0.0 (pinned items are boosted by being pinned, not by
     * this). Kept LAST so positional constructors of the first five fields are unaffected.
     */
    val prominence: Double = 0.0,
)
