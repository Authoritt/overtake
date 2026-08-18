// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.search

import android.content.Context
import dev.overtake.maps.MapsLog
import dev.overtake.maps.OvertakeMapsConfig
import dev.overtake.maps.contract.PlaceSearch
import dev.overtake.maps.model.GeoPoint
import dev.overtake.maps.model.MapPlace
import dev.overtake.maps.model.PoiChip
import dev.overtake.maps.net.OvertakeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The [PlaceSearch] contract, assembling the three free-stack sources the OpenCfMoto cockpit tuned to
 * Google-Maps quality without a paid Places API:
 *  - [AndroidGeocode] — the platform Geocoder (Google-backed on a Play-Services device), for specific
 *    local street / neighbourhood addresses Photon/Nominatim miss;
 *  - [NominatimSearch] — Photon + Nominatim worldwide autocomplete, already cross-source merged, ranked
 *    and deduped internally (abbrev-expanded);
 *  - [OverpassSearch] — real "what's near me" category lookups for [poi].
 *
 * The cross-source MERGE for [query] (geocoder + the Nominatim result) was extracted from the fork's
 * `CockpitScreen` search overlay (`rankPicks`, network portion). It scores every candidate by the SAME
 * [NominatimSearch.relevance] blend the fork ranks against and dedupes by [NominatimSearch.dedupeKey],
 * so the fork can fold in its LOCAL recents/favourites/home with identical scoring and get a
 * byte-for-byte identical list. It deliberately does NOT cap the result (no `take(12)`): the fork adds
 * its own places to this pool and applies the final top-N, so truncating here would drop a network row
 * that a local dedupe-collision would otherwise have surfaced.
 */
internal class PlaceSearchImpl(
    private val config: OvertakeMapsConfig,
    context: Context,
) : PlaceSearch {

    private val appContext: Context = context.applicationContext

    init {
        // Apply the host's identifying User-Agent (OSM/Nominatim/Overpass usage policies require it),
        // so search HTTP carries it whether or not the facade already set it at create().
        OvertakeHttp.userAgent = config.userAgent
    }

    override val poiChips: List<PoiChip> = NominatimSearch.POI_CHIPS

    override suspend fun query(text: String, near: GeoPoint?): List<MapPlace> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val nearLat = near?.lat
        val nearLon = near?.lon
        return coroutineScope {
            // Both network sources run off the caller's thread, concurrently; each degrades to empty on
            // failure so a dead source never sinks the search (identical to the fork's additive merge).
            val geoDeferred = async(Dispatchers.IO) { geocode(q, nearLat, nearLon) }
            val nomDeferred = async(Dispatchers.IO) {
                runCatching { NominatimSearch.search(q, nearLat, nearLon, includeNominatim = true) }
                    .getOrElse { err ->
                        MapsLog.w("cockpit-search", "$err")
                        emptyList()
                    }
            }
            mergeRanked(q, nearLat, nearLon, geoDeferred.await(), nomDeferred.await())
        }
    }

    override suspend fun poi(chip: PoiChip, near: GeoPoint): List<MapPlace> =
        withContext(Dispatchers.IO) {
            OverpassSearch.nearbyByTag(chip.query, near.lat, near.lon)
        }

    /** Bridge [AndroidGeocode]'s thread+callback API to a suspend value — platform behaviour unchanged. */
    private suspend fun geocode(q: String, nearLat: Double?, nearLon: Double?): List<MapPlace> =
        suspendCancellableCoroutine { cont ->
            AndroidGeocode.searchAsync(
                appContext, q, nearLat, nearLon,
                onResult = { list -> if (cont.isActive) cont.resume(list) },
                onError = { err ->
                    MapsLog.w("cockpit-search", "geocoder: $err")
                    if (cont.isActive) cont.resume(emptyList())
                },
            )
        }

    /**
     * Merge the platform-Geocoder hits with the Nominatim result into one ranked, deduped list — the
     * network portion of the fork's `rankPicks`, EXACTLY: score by [NominatimSearch.relevance] (these
     * are all engine results, so no saved-place boost), sort descending, then keep the first (highest-
     * scored) row per [NominatimSearch.dedupeKey]. No `take(12)` (see the class doc).
     */
    private fun mergeRanked(
        q: String,
        nearLat: Double?,
        nearLon: Double?,
        geo: List<MapPlace>,
        nom: List<MapPlace>,
    ): List<MapPlace> {
        val ranked = (geo + nom)
            .map { p ->
                val dist = if (nearLat != null && nearLon != null) {
                    NominatimSearch.haversineKm(nearLat, nearLon, p.lat, p.lon)
                } else {
                    -1.0
                }
                p to NominatimSearch.relevance(q, p.name, p.subtitle, p.category, p.prominence, dist)
            }
            .sortedByDescending { it.second }
            .map { it.first }
        val seen = HashSet<String>()
        val out = ArrayList<MapPlace>(ranked.size)
        for (p in ranked) if (seen.add(NominatimSearch.dedupeKey(p))) out.add(p)
        return out
    }
}
