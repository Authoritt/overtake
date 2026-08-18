// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.search

import dev.overtake.maps.model.MapPlace
import dev.overtake.maps.net.OvertakeHttp
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos

/**
 * The Overpass POI-category source: finds OSM features carrying a tag (e.g. `amenity=fuel`) within a
 * radius of the rider, nearest first — what the [dev.overtake.maps.contract.PlaceSearch.poi] chips
 * need (actual fuel stations / cafes around you, not a text search for the word "fuel").
 *
 * This is the search half of the fork's `OverpassClient`, extracted VERBATIM (behaviour byte-for-byte
 * identical to search-parity); the only edits are `AppHttp.*` -> [OvertakeHttp] and the neutral
 * [MapPlace] model. The fork's maxspeed / route-corridor helpers stay in the fork: they speak the
 * host's own `GpxPoint` / `MapUnits` (dash + GPX concerns), not this library's neutral models.
 */
object OverpassSearch {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    fun nearbyByTagAsync(
        tag: String,
        lat: Double,
        lon: Double,
        radiusM: Int = 6_000,
        maxResults: Int = 30,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "overpass-nearby") {
            try {
                onResult(nearbyByTag(tag, lat, lon, radiusM, maxResults))
            } catch (e: Exception) {
                onError(e.message ?: "Nearby search failed")
            }
        }
    }

    fun nearbyByTag(
        tag: String,
        lat: Double,
        lon: Double,
        radiusM: Int = 6_000,
        maxResults: Int = 30,
    ): List<MapPlace> {
        val key = tag.substringBefore('=').trim()
        val value = tag.substringAfter('=', "").trim()
        if (key.isEmpty() || value.isEmpty()) return emptyList()
        // Widen the search if the immediate area comes up empty (e.g. fuel out on the open road).
        for (r in intArrayOf(radiusM, radiusM * 3, radiusM * 8)) {
            val found = queryNearby(key, value, lat, lon, r, maxResults)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun queryNearby(
        key: String,
        value: String,
        lat: Double,
        lon: Double,
        radiusM: Int,
        maxResults: Int,
    ): List<MapPlace> {
        val q = """
            [out:json][timeout:25];
            (
              node["$key"="$value"](around:$radiusM,$lat,$lon);
              way["$key"="$value"](around:$radiusM,$lat,$lon);
            );
            out center ${maxResults * 2};
        """.trimIndent()
        val els = post(q).optJSONArray("elements") ?: return emptyList()
        val scored = ArrayList<Pair<MapPlace, Double>>(els.length())
        for (i in 0 until els.length()) {
            val o = els.getJSONObject(i)
            val plat: Double
            val plon: Double
            when {
                o.has("lat") && o.has("lon") -> {
                    plat = o.getDouble("lat"); plon = o.getDouble("lon")
                }
                o.has("center") -> {
                    val c = o.getJSONObject("center")
                    plat = c.getDouble("lat"); plon = c.getDouble("lon")
                }
                else -> continue
            }
            val tags = o.optJSONObject("tags")
            val brand = tags?.optString("brand")?.takeIf { it.isNotBlank() }
                ?: tags?.optString("operator")?.takeIf { it.isNotBlank() }
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: brand
                ?: value.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val street = listOfNotNull(
                tags?.optString("addr:street")?.takeIf { it.isNotBlank() },
                tags?.optString("addr:housenumber")?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            val subtitle = listOfNotNull(
                brand?.takeIf { it != name },
                street.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            scored.add(
                MapPlace(
                    name = name,
                    lat = plat,
                    lon = plon,
                    category = value,
                    subtitle = subtitle,
                ) to approxMetres(lat, lon, plat, plon),
            )
        }
        return scored
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { "${"%.5f".format(it.lat)},${"%.5f".format(it.lon)}" }
            .take(maxResults)
    }

    private fun post(query: String): JSONObject {
        val body = "data=" + OvertakeHttp.encode(query)
        // Overpass fair-use: keep the request rate modest from a single client.
        OvertakeHttp.throttle("overpass-api.de", 1_000)
        return JSONObject(OvertakeHttp.postForm(ENDPOINT, body))
    }

    /** Rough metres between two lat/lon — used by callers to sort POIs by distance. */
    fun approxMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = abs(lat1 - lat2) * 111_320.0
        val dLon = abs(lon1 - lon2) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.hypot(dLat, dLon)
    }
}
