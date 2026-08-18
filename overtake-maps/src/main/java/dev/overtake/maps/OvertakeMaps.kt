// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import android.content.Context
import dev.overtake.maps.net.OvertakeHttp
import dev.overtake.maps.route.RouterChain
import dev.overtake.maps.search.PlaceSearchImpl

/**
 * The library entry point. A host calls [create] once with an [OvertakeMapsConfig] and receives the
 * [MapProvider] to drive (a native map, or a hand-off to Google/Waze for Android Auto).
 *
 * [create] returns a [MapProvider.Native] whose [PlaceSearchImpl] runs the extracted place-search
 * stack (Stage 1) and whose [RouterChain] runs the extracted routing engine (Stage 2 — offline
 * BRouter / Valhalla / ORS / OSRM with the fork's connectivity-aware chooser). The renderer is still
 * a [PendingRenderer] stub (Stage 3 replaces it); constructing it is free — it throws only if invoked
 * — so the search + routing paths never trip it.
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
            // The routing engine is Context-based (offline graph dirs + BRouter assets under
            // filesDir) and needs only the host's effective ORS key from config; hold the app context
            // so the router outlives the caller's scope.
            router = RouterChain(context.applicationContext, config.defaultOrsApiKey),
            search = PlaceSearchImpl(config, context),
        )
    }
}
