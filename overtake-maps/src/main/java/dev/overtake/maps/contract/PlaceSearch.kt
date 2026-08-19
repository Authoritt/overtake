// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.contract

import dev.overtake.maps.model.GeoPoint
import dev.overtake.maps.model.MapPlace
import dev.overtake.maps.model.PoiChip

/**
 * Finds places: free-text geocoding and one-tap POI category lookups. Suspending; call off the main
 * thread. [near] biases / centres results on the rider's location when supplied.
 */
interface PlaceSearch {

    /**
     * Free-text geocode; [near] biases ranking toward the rider when given.
     *
     * [intent] says what the rider DID, and that decides which providers may run — see [SearchIntent]
     * for the Nominatim usage policy this enforces
     * (https://operations.osmfoundation.org/policies/nominatim/). It defaults to the SAFE value:
     * a caller who says nothing is assumed to be typing, so forgetting the argument can only ever
     * under-use a provider, never break the policy.
     */
    suspend fun query(
        text: String,
        near: GeoPoint? = null,
        intent: SearchIntent = SearchIntent.TYPEAHEAD,
    ): List<MapPlace>

    /** All places of a POI category ([chip]) around [near]. */
    suspend fun poi(chip: PoiChip, near: GeoPoint): List<MapPlace>

    /** The POI category chips this backend offers (Fuel, Food, ...). */
    val poiChips: List<PoiChip>
}
