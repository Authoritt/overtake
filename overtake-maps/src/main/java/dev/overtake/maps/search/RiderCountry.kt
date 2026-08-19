// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.search

import android.content.Context
import android.location.Geocoder
import android.os.Build
import dev.overtake.maps.MapsLog
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Which country the RIDER is standing in, as an ISO 3166-1 alpha-2 code — the value Nominatim's
 * `countrycodes` param takes (see [SearchUrls.nominatim]).
 *
 * Derived, never declared. The device locale would be the lazy answer and it is the wrong one: a
 * Colombian phone ridden into Ecuador still says `CO`, and a phone bought abroad says the wrong
 * thing at home. So this reverse-geocodes the rider's own fix with the PLATFORM geocoder (the same
 * backend [AndroidGeocode] already uses — no extra dependency, no extra service).
 *
 * Two rules make it safe to call from the search path:
 *  - it NEVER blocks. A cache miss returns null (the search then runs unrestricted, exactly as
 *    before) and resolves in the background so the NEXT search has it;
 *  - it is cached per ~55 km cell ([CELL_DEG]), so a day's riding costs a handful of lookups and
 *    crossing a border re-resolves instead of carrying the old country along.
 *
 * Library-neutral: nothing here knows which country it will find.
 */
internal object RiderCountry {

    /** Cell size for the cache, in degrees (~55 km). Small enough to notice a border crossing. */
    private const val CELL_DEG = 0.5

    private val byCell = ConcurrentHashMap<String, String>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()

    /**
     * The rider's country code for ([lat],[lon]) if it is already known, else null — and, on a miss,
     * a background resolve is kicked off. Deliberately non-suspending and instant.
     */
    fun codeFor(ctx: Context, lat: Double, lon: Double): String? {
        val cell = cellKey(lat, lon)
        byCell[cell]?.let { return it }
        if (Geocoder.isPresent() && resolving.add(cell)) {
            resolve(ctx.applicationContext, cell, lat, lon)
        }
        return null
    }

    private fun cellKey(lat: Double, lon: Double): String {
        val la = Math.floor(lat / CELL_DEG).toInt()
        val lo = Math.floor(lon / CELL_DEG).toInt()
        return "$la:$lo"
    }

    private fun store(cell: String, code: String?) {
        resolving.remove(cell)
        val c = code?.trim()?.lowercase(Locale.US) ?: return
        if (c.length == 2 && c.all { it in 'a'..'z' }) byCell[cell] = c
    }

    /**
     * One reverse geocode, off the calling thread. API 33+ gets the async listener overload; 29–32
     * only has the deprecated blocking one, so it runs on its own thread (same split as
     * [AndroidGeocode]). Any failure is logged and simply leaves the country unknown.
     */
    private fun resolve(ctx: Context, cell: String, lat: Double, lon: Double) {
        val geocoder = runCatching { Geocoder(ctx, Locale.getDefault()) }.getOrElse {
            store(cell, null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        store(cell, addresses.firstOrNull()?.countryCode)
                    }

                    override fun onError(errorMessage: String?) {
                        MapsLog.w("search", "rider country: reverse geocode failed: $errorMessage")
                        store(cell, null)
                    }
                })
            }.onFailure {
                MapsLog.w("search", "rider country: reverse geocode threw: $it")
                store(cell, null)
            }
        } else {
            thread(name = "rider-country") {
                val code = runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.countryCode
                }.getOrElse {
                    MapsLog.w("search", "rider country: reverse geocode threw: $it")
                    null
                }
                store(cell, code)
            }
        }
    }
}
