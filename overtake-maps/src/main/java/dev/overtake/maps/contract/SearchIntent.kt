// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.contract

/**
 * WHY a place search is being run — and therefore which providers are allowed to serve it.
 *
 * This is a COMPLIANCE boundary, not a performance knob. The OSMF Nominatim usage policy
 * (https://operations.osmfoundation.org/policies/nominatim/) is categorical:
 *
 *   "Auto-complete search: This is not yet supported by Nominatim and you must not implement such a
 *    service on the client side using the API."
 *
 * Rate-limiting is NOT a way around it: even at one request per second, an as-you-type search shipped
 * in a public APK is exactly the aggregate pattern the policy exists to stop, and the penalty lands on
 * every rider using the app (OSMF throttles or blocks the endpoint), not on the developer who wrote
 * the loop.
 *
 * So the CALLER declares the trigger and the backend picks the providers. It is deliberately not a
 * timing heuristic ("the query looks settled"): a heuristic drifts back into autocomplete the moment
 * someone tunes a debounce, whereas this cannot be satisfied by typing at all — only by the rider
 * doing something.
 */
enum class SearchIntent {

    /**
     * The rider is TYPING and has not asked for anything yet (keystroke / debounced keystroke).
     * Autocomplete-capable providers only: Photon (a separate service, designed and licensed for
     * exactly this) and the platform Geocoder. **Nominatim is never contacted on this path — not even
     * as a fallback when the others come back empty.**
     */
    TYPEAHEAD,

    /**
     * The rider EXPLICITLY asked for this search: the keyboard's search/go key, a search button, a
     * destination sent from the phone. One deliberate action, one request — which is the pattern the
     * Nominatim policy allows, so the precise provider joins in here (still rate-limited, still with
     * an identifying User-Agent).
     */
    SUBMIT,
}
