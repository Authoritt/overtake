// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.model

/**
 * A one-tap POI category shown as a chip in search UIs. [label] is what the user sees ("Fuel");
 * [tag] is the machine key the search backend maps to a query (e.g. an Overpass amenity value).
 */
data class PoiChip(
    val label: String,
    val tag: String,
)
