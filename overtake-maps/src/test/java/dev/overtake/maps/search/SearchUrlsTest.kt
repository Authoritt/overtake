// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors.
package dev.overtake.maps.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The location bias is the whole fix: a rider in Colombia searching a local barrio was getting
 * Yaiza (ES), Monterrey (MX) and Chicama (PE). These lock the REQUEST — the part that decides which
 * candidates ever reach the (already correct) ranker — without touching the network.
 */
class SearchUrlsTest {

    // Cali, Colombia — where the wrong-country results were measured.
    private val lat = 3.42158
    private val lon = -76.5205

    @Test
    fun `nominatim with a near point sends a viewbox as a preference, never bounded`() {
        val url = SearchUrls.nominatim("villa del sol sector 2", lat, lon, acceptLanguage = "es-CO,es")
        assertEquals(
            "https://nominatim.openstreetmap.org/search" +
                "?q=villa+del+sol+sector+2&format=jsonv2&addressdetails=1&limit=20" +
                "&accept-language=es-CO%2Ces" +
                "&viewbox=-78.12050,5.02158,-74.92050,1.82158" +
                "&bounded=0",
            url,
        )
        // bounded=0, not 1: the box RANKS, it must never exclude a real distant destination.
        assertFalse("viewbox must stay a preference", url.contains("bounded=1"))
    }

    @Test
    fun `nominatim takes a countrycodes restriction from the rider country`() {
        val url = SearchUrls.nominatim(
            "villa del sol sector 2", lat, lon,
            acceptLanguage = "es-CO,es",
            countryCodes = "CO",
        )
        assertTrue(url.contains("&countrycodes=co"))
        // It must sit alongside the box, not replace it.
        assertTrue(url.contains("&viewbox="))
        assertTrue(url.contains("&bounded=0"))
    }

    @Test
    fun `a junk countrycodes value is dropped instead of poisoning the request`() {
        // Anything that is not a 2-letter ISO code list is ignored: better a worldwide search than
        // an empty one. (A locale tag, a country name, an empty string, a stray null.)
        for (bad in listOf("es-CO", "Colombia", "", "  ", "c", "col,co")) {
            val url = SearchUrls.nominatim("x", lat, lon, acceptLanguage = "es", countryCodes = bad)
            assertFalse("countrycodes=$bad must be dropped", url.contains("countrycodes"))
        }
        assertFalse(
            SearchUrls.nominatim("x", lat, lon, acceptLanguage = "es", countryCodes = null)
                .contains("countrycodes"),
        )
        // ...and a valid list is normalised (trimmed, lowercased, de-duplicated).
        assertEquals("co,ec", SearchUrls.normalizeCountryCodes(" CO , ec , co "))
    }

    @Test
    fun `nominatim without a near point stays worldwide`() {
        val url = SearchUrls.nominatim("villa del sol sector 2", null, null, acceptLanguage = "es-CO,es")
        assertEquals(
            "https://nominatim.openstreetmap.org/search" +
                "?q=villa+del+sol+sector+2&format=jsonv2&addressdetails=1&limit=20" +
                "&accept-language=es-CO%2Ces",
            url,
        )
        assertFalse(url.contains("viewbox"))
        assertFalse(url.contains("bounded"))
    }

    @Test
    fun `photon general query carries the rider bbox`() {
        val url = SearchUrls.photon("villa del sol sector 2", lat, lon, limit = 12, settlementsOnly = false)
        assertEquals(
            "https://photon.komoot.io/api/?q=villa+del+sol+sector+2" +
                "&lat=3.42158&lon=-76.52050&limit=12&location_bias_scale=0.2&lang=default" +
                "&bbox=-80.52050,-0.57842,-72.52050,7.42158",
            url,
        )
    }

    @Test
    fun `photon bbox is minLon minLat maxLon maxLat and excludes the measured noise`() {
        val url = SearchUrls.photon("villa del sol sector 2", lat, lon, limit = 12, settlementsOnly = false)
        val box = url.substringAfter("&bbox=").substringBefore('&').split(",").map { it.toDouble() }
        assertEquals(4, box.size)
        val (minLon, minLat, maxLon, maxLat) = box
        assertTrue("minLon < maxLon", minLon < maxLon)
        assertTrue("minLat < maxLat", minLat < maxLat)
        assertTrue("rider inside the box", lat in minLat..maxLat && lon in minLon..maxLon)
        // The three places the rider actually got back must all fall OUTSIDE the box.
        val noise = listOf(
            "Yaiza, Canarias, ES" to (28.95 to -13.76),
            "Monterrey, MX" to (25.68 to -100.31),
            "Chicama, PE" to (-7.84 to -79.15),
        )
        for ((name, p) in noise) {
            val inside = p.first in minLat..maxLat && p.second in minLon..maxLon
            assertFalse("$name must be outside the rider bbox", inside)
        }
    }

    @Test
    fun `photon settlements query stays unboxed so a far away city is still reachable`() {
        val url = SearchUrls.photon("bogota", lat, lon, limit = 10, settlementsOnly = true)
        assertFalse("a hard bbox here would hide distant cities", url.contains("bbox="))
        assertTrue(url.contains("&location_bias_scale=0.12"))
        assertTrue(url.contains("&osm_tag=place:city&osm_tag=place:town&osm_tag=place:municipality"))
        assertTrue(url.contains("&lat=3.42158&lon=-76.52050"))
    }

    @Test
    fun `photon without a near point sends no bias and no box`() {
        val url = SearchUrls.photon("villa del sol", null, null, limit = 12, settlementsOnly = false)
        assertEquals(
            "https://photon.komoot.io/api/?q=villa+del+sol&limit=12&lang=default",
            url,
        )
    }

    @Test
    fun `degrees are formatted locale independently`() {
        // On an es-* device the default locale writes 3,42 — a comma inside a comma-separated
        // viewbox/bbox is a silently malformed request. This is the guard for exactly that.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-CO"))
            val url = SearchUrls.nominatim("x", lat, lon, acceptLanguage = "es")
            assertTrue(url.contains("&viewbox=-78.12050,5.02158,-74.92050,1.82158&bounded=0"))
            assertEquals(4, url.substringAfter("&viewbox=").substringBefore("&").split(",").size)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `boxes are clamped at the poles and the antimeridian`() {
        val north = SearchUrls.boxAround(89.0, 179.0, 4.0)
        assertEquals(90.0, north.maxLat, 1e-9)
        assertEquals(180.0, north.maxLon, 1e-9)
        val south = SearchUrls.boxAround(-89.0, -179.0, 4.0)
        assertEquals(-90.0, south.minLat, 1e-9)
        assertEquals(-180.0, south.minLon, 1e-9)
    }

    @Test
    fun `accept language comes from the device locale, never hardcoded`() {
        assertEquals("es-CO,es", SearchUrls.deviceAcceptLanguage(Locale.forLanguageTag("es-CO")))
        assertEquals("ro-RO,ro", SearchUrls.deviceAcceptLanguage(Locale.forLanguageTag("ro-RO")))
        assertEquals("en", SearchUrls.deviceAcceptLanguage(Locale.forLanguageTag("en")))
        assertEquals("", SearchUrls.deviceAcceptLanguage(Locale.forLanguageTag("")))
    }

    @Test
    fun `a blank accept language leaves the param out entirely`() {
        val url = SearchUrls.nominatim("x", null, null, acceptLanguage = "")
        assertFalse(url.contains("accept-language"))
    }
}
