// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

/**
 * The library entry point. A host calls [create] once with an [OvertakeMapsConfig] and receives the
 * [MapProvider] to drive (a native map, or a hand-off to Google/Waze for Android Auto).
 *
 * SKELETON (Stage 0): the wiring is a [TODO]; Stages 1-4 move the fork's real renderer, router and
 * search implementations in behind the contracts and assemble a [MapProvider.Native] here.
 */
object OvertakeMaps {

    private const val TAG = "OvertakeMaps"

    fun create(config: OvertakeMapsConfig): MapProvider {
        MapsLog.w(TAG, "create(rendererKind=${config.rendererKind}): not wired yet — Stages 1-4")
        TODO("stages 1-4 wire the real impls (renderer/router/search into MapProvider.Native)")
    }
}
