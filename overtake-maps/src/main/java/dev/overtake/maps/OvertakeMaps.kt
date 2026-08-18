// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import android.content.Context
import dev.overtake.maps.net.OvertakeHttp
import dev.overtake.maps.search.PlaceSearchImpl

/**
 * The library entry point. A host calls [create] once with an [OvertakeMapsConfig] and receives the
 * [MapProvider] to drive (a native map, or a hand-off to Google/Waze for Android Auto).
 *
 * Stage 1 wires the SEARCH capability: [create] returns a [MapProvider.Native] whose [PlaceSearchImpl]
 * runs the extracted place-search stack, with the renderer/router still [PendingRenderer]/[PendingRouter]
 * stubs (Stages 2-3 replace them). Constructing the stubs is free — they throw only if invoked — so the
 * search path never trips them.
 *
 * [context] is required because the platform Geocoder source ([dev.overtake.maps.search.AndroidGeocode])
 * needs one; the library holds only its `applicationContext`.
 */
object OvertakeMaps {

    fun create(context: Context, config: OvertakeMapsConfig): MapProvider {
        // Install the host's identifying User-Agent for all map HTTP (usage-policy requirement).
        OvertakeHttp.userAgent = config.userAgent
        return MapProvider.Native(
            renderer = PendingRenderer(),
            router = PendingRouter(),
            search = PlaceSearchImpl(config, context),
        )
    }
}
