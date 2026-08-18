// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.search

import dev.overtake.maps.MapsLog
import dev.overtake.maps.model.MapPlace
import dev.overtake.maps.model.PoiChip
import dev.overtake.maps.net.OvertakeHttp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Worldwide place search / autocomplete for Map.
 * Uses Photon (location-biased) + settlement query so short prefixes prefer major
 * nearby cities over tiny locals / far-away name collisions. No region hardcoding.
 *
 * Extracted VERBATIM from the OpenCfMoto fork (behaviour byte-for-byte identical to search-parity);
 * the only edits are the module seams — `AppHttp.*` -> [OvertakeHttp] and the `LogBus` sink -> [MapsLog]
 * — and the neutral [MapPlace] / [PoiChip] model types. The scorer ([relevance] / [matchScore] / the
 * fuzzy [fold] / [dedupe]) is the single source of truth the cockpit search overlay ranks against.
 */
object NominatimSearch {

    val POI_CHIPS = listOf(
        PoiChip("Fuel", "amenity=fuel"),
        PoiChip("Cafe", "amenity=cafe"),
        PoiChip("Food", "amenity=restaurant"),
        PoiChip("Parking", "amenity=parking"),
        PoiChip("Hotel", "tourism=hotel"),
        PoiChip("ATM", "amenity=atm"),
        PoiChip("Hospital", "amenity=hospital"),
        PoiChip("Viewpoint", "tourism=viewpoint"),
    )

