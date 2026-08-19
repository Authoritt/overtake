// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.search

import dev.overtake.maps.net.OvertakeHttp
import java.util.Locale

/**
 * Pure REQUEST-URL construction for the two worldwide geocoders [NominatimSearch] queries. Split out
 * of the fetch code so the LOCATION BIAS — the part that decides whether the rider gets their own
 * barrio or a same-named place three countries away — is unit-testable without a network
 * (`SearchUrlsTest`).
 *
 * WHY it exists (measured: phone logcat + both endpoints queried by hand). The rider searched
 * "villa del sol sector #2" from Colombia and the list came back Yaiza (Canarias, ES), Monterrey
 * (MX), Chicama (PE). Two facts, not guesses:
 *  - Nominatim, which DOES hold the right rows (Villa del Sol in Cali / Pereira / Pasto / Mosquera),
 *    died with `SocketTimeoutException` — so the merged list was Photon-ONLY;
 *  - Photon's `lat`/`lon` soft bias alone did not keep those three continents out of the top 5.
 * The ranker was never the problem (it scores the right rows at 780+); the good candidates simply
 * never arrived. Hence a real box for BOTH providers here, and in [NominatimSearch] a budget that
 * lets Nominatim actually answer.
 *
 * NOTHING here is region-specific: every box is derived from the rider's own fix, so the library
 * stays app-agnostic and behaves the same in Cali, Constanța or Cape Town.
 */
internal object SearchUrls {

    /**
     * Half-side of the Nominatim `viewbox`, in degrees (~178 km N–S). Deliberately a PREFERENCE, not
     * a restriction (`bounded=0`), so a legitimately distant destination still resolves — it just
     * ranks below a near one, which is exactly what our own distance term already does.
     */
    const val NOMINATIM_VIEWBOX_DEG = 1.6

    /**
     * Half-side of the Photon `bbox`, in degrees (~440 km N–S). MUCH more generous than the Nominatim
     * box on purpose: Photon's `bbox` FILTERS (it drops everything outside) while Nominatim's
     * `viewbox` only biases. A box this size still buries the measured noise — Yaiza 28.9°N,
     * Monterrey 25.7°N and Chicama 7.8°S are all far beyond 4° from a rider in Colombia — without
     * cutting a plausible day's ride. Long distance stays reachable anyway: the settlements query is
     * sent UNBOXED (see [photon]) and Nominatim is never bounded.
     */
    const val PHOTON_BBOX_DEG = 4.0

    /**
     * Nominatim `/search` URL. Params per the API docs
     * (https://nominatim.org/release-docs/latest/api/Search/):
     *  - `format=jsonv2`, `addressdetails=1`, `limit` — unchanged, the shape the parser expects;
     *  - `accept-language` — RFC-2616 language preference for the returned names, taken from the
     *    DEVICE locale ([deviceAcceptLanguage]), never hardcoded, so a Colombian rider reads
     *    "Villa del Sol, Comuna 5, Cali" rather than an anglicised label;
     *  - `viewbox=<x1>,<y1>,<x2>,<y2>` (x = lon, y = lat; the docs take ANY two opposite corners)
     *    plus `bounded=0` — "the preferred area to find search results"; with `bounded=0` it is a
     *    ranking preference only. `bounded=1` would HARD-restrict and break a real destination two
     *    provinces away, so it is deliberately not sent;
     *  - `countrycodes` — ISO 3166-1 alpha-2, "limit search results to one or more countries". This
     *    one DOES restrict, which is why it is only ever the country the rider is standing in
     *    ([RiderCountry], reverse-geocoded from their own fix — never a hardcoded market) and why the
     *    caller widens to an unrestricted query when it comes back empty. Ignored unless it looks
     *    like a real code list, so a junk value can never poison the request.
     * With no rider fix ([nearLat]/[nearLon] null) no box is sent at all — worldwide, as before.
     */
    fun nominatim(
        query: String,
        nearLat: Double?,
        nearLon: Double?,
        limit: Int = 20,
        acceptLanguage: String = deviceAcceptLanguage(),
        countryCodes: String? = null,
    ): String = buildString {
        append("https://nominatim.openstreetmap.org/search?q=").append(OvertakeHttp.encode(query))
        append("&format=jsonv2&addressdetails=1&limit=").append(limit)
        if (acceptLanguage.isNotBlank()) {
            append("&accept-language=").append(OvertakeHttp.encode(acceptLanguage))
        }
        normalizeCountryCodes(countryCodes)?.let { append("&countrycodes=").append(it) }
        if (nearLat != null && nearLon != null) {
            val b = boxAround(nearLat, nearLon, NOMINATIM_VIEWBOX_DEG)
            // Corner order left,top,right,bottom = minLon,maxLat,maxLon,minLat.
            append("&viewbox=")
                .append(deg(b.minLon)).append(",").append(deg(b.maxLat)).append(",")
                .append(deg(b.maxLon)).append(",").append(deg(b.minLat))
            append("&bounded=0")
        }
    }

