// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.model

/**
 * A one-tap POI category shown as a chip in search UIs. [label] is what the user sees ("Fuel");
 * [query] is the machine key the search backend maps to a lookup (e.g. an Overpass `amenity=fuel`
 * tag filter). Field names mirror the consuming fork's chip EXACTLY so the fork can `typealias` its
 * own `PoiChip` to this one and every `chip.label` / `chip.query` access compiles unchanged.
 */
data class PoiChip(
    val label: String,
    val query: String,
)
