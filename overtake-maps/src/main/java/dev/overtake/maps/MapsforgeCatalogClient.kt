// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import dev.overtake.maps.net.OvertakeHttp
import java.util.Locale

/**
 * Fetches + parses the mapsforge.org Apache autoindex into the neutral [MapsforgeCatalogPage] the
 * host browses, and resolves a phone-detected country to the matching regional `.map` (the "suggested
 * for you" shortcut). HTTP rides [OvertakeHttp] so it works on the host's pinned uplink (e.g. cellular
 * while bound to the bike's internet-less Wi‑Fi). Blocking + synchronous: the host calls it off the
 * main thread (as it already does for the other [OfflineManager] engines).
 *
 * The index is a classic Apache `mod_autoindex` table — one `<tr>` per entry with an `<a href>` (a
 * `name/` sub-dir or a `name.map` file) and a right-aligned size column (`314M`, `2.0G`, or `-` for
 * dirs). Parsing is row-by-row and tolerant of markup drift: unknown rows are simply skipped.
 */
internal object MapsforgeCatalogClient {

    /** Catalog root — the V5 maps that match the `org.mapsforge:*:0.21.0` bridge we render with. */
    const val ROOT = "https://download.mapsforge.org/maps/v5/"

    /** The continents at the catalog root (used to scan when a country's continent isn't hinted). */
    private val CONTINENTS = listOf(
        "europe/", "asia/", "north-america/", "south-america/",
        "central-america/", "africa/", "australia-oceania/", "russia/",
    )

    // --- Browse ----------------------------------------------------------------------------------

    /**
     * Browse the catalog at [path] (relative to [ROOT]; `""` = root). Fetches the index HTML and
     * parses it into sub-directories + `.map` files. Throws [OvertakeHttp.HttpException] / IOException
     * on a transport failure so the host can show a "couldn't load" state.
     */
    fun browse(path: String): MapsforgeCatalogPage {
        val clean = normalizeDirPath(path)
        val html = OvertakeHttp.getText(
            url = ROOT + clean,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
            accept = "text/html",
        )
        val dirs = ArrayList<MapsforgeDir>()
        val maps = ArrayList<MapsforgeMap>()
        parseRows(html, clean, dirs, maps)
        return MapsforgeCatalogPage(
            path = clean,
            parent = parentOf(clean),
            dirs = dirs.sortedBy { it.name.lowercase(Locale.US) },
            maps = maps.sortedBy { it.name.lowercase(Locale.US) },
        )
    }

