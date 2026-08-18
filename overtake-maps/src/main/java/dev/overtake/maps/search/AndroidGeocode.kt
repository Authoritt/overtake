// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.search

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dev.overtake.maps.model.MapPlace
import kotlin.concurrent.thread

/**
 * Second place-search source: the PLATFORM Geocoder ([android.location.Geocoder]).
 *
 * On a device with Google Play Services (e.g. a Xiaomi) the platform Geocoder is backed by Google's
 * geocoding service — free, no API key — so it resolves specific local street / neighbourhood
 * addresses the way Google Maps does. That fills the gap where the free Photon/Nominatim path misses
 * a real local address (measured miss: "rincon de la flora 1", a barrio in Cali, CO).
 *
 * This is ADDITIVE to [NominatimSearch]: both sources run and the caller merges their results.
 * Everything runs off the main thread; any failure logs and yields an empty list (never crashes,
 * never blocks). When no geocoding backend is present the source skips silently (empty) and
 * Nominatim still covers the search.
 */
object AndroidGeocode {

    /** ~1.5° box around the map center — the same soft regional bias NominatimSearch uses (viewbox d≈1.6). */
    private const val BIAS_DEG = 1.5
    private const val MAX_RESULTS = 6

    /**
     * Query the platform Geocoder for [query], biased to a box around ([nearLat],[nearLon]) when both
     * are given (else the unbounded overload). Calls [onResult] with a possibly-empty list of
     * [MapPlace], or [onError] with a message. Neither callback runs on the calling thread's work:
     *
     * - API 33+ (TIRAMISU): the async `getFromLocationName(..., GeocodeListener)` overload — the
     *   platform runs the lookup off our thread and invokes the listener on a binder thread.
     * - API 29–32: the only overload is the **deprecated synchronous** one, so it is run on a
     *   dedicated background thread (it must never touch the main thread — it does blocking I/O).
     *
     * The caller is responsible for marshalling results back to the UI thread (as the existing
     * NominatimSearch path does), so both callbacks may arrive on a non-main thread.
     */
    fun searchAsync(
        ctx: Context,
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val q = query.trim()
        if (q.isEmpty()) {
            onResult(emptyList())
            return
        }
        // No geocoding backend on this device — skip silently, Nominatim still runs.
        if (!Geocoder.isPresent()) {
            onResult(emptyList())
            return
        }

        val geocoder = Geocoder(ctx.applicationContext)
        val box = if (nearLat != null && nearLon != null) {
            ViewBox(
                llLat = nearLat - BIAS_DEG,
                llLon = nearLon - BIAS_DEG,
                urLat = nearLat + BIAS_DEG,
                urLon = nearLon + BIAS_DEG,
            )
        } else {
            null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: async overload. The listener fires later on a binder thread.
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    onResult(map(addresses))
                }

                override fun onError(errorMessage: String?) {
                    onError(errorMessage ?: "geocoder error")
                }
            }
            runCatching {
                if (box != null) {
                    geocoder.getFromLocationName(
                        q, MAX_RESULTS,
                        box.llLat, box.llLon, box.urLat, box.urLon,
                        listener,
                    )
                } else {
                    geocoder.getFromLocationName(q, MAX_RESULTS, listener)
                }
            }.onFailure { onError(it.message ?: "geocoder failed") }
        } else {
            // API 29–32: the deprecated synchronous overload — blocking I/O, never on the main thread.
            thread(name = "android-geocode") {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = if (box != null) {
                        geocoder.getFromLocationName(
                            q, MAX_RESULTS,
                            box.llLat, box.llLon, box.urLat, box.urLon,
                        )
                    } else {
                        geocoder.getFromLocationName(q, MAX_RESULTS)
                    }
                    onResult(map(addresses ?: emptyList()))
                } catch (e: Exception) {
                    onError(e.message ?: "geocoder failed")
                }
            }
        }
    }

    /** Bounding box for the location-biased overload (lower-left / upper-right corners). */
    private data class ViewBox(
        val llLat: Double,
        val llLon: Double,
        val urLat: Double,
        val urLon: Double,
    )

    /**
     * [android.location.Address] → [MapPlace]. Drops any address without a real lat/lon fix.
     * name = featureName, else thoroughfare, else the head of address line 0, else "Lugar";
     * subtitle = the full address line 0; category = "address" (used by the merge to rank
     * specific address hits above generic city hits).
     */
    private fun map(addresses: List<Address>): List<MapPlace> = buildList {
        for (a in addresses) {
            if (!a.hasLatitude() || !a.hasLongitude()) continue
            val line0 = runCatching { a.getAddressLine(0) }.getOrNull()
            // featureName is often a bare house number ("5") — useless as a title; fall back to the
            // street / neighbourhood / locality so the row reads like a place, not a digit.
            val name = a.featureName?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                ?: a.thoroughfare
                ?: a.subLocality
                ?: a.locality
                ?: line0?.substringBefore(',')
                ?: "Lugar"
            add(
                MapPlace(
                    name = name,
                    lat = a.latitude,
                    lon = a.longitude,
                    category = "address",
                    subtitle = line0 ?: "",
                    // A specific matched address is moderately prominent — the name/address match carries
                    // the weight; this just keeps it competitive with a generic same-name city hit.
                    prominence = 0.2,
                ),
            )
        }
    }
}