    /**
     * Photon `/api` URL. Params per the Photon API (https://github.com/komoot/photon#api):
     *  - `lat`/`lon` + `location_bias_scale` — the SOFT bias that was already here and, measured, is
     *    not enough on its own;
     *  - `bbox=minLon,minLat,maxLon,maxLat` — the hard filter that stops a same-named place on
     *    another continent from outranking the rider's own barrio. Sent ONLY on the general query:
     *    the [settlementsOnly] query stays UNBOXED so typing a far-away CITY (the realistic
     *    long-distance destination on a bike) still resolves;
     *  - `lang=default` — kept. The public photon.komoot.io understands only `default|en|de|fr|it`;
     *    `default` returns each place in its LOCAL language, which is what a rider wants and what
     *    `lang=es` would (wrongly, and with an HTTP 400) try to buy.
     * With no rider fix, neither the bias nor the box is sent (worldwide, previous behaviour).
     */
    fun photon(
        query: String,
        nearLat: Double?,
        nearLon: Double?,
        limit: Int,
        settlementsOnly: Boolean,
    ): String = buildString {
        append("https://photon.komoot.io/api/?q=").append(OvertakeHttp.encode(query))
        if (nearLat != null && nearLon != null) {
            append("&lat=").append(deg(nearLat)).append("&lon=").append(deg(nearLon))
        }
        append("&limit=").append(limit)
        if (nearLat != null && nearLon != null) {
            // Settlements are ranked more by prominence than by proximity, so they get a lighter
            // pull toward the rider (both values unchanged).
            append("&location_bias_scale=").append(if (settlementsOnly) "0.12" else "0.2")
        }
        append("&lang=default")
        if (nearLat != null && nearLon != null && !settlementsOnly) {
            val b = boxAround(nearLat, nearLon, PHOTON_BBOX_DEG)
            append("&bbox=")
                .append(deg(b.minLon)).append(",").append(deg(b.minLat)).append(",")
                .append(deg(b.maxLon)).append(",").append(deg(b.maxLat))
        }
        if (settlementsOnly) {
            append("&osm_tag=place:city&osm_tag=place:town&osm_tag=place:municipality")
        }
    }

    /**
     * RFC-2616 language preference derived from the DEVICE locale: `es-CO` becomes `es-CO,es`.
     * Region first (local naming), bare language as the fallback. Blank when the locale carries no
     * language, in which case the caller omits the param entirely.
     */
    fun deviceAcceptLanguage(locale: Locale = Locale.getDefault()): String {
        val lang = locale.language.lowercase(Locale.US)
        if (lang.isBlank()) return ""
        val tag = locale.toLanguageTag()
        return if (tag.isNotBlank() && !tag.equals(lang, ignoreCase = true) && !tag.startsWith("und")) {
            "$tag,$lang"
        } else {
            lang
        }
    }

    /**
     * Accept a `countrycodes` value only when every entry is a plain 2-letter ISO 3166-1 alpha-2
     * code (comma-separated, lowercased). Anything else — a locale tag, a country NAME, an empty
     * string — returns null and the param is left out, so a bad value degrades to a worldwide search
     * instead of an empty one.
     */
    fun normalizeCountryCodes(raw: String?): String? {
        val parts = raw?.split(',')?.map { it.trim().lowercase(Locale.US) }?.filter { it.isNotEmpty() }
        if (parts.isNullOrEmpty()) return null
        if (parts.any { it.length != 2 || !it.all { ch -> ch in 'a'..'z' } }) return null
        return parts.distinct().joinToString(",")
    }

    /** A degree box centred on the rider, clamped to valid lat/lon (poles / antimeridian). */
    fun boxAround(lat: Double, lon: Double, halfSideDeg: Double): GeoBox = GeoBox(
        minLat = (lat - halfSideDeg).coerceIn(-90.0, 90.0),
        minLon = (lon - halfSideDeg).coerceIn(-180.0, 180.0),
        maxLat = (lat + halfSideDeg).coerceIn(-90.0, 90.0),
        maxLon = (lon + halfSideDeg).coerceIn(-180.0, 180.0),
    )

    data class GeoBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    /**
     * Locale-INDEPENDENT degree formatting. `"%.5f".format(v)` uses the DEFAULT locale and emits
     * `3,42` on an es-* device — a comma inside a comma-separated `viewbox`/`bbox`, i.e. a silently
     * malformed request on exactly the phones this bug was reported from. ~1 m resolution.
     */
    private fun deg(v: Double): String = String.format(Locale.US, "%.5f", v)
}
