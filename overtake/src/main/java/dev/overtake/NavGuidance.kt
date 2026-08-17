// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
// Turn-by-turn guidance mirror — we do NOT project Google Maps / Waze. We read their *ongoing*
// navigation notification via [NowPlayingListener] and re-render the next maneuver as our own
// glanceable card. Read-only: the source of truth stays in the maps app; this is an echo.
// Populated by [CockpitNotifications].
package dev.overtake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable snapshot of the current navigation prompt for the UI to render. */
data class NavState(
    val active: Boolean = false,
    val instruction: String = "",
    val detail: String = "",
    val source: String = "",
)

/**
 * Process-global holder of the active navigation prompt, in the spirit of [NowPlaying].
 *
 * There is no lifecycle to manage here: the data is pushed in by [CockpitNotifications] whenever the
 * notification listener re-scans (posted/removed/connected). The UI only *observes* [state]; it never
 * starts or stops anything. When no ongoing Maps/Waze notification is present, [state] resets to an
 * inactive [NavState] and the card is hidden by the caller.
 */
object NavGuidance {

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Pushed by [CockpitNotifications] after each scan. StateFlow de-dupes structurally-equal values. */
    internal fun update(next: NavState) {
        _state.value = next
    }
}