    /**
     * Best-effort: the regional `.map`(s) that match a phone-detected [countryIso] (ISO-3166 alpha-2),
     * optionally refined by a known [city]/admin-area for big countries split into sub-regions. Never
     * throws — any network/parse failure yields an empty list, so the "suggested for you" section just
     * doesn't appear. Runs a small number of index fetches (one when the continent is hinted).
     */
    fun suggest(countryIso: String, city: String?): List<MapsforgeMap> {
        return try {
            val iso = countryIso.trim().uppercase(Locale.US)
            if (iso.length != 2) return emptyList()
            val slug = SLUG_OVERRIDE[iso] ?: slugify(Locale("", iso).getDisplayCountry(Locale.ENGLISH))
            if (slug.isBlank()) return emptyList()

            // Which continent index to read: the hint first (1 fetch), else scan them all.
            val continents = CONTINENT_BY_ISO[iso]?.let { listOf("$it/") } ?: CONTINENTS
            val target = "$slug.map"

            for (cont in continents) {
                val page = runCatching { browse(cont) }.getOrNull() ?: continue
                val country = page.maps.firstOrNull { it.fileName.equals(target, ignoreCase = true) }
                val subDir = page.dirs.firstOrNull { it.name.equals(slug, ignoreCase = true) }

                // Optional polish: for a big country split into sub-regions, prefer the sub-region
                // matching the detected city/admin-area — but always keep the country-level map as a win.
                if (subDir != null && !city.isNullOrBlank()) {
                    val citySlug = slugify(city)
                    val sub = runCatching { browse(subDir.path) }.getOrNull()
                    val match = sub?.maps?.firstOrNull {
                        val n = it.name.lowercase(Locale.US)
                        citySlug.isNotBlank() && (n.contains(citySlug) || citySlug.contains(n))
                    }
                    if (match != null) return listOfNotNull(match, country)
                }
                if (country != null) return listOf(country)
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- HTML parsing ----------------------------------------------------------------------------

    private val HREF = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val RIGHT_TD = Regex("""<td[^>]*align="right"[^>]*>([^<]*)</td>""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([KMGT])?B?""", RegexOption.IGNORE_CASE)

    /** Split the index into rows and pull the (href, size) out of each; skip anything that isn't an entry. */
    private fun parseRows(
        html: String,
        basePath: String,
        dirs: MutableList<MapsforgeDir>,
        maps: MutableList<MapsforgeMap>,
    ) {
        // Row boundary is "<tr"; robust enough for mod_autoindex (and a <pre> variant still yields one
        // href per logical line if we ever hit it — handled by the per-fragment href scan below).
        val fragments = if (html.contains("<tr", ignoreCase = true)) {
            html.split(Regex("<tr", RegexOption.IGNORE_CASE))
        } else {
            html.split('\n')
        }
        for (frag in fragments) {
            val href = HREF.find(frag)?.groupValues?.get(1) ?: continue
            // Skip sort links (?C=…), the parent link, absolute paths and self.
            if (href.startsWith("?") || href.startsWith("/") || href.startsWith("..") || href == "./") continue
            when {
                href.endsWith("/") -> {
                    val name = href.trimEnd('/')
                    if (name.isNotEmpty()) dirs.add(MapsforgeDir(name = name, path = basePath + href))
                }
                href.endsWith(".map", ignoreCase = true) -> {
                    val sizeToken = RIGHT_TD.findAll(frag).lastOrNull()?.groupValues?.get(1)?.trim().orEmpty()
                    maps.add(
                        MapsforgeMap(
                            fileName = href,
                            url = ROOT + basePath + href,
                            sizeBytes = parseSize(sizeToken),
                        ),
                    )
                }
            }
        }
    }

    /** Apache size token → bytes (1024-based, matching mod_autoindex); `-1` when unknown. */
    private fun parseSize(token: String): Long {
        val t = token.trim()
        if (t.isEmpty() || t == "-") return -1L
        val m = SIZE.find(t) ?: return -1L
        val num = m.groupValues[1].toDoubleOrNull() ?: return -1L
        val mult = when (m.groupValues[2].uppercase(Locale.US)) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "T" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    // --- Paths -----------------------------------------------------------------------------------

    /** Trim a browse path to `""` or `a/b/` (no leading slash, single trailing slash). */
    private fun normalizeDirPath(path: String): String {
        var p = path.trim().trim('/')
        if (p.isEmpty()) return ""
        p = p.replace("//", "/")
        return "$p/"
    }

    /** Parent browse path of [path], or null at the root. */
    private fun parentOf(path: String): String? {
        val p = path.trim('/')
        if (p.isEmpty()) return null
        val idx = p.lastIndexOf('/')
        return if (idx < 0) "" else p.substring(0, idx) + "/"
    }

    // --- Country → slug / continent resolution ---------------------------------------------------

    /** Lowercase, strip accents, collapse every non-alphanumeric run to a single hyphen. */
    private fun slugify(raw: String): String {
        val decomposed = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
        val ascii = decomposed.replace(Regex("\\p{M}+"), "")
        return ascii.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    /**
     * ISO alpha-2 → mapsforge continent directory. A hint so the common case is ONE index fetch; a
     * miss falls back to scanning every continent (still correct, just slower). Not exhaustive — the
     * scan is the correctness net for the long tail.
     */
    private val CONTINENT_BY_ISO: Map<String, String> = buildMap {
        val sa = listOf("AR", "BO", "BR", "CL", "CO", "EC", "GY", "PY", "PE", "SR", "UY", "VE")
        val ca = listOf("BZ", "CR", "SV", "GT", "HN", "NI", "PA", "BS", "CU", "HT", "DO", "JM")
        val na = listOf("US", "CA", "MX", "GL")
        val eu = listOf(
            "AL", "AD", "AT", "BY", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "GE",
            "DE", "GR", "HU", "IS", "IE", "IT", "XK", "LV", "LI", "LT", "LU", "MK", "MT", "MD", "MC",
            "ME", "NL", "NO", "PL", "PT", "RO", "RS", "SK", "SI", "ES", "SE", "CH", "TR", "UA", "GB",
        )
        val asia = listOf(
            "AF", "AM", "AZ", "BD", "BT", "KH", "CN", "TL", "IN", "ID", "IR", "IQ", "IL", "PS", "JP",
            "JO", "KZ", "KG", "LA", "LB", "MY", "SG", "BN", "MV", "MN", "MM", "NP", "KP", "KR", "PK",
            "PH", "LK", "SY", "TW", "TJ", "TH", "TM", "UZ", "VN", "YE", "AE", "SA", "KW", "QA", "BH",
            "OM",
        )
        val africa = listOf(
            "DZ", "AO", "BJ", "BW", "BF", "BI", "CM", "CV", "CF", "TD", "KM", "CG", "CD", "DJ", "EG",
            "GQ", "ER", "ET", "GA", "GH", "GN", "GW", "CI", "KE", "LS", "LR", "LY", "MG", "MW", "ML",
            "MR", "MU", "MA", "MZ", "NA", "NE", "NG", "RW", "SN", "GM", "SC", "SL", "SO", "ZA", "SS",
            "SD", "SZ", "TZ", "TG", "TN", "UG", "ZM", "ZW",
        )
        val oceania = listOf("AU", "NZ", "FJ", "PG", "SB", "VU", "NC", "WS", "TO", "KI", "FM", "PW")
        sa.forEach { put(it, "south-america") }
        ca.forEach { put(it, "central-america") }
        na.forEach { put(it, "north-america") }
        eu.forEach { put(it, "europe") }
        asia.forEach { put(it, "asia") }
        africa.forEach { put(it, "africa") }
        oceania.forEach { put(it, "australia-oceania") }
        put("RU", "russia")
    }

    /** ISO alpha-2 → mapsforge slug where the file name differs from the slugified English name. */
    private val SLUG_OVERRIDE: Map<String, String> = mapOf(
        "GB" to "great-britain",
        "CZ" to "czech-republic",
        "MK" to "macedonia",
        "IE" to "ireland-and-northern-ireland",
        "DO" to "haiti-and-domrep",
        "HT" to "haiti-and-domrep",
        "SG" to "malaysia-singapore-brunei",
        "MY" to "malaysia-singapore-brunei",
        "BN" to "malaysia-singapore-brunei",
        "IL" to "israel-and-palestine",
        "PS" to "israel-and-palestine",
        "ZA" to "south-africa-and-lesotho",
        "SN" to "senegal-and-gambia",
        "GM" to "senegal-and-gambia",
        "CI" to "ivory-coast",
        "AE" to "gcc-states",
        "SA" to "gcc-states",
        "KW" to "gcc-states",
        "QA" to "gcc-states",
        "BH" to "gcc-states",
        "OM" to "gcc-states",
    )
}
