// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import dev.overtake.maps.contract.MapRenderer
import dev.overtake.maps.contract.PlaceSearch
import dev.overtake.maps.contract.Router

/**
 * What Overtake hands back from [OvertakeMaps.create]. Either Overtake drives the map itself
 * ([Native], bundling the three capability contracts), or the host defers to a first-party
 * Android Auto navigation app ([GoogleAA] / [WazeAA]) and Overtake only supplies search/metadata.
 */
sealed interface MapProvider {

    /** Overtake renders, routes, searches and manages offline data in-process via its own map stack. */
    data class Native(
        val renderer: MapRenderer,
        val router: Router,
        val search: PlaceSearch,
        val offline: OfflineManager,
    ) : MapProvider

    /** Hand navigation to Google Maps for Android Auto. */
    data object GoogleAA : MapProvider

    /** Hand navigation to Waze for Android Auto. */
    data object WazeAA : MapProvider
}
