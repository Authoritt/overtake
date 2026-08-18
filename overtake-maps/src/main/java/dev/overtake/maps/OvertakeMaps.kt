// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import android.content.Context
import dev.overtake.maps.contract.MapRenderer
import dev.overtake.maps.net.OvertakeHttp
import dev.overtake.maps.render.DashMapEngine
import dev.overtake.maps.route.RouterChain
import dev.overtake.maps.search.PlaceSearchImpl

/**
 * The library entry point. A host calls [create] once with an [OvertakeMapsConfig] and receives the
 * [MapProvider] to drive (a native map, or a hand-off to Google/Waze for Android Auto).
 *
 * [create] returns a [MapProvider.Native] whose [PlaceSearchImpl] runs the extracted place-search
 * stack (Stage 1), whose [RouterChain] runs the extracted routing engine (Stage 2 — offline
 * BRouter / Valhalla / ORS / OSRM with the fork's connectivity-aware chooser) and whose renderer is
 * the extracted map stack (Stage 3 — [DashMapEngine], MapLibre GL vector or osmdroid raster per
 * [OvertakeMapsConfig.rendererKind]). The renderer is un-attached; the host calls
 * [MapRenderer.attach] with its own host [android.view.ViewGroup] to bring it up. A fresh renderer is
 * returned per [create] call, so each host surface gets its own.
 *
 * [context] is required because the platform Geocoder source ([dev.overtake.maps.search.AndroidGeocode])
 * needs one; the library holds only its `applicationContext`.
 */
object OvertakeMaps {

    fun create(context: Context, config: OvertakeMapsConfig): MapProvider {
        // Install the host's identifying User-Agent for all map HTTP (usage-policy requirement).
        OvertakeHttp.userAgent = config.userAgent
        val appContext = context.applicationContext
        return MapProvider.Native(
            renderer = DashMapEngine(config),
            // The routing engine is Context-based (offline graph dirs + BRouter assets under
            // filesDir) and needs only the host's effective ORS key from config; hold the app context
            // so the router outlives the caller's scope.
            router = RouterChain(appContext, config.defaultOrsApiKey),
            search = PlaceSearchImpl(config, context),
            // Offline data management (downloaded areas + routing data + raster cache). Context-based
            // (all state under config.filesDir); holds the app context so it outlives the caller.
            offline = NativeOfflineManager(appContext, config),
        )
    }

    /**
     * DEV PROBE ONLY — an un-attached [MapRenderer] pinned to MapLibre regardless of
     * [OvertakeMapsConfig.rendererKind] or the host [Context] type. Used to measure MapLibre's output
     * on an encoder-backed off-screen host in isolation; has no production role. The host calls
     * [MapRenderer.attach] as usual.
     */
    fun createDevProbeRenderer(config: OvertakeMapsConfig): MapRenderer =
        DashMapEngine(config, forceLibre = true)
}