    fun searchAsync(
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
        includeNominatim: Boolean = false,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "place-search") {
            try {
                onResult(search(query, nearLat, nearLon, includeNominatim))
            } catch (e: Exception) {
                onError(e.message ?: "Search failed")
            }
        }
    }

    /**
     * @param includeNominatim when biased (nearLat/nearLon given), also fetch Nominatim and rank it
     *   TOGETHER with Photon instead of only falling back to it when Photon is empty. Photon is
     *   POI/settlement-first and can return a nearby-but-wrong hit for a specific local street or
     *   barrio (measured: "rincón de la flora 1" in Cali — Photon returns unrelated places, so the
     *   real Nominatim result was never fetched and the search "missed" a place Google finds). Off by
     *   default so the dash/other callers are byte-for-byte unchanged; the in-cockpit search opts in.
     *   No effect on the unbiased path, which already queries Nominatim directly.
     */
    fun search(
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
        includeNominatim: Boolean = false,
    ): List<MapPlace> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        // Expand street abbreviations up front ("cra 100" -> "carrera 100", "av" -> "avenida"). Photon
        // does NOT expand them (measured: "cra 100" returns nothing useful), Nominatim mostly does; a
        // single expanded query feeds both and the ranker, so the cache key is the expanded form too.
        val q = expandQuery(raw)

        val cacheKey = cacheKey(q, nearLat, nearLon, includeNominatim)
        cacheGet(cacheKey)?.let { return it }

        val result = if (nearLat != null && nearLon != null) {
            if (includeNominatim) {
                // Merge both sources so a specific local address (Nominatim) is never shadowed by
                // Photon's nearby-but-wrong POIs; rankScored then orders by distance + name + type.
                val photon = runCatching { photonScored(q, nearLat, nearLon) }.getOrElse { err ->
                    MapsLog.w("search", "Photon failed: $err")
                    emptyList()
                }
                val nominatim = runCatching { searchNominatim(q, nearLat, nearLon) }.getOrElse { err ->
                    MapsLog.w("search", "Nominatim failed: $err")
                    emptyList()
                }
                rankScored(photon + nominatim, q, nearLat, nearLon)
            } else {
                val photon = runCatching { searchPhotonBiased(q, nearLat, nearLon) }.getOrElse { err ->
                    MapsLog.w("search", "Photon failed: $err")
                    emptyList()
                }
                if (photon.isNotEmpty()) photon
                else rankScored(searchNominatim(q, nearLat, nearLon), q, nearLat, nearLon)
            }
        } else {
            rankScored(searchNominatim(q, nearLat, nearLon), q, nearLat, nearLon)
        }
        if (result.isNotEmpty()) cachePut(cacheKey, result)
        return result
    }

    // --- Tiny LRU cache so retyping / re-searching the same thing doesn't re-hit the network. ---
    private const val CACHE_MAX = 64
    private val cache = object : LinkedHashMap<String, List<MapPlace>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MapPlace>>) =
            size > CACHE_MAX
    }

    private fun cacheKey(q: String, lat: Double?, lon: Double?, includeNominatim: Boolean): String {
        val near = if (lat != null && lon != null) "%.2f,%.2f".format(lat, lon) else "-"
        return "${q.lowercase()}|$near|${if (includeNominatim) "n1" else "n0"}"
    }

    private fun cacheGet(key: String): List<MapPlace>? = synchronized(cache) { cache[key] }
    private fun cachePut(key: String, value: List<MapPlace>) { synchronized(cache) { cache[key] = value } }

    /**
     * Two Photon queries, unranked (for the caller to rank, alone or merged with Nominatim):
     * 1) general autocomplete (POIs / streets / everything near you)
     * 2) settlements only (city/town/municipality) so "Cons" → Constanța, not a metro stop
     */
    private fun photonScored(query: String, nearLat: Double, nearLon: Double): List<ScoredPlace> {
        val general = fetchPhoton(query, nearLat, nearLon, limit = 12, settlementsOnly = false)
        val settlements = fetchPhoton(query, nearLat, nearLon, limit = 10, settlementsOnly = true)
        return general + settlements
    }

    private fun searchPhotonBiased(query: String, nearLat: Double, nearLon: Double): List<MapPlace> =
        rankScored(photonScored(query, nearLat, nearLon), query, nearLat, nearLon)

    private fun fetchPhoton(
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int,
        settlementsOnly: Boolean,
    ): List<ScoredPlace> {
        val enc = OvertakeHttp.encode(query)
        val bias = if (settlementsOnly) 0.12 else 0.2
        var url =
            "https://photon.komoot.io/api/?q=$enc&lat=$nearLat&lon=$nearLon&limit=$limit" +
                "&location_bias_scale=$bias&lang=default"
        if (settlementsOnly) {
            url += "&osm_tag=place:city&osm_tag=place:town&osm_tag=place:municipality"
        }
        OvertakeHttp.throttle("photon.komoot.io", 300)
        run {
            val root = JSONObject(OvertakeHttp.getText(url))
            val features = root.optJSONArray("features") ?: JSONArray()
            return buildList {
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val geom = f.getJSONObject("geometry")
                    val coords = geom.getJSONArray("coordinates")
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val p = f.getJSONObject("properties")
                    val name = p.optString("name").ifBlank {
                        p.optString("street").ifBlank { "Place" }
                    }
                    val type = p.optString("osm_value").ifBlank { p.optString("type") }
                    val prom = extentImportance(p) + p.optDouble("importance", 0.0)
                    add(
                        ScoredPlace(
                            place = MapPlace(
                                name = name,
                                lat = lat,
                                lon = lon,
                                category = type,
                                subtitle = buildPhotonSubtitle(p),
                                prominence = prom,
                            ),
                            type = type,
                            importance = prom,
                            nameMatch = name,
                        ),
                    )
                }
            }
        }
    }

    private fun searchNominatim(
        query: String,
        nearLat: Double?,
        nearLon: Double?,
    ): List<ScoredPlace> {
        val enc = OvertakeHttp.encode(query)
        var url =
            "https://nominatim.openstreetmap.org/search?q=$enc&format=jsonv2&addressdetails=1&limit=20"
        if (nearLat != null && nearLon != null) {
            // Soft regional preference (~180 km); bounded=0 still allows farther matches.
            val d = 1.6
            url += "&viewbox=${nearLon - d},${nearLat + d},${nearLon + d},${nearLat - d}&bounded=0"
        }
        // Nominatim usage policy: at most 1 request/second from a single source.
        OvertakeHttp.throttle("nominatim.openstreetmap.org", 1_100)
        run {
            val arr = JSONArray(OvertakeHttp.getText(url))
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val display = o.optString("display_name")
                    val name = o.optString("name").ifBlank {
                        display.substringBefore(',').ifBlank { "Place" }
                    }
                    val type = o.optString("type").ifBlank { o.optString("addresstype") }
                    val prom = o.optDouble("importance", 0.0)
                    add(
                        ScoredPlace(
                            place = MapPlace(
                                name = name,
                                lat = o.getDouble("lat"),
                                lon = o.getDouble("lon"),
                                category = type,
                                subtitle = display,
                                prominence = prom,
                            ),
                            type = type,
                            importance = prom,
                            nameMatch = name,
                        ),
                    )
                }
            }
        }
    }

    private fun rankScored(
        items: List<ScoredPlace>,
        query: String,
        nearLat: Double?,
        nearLon: Double?,
    ): List<MapPlace> {
        val scored = items
            .map { s ->
                val distKm = if (nearLat != null && nearLon != null) {
                    haversineKm(nearLat, nearLon, s.place.lat, s.place.lon)
                } else {
                    -1.0
                }
                s.place to relevance(
                    query = query,
                    name = s.place.name,
                    subtitle = s.place.subtitle,
                    category = s.type,
                    prominence = s.importance,
                    distanceKm = distKm,
                )
            }
            .sortedByDescending { it.second }
            .map { it.first }
        // Dedupe AFTER sorting by score so each group keeps its best-ranked (nearest / strongest) copy.
        return dedupe(scored).take(12)
    }

    /**
     * Unified relevance blend — the single source of truth used both here (Photon/Nominatim) and by the
     * cockpit search overlay (which merges in the platform Geocoder). A Google-class order comes from
     * three signals, name-match DOMINANT so "the right place" leads, prominence + distance ordering it:
     *
     *  - NAME-MATCH class: exact ≫ prefix ≫ "query = name + house number" ≫ contains ≫ full token
     *    coverage / address-line hit ≫ partial coverage ≫ whole-name typo (edit distance). Accent- and
     *    abbreviation-insensitive ([normalize]: "Cra." / "AV" fold + expand like the query).
     *  - PROMINENCE (0..~185): OSM class-type weight + the source's own `importance` — a city / named
     *    POI / main road beats an obscure node.
     *  - DISTANCE (0..140): nearer breaks ties; a far match still surfaces, it just ranks below a near one.
     *
     * @param distanceKm haversine km to the bias point, or a negative value when there is no bias (then
     *   distance is neutral, so the unbiased path ranks purely on name-match + prominence).
     */
    fun relevance(
        query: String,
        name: String,
        subtitle: String,
        category: String,
        prominence: Double,
        distanceKm: Double,
    ): Double {
        val prom = prominenceScore(category, prominence)
        val dist = distanceFactor(distanceKm)
        if (normalize(query).isEmpty()) return prom + dist
        return matchScore(query, name, subtitle) + prom + dist
    }

    /**
     * Pure text-match strength (0..1000), no prominence/distance — the DOMINANT term of [relevance],
     * also used by the cockpit overlay to decide whether a saved place (recent/favourite/home) is a
     * real hit for the query before boosting it into the results. Accent- and abbreviation-folded.
     */
    fun matchScore(query: String, name: String, subtitle: String): Double {
        val q = normalize(query)
        if (q.isEmpty()) return 0.0
        val fn = normalize(name)
        val fsub = normalize(subtitle)
        val qToks = q.split(' ').filter { it.isNotEmpty() }
        val nameToks = fn.split(' ').filter { it.isNotEmpty() }
        val hayToks = nameToks + fsub.split(' ').filter { it.isNotEmpty() }
        return when {
            fn == q -> 1000.0
            fn.startsWith(q) -> 840.0
            // Query is the place name PLUS more (a house number, a suffix): "rincon de la flora 1"
            // starts with the barrio name "rincon de la flora". Strong — a specific local address.
            q.length >= 4 && fn.length >= 4 && q.startsWith("$fn ") -> 810.0
            q.length >= 4 && fn.length >= 4 && q.startsWith(fn) -> 780.0
            fn.contains(q) -> 660.0
            else -> {
                val subFull = q.length >= 3 && fsub.contains(q)
                // NAME coverage dominates ADDRESS-LINE coverage: a place whose NAME holds every query
                // token beats one where a token only appears in the address region ("Valle del Cauca"),
                // so a clinic in the Valle department does not win the query "universidad del valle".
                val nameCover = tokenCoverage(qToks, nameToks)
                val allCover = tokenCoverage(qToks, hayToks)
                when {
                    subFull && nameCover >= 0.5 -> 620.0
                    nameCover >= 0.999 -> 560.0                    // every query token is in the NAME
                    subFull -> 540.0                               // whole query appears in the address line
                    allCover >= 0.999 -> 420.0 + nameCover * 120.0 // all tokens present; name matches rank higher
                    nameCover > 0.0 || allCover > 0.0 -> 180.0 + nameCover * 260.0 + allCover * 60.0
                    fuzzyClose(q, fn) -> 340.0                     // whole-name typo, small edit distance
                    else -> 60.0                                   // engine linked it, no textual hook
                }
            }
        }
    }

    /** Previously-ranked biased result for [query], if still cached — lets the overlay paint instantly. */
    fun cachedBiased(query: String, nearLat: Double, nearLon: Double): List<MapPlace>? {
        val q = expandQuery(query.trim())
        if (q.isEmpty()) return null
        return cacheGet(cacheKey(q, nearLat, nearLon, true))
    }

    /** OSM class-type weight (0..100) plus the source `importance`, capped — the prominence term. */
    private fun prominenceScore(category: String, prominence: Double): Double =
        typeWeight(category) * 0.9 + min(95.0, prominence * 190.0)

    /** Nearer ranks higher; a far match still surfaces. Negative km (no bias) is neutral. */
    private fun distanceFactor(distKm: Double): Double = when {
        distKm < 0 -> 40.0
        distKm <= 2 -> 140.0
        distKm <= 5 -> 122.0
        distKm <= 10 -> 104.0
        distKm <= 25 -> 82.0
        distKm <= 60 -> 56.0
        distKm <= 150 -> 32.0
        distKm <= 400 -> 14.0
        distKm <= 1200 -> 5.0
        else -> 0.0
    }

    /** Fraction (0..1) of query tokens found in the haystack as an exact / prefix / near-fuzzy token. */
    private fun tokenCoverage(qToks: List<String>, hayToks: List<String>): Double {
        if (qToks.isEmpty()) return 0.0
        var hit = 0
        for (qt in qToks) {
            val ok = hayToks.any { ht ->
                ht == qt ||
                    (qt.length >= 2 && ht.startsWith(qt)) ||
                    (qt.length >= 4 && ht.length >= 4 && abs(ht.length - qt.length) <= 2 && levenshtein(qt, ht) <= 1)
            }
            if (ok) hit++
        }
        return hit.toDouble() / qToks.size
    }

    /** Whole-query typo tolerance against the head of the name (accent-folded, length-scaled). */
    private fun fuzzyClose(q: String, fn: String): Boolean {
        if (q.length < 3 || q.length > 18 || fn.isEmpty()) return false
        val window = if (fn.length > q.length + 2) fn.substring(0, q.length + 2) else fn
        val tol = when {
            q.length <= 5 -> 1
            q.length <= 9 -> 2
            else -> 3
        }
        return levenshtein(q, window) <= tol
    }

    /** Iterative Levenshtein, two rolling rows (cheap for the short strings we compare). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..a.length) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /**
     * Collapse duplicate rows across the (up to three) sources into one, keeping the FIRST occurrence —
     * so when the caller pre-sorts by [relevance], each group keeps its best-ranked copy.
     *  - Streets/roads: keyed by name only, so the 7 "Carrera 100" segments Nominatim/Photon return
     *    collapse to a single row (Google shows one street), while "Carrera 100A" stays separate.
     *  - Everything else: keyed by name + ~100 m coordinate bucket, so the SAME store from Photon and
     *    Nominatim merges but distinct branches of a chain ("Éxito" in different comunas) are all kept.
     * A kept row with a blank subtitle inherits one from a dropped duplicate.
     */
    fun dedupe(places: List<MapPlace>): List<MapPlace> {
        val seen = LinkedHashMap<String, MapPlace>()
        for (p in places) {
            val key = dedupeKey(p)
            val cur = seen[key]
            when {
                cur == null -> seen[key] = p
                cur.subtitle.isBlank() && p.subtitle.isNotBlank() -> seen[key] = cur.copy(subtitle = p.subtitle)
                else -> Unit // keep the first (higher-ranked) copy
            }
        }
        return seen.values.toList()
    }

    /** Grouping key for [dedupe] — reused by the cockpit overlay so it can dedupe while keeping row metadata. */
    fun dedupeKey(p: MapPlace): String {
        val fn = fold(p.name)
        return if (isStreetType(p.category)) {
            "s|$fn"
        } else {
            "p|$fn|${"%.3f".format(p.lat)}|${"%.3f".format(p.lon)}"
        }
    }

    /** Accent- and case-insensitive fold; punctuation → space, spaces collapsed ("Rincón, Cra." → "rincon cra"). */
    fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[.,;:_/\\\\#\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Fold + expand street abbreviations, for match heuristics (both query and candidate name/subtitle). */
    private fun normalize(s: String): String {
        val f = fold(s)
        if (f.isEmpty()) return f
        return f.split(' ').joinToString(" ") { STREET_ABBREV[it] ?: it }
    }

    /**
     * Expand street abbreviations in the OUTGOING query so Photon (which does not expand them) resolves
     * "cra 100" → "carrera 100", "av 6 nte" → "avenida 6 nte". Token-wise, punctuation preserved for the
     * geocoders (a token folds to its abbrev key only when it IS the whole abbrev, so "#5-20" is untouched).
     */
    fun expandQuery(query: String): String {
        if (query.isBlank()) return query
        return query.split(Regex("\\s+")).joinToString(" ") { tok ->
            STREET_ABBREV[fold(tok)] ?: tok
        }
    }

    private val STREET_ABBREV: Map<String, String> = mapOf(
        "cra" to "carrera", "cr" to "carrera", "kra" to "carrera", "kr" to "carrera",
        "carr" to "carrera", "crra" to "carrera",
        "cl" to "calle", "cll" to "calle", "clle" to "calle", "cle" to "calle",
        "av" to "avenida", "ave" to "avenida", "avd" to "avenida", "avda" to "avenida",
        "dg" to "diagonal", "diag" to "diagonal",
        "tv" to "transversal", "tsv" to "transversal", "transv" to "transversal",
        "ac" to "avenida calle", "ak" to "avenida carrera",
        "ctra" to "carretera", "km" to "kilometro",
        "blv" to "boulevard", "blvd" to "boulevard",
    )

    /** Road-ish OSM class-types — searched streets collapse to one row per name (see [dedupe]). */
    private fun isStreetType(category: String): Boolean = when (category.lowercase()) {
        "highway", "road", "residential", "primary", "secondary", "tertiary", "trunk", "motorway",
        "unclassified", "living_street", "service", "track", "pedestrian", "footway", "path",
        "cycleway", "street", "motorway_link", "trunk_link", "primary_link", "secondary_link",
        "tertiary_link" -> true
        else -> false
    }

    /** Place rank: city/municipality ≫ village/hamlet ≫ random node; named POIs/main roads mid-high. */
    private fun typeWeight(type: String): Double = when (type.lowercase()) {
        "city", "municipality" -> 100.0
        "town" -> 88.0
        "administrative", "county", "state", "region", "province" -> 70.0
        "suburb", "borough", "quarter", "neighbourhood", "neighborhood", "city_district", "district" -> 45.0
        "village" -> 28.0
        "hamlet", "isolated_dwelling", "farm", "allotments" -> 8.0
        "locality" -> 22.0
        "primary", "trunk", "motorway" -> 42.0
        "secondary" -> 38.0
        "tertiary", "highway", "road", "residential", "unclassified", "living_street" -> 34.0
        "service", "track", "pedestrian", "footway", "path", "cycleway" -> 20.0
        "hospital", "clinic", "pharmacy", "doctors" -> 40.0
        "university", "college", "school", "kindergarten" -> 40.0
        "supermarket", "department_store", "mall", "marketplace", "wholesale" -> 40.0
        "hotel", "hostel", "motel", "guest_house" -> 32.0
        "attraction", "viewpoint", "museum", "theme_park", "zoo", "gallery" -> 34.0
        "park", "garden", "nature_reserve", "stadium", "sports_centre" -> 30.0
        "restaurant", "cafe", "fast_food", "bar", "pub", "food_court" -> 28.0
        "fuel", "charging_station" -> 28.0
        "bank", "atm", "bureau_de_change" -> 26.0
        "station", "bus_stop", "tram_stop", "subway_entrance", "halt", "platform" -> 14.0
        "address", "house", "building", "yes" -> 30.0
        else -> 20.0
    }

    /** Larger Photon extent ≈ bigger settlement — useful worldwide importance proxy. */
    private fun extentImportance(p: JSONObject): Double {
        val ext = p.optJSONArray("extent") ?: return 0.0
        if (ext.length() < 4) return 0.0
        val w = abs(ext.getDouble(2) - ext.getDouble(0))
        val h = abs(ext.getDouble(1) - ext.getDouble(3))
        return min(0.9, w * h * 80.0)
    }

    private fun buildPhotonSubtitle(p: JSONObject): String {
        val parts = listOfNotNull(
            p.optString("city").takeIf { it.isNotBlank() },
            p.optString("county").takeIf { it.isNotBlank() },
            p.optString("state").takeIf { it.isNotBlank() },
            p.optString("country").takeIf { it.isNotBlank() },
        ).distinct()
        val type = p.optString("osm_value").takeIf { it.isNotBlank() }
        return buildString {
            if (type != null) append(type)
            if (parts.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(parts.joinToString(", "))
            }
        }.ifBlank { p.optString("country") }
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private data class ScoredPlace(
        val place: MapPlace,
        val type: String,
        val importance: Double,
        val nameMatch: String,
    )
}
