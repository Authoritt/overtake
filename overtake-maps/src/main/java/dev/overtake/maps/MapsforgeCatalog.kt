// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

/**
 * The public catalog vocabulary for the mapsforge.org offline `.map` browser + downloader, surfaced
 * through [OfflineManager]. A host renders a "download an offline map" UI purely from these types:
 * drill through continents/countries ([MapsforgeDir]) and download regional vector maps
 * ([MapsforgeMap]). Kept intentionally free of any Android type so the contract is host-neutral.
 *
 * Source: the Apache autoindex under `https://download.mapsforge.org/maps/v5/` — continents at the
 * root, countries under each continent, sub-regions under big countries. [MapsforgeCatalogClient]
 * fetches + parses it; the host never touches HTTP or HTML.
 */

/** A browsable sub-directory in the catalog (a continent, a country with sub-regions, …). */
data class MapsforgeDir(
    /** Display label, e.g. `south-america` or `germany` (the trailing `/` stripped). */
    val name: String,
    /**
     * Path RELATIVE to the catalog root, always ending in `/`, e.g. `south-america/` or
     * `europe/germany/`. Feed straight back into [OfflineManager.browseMapsforge] to drill in.
     */
    val path: String,
)

/** A downloadable `.map` vector file in the catalog. */
data class MapsforgeMap(
    /** File name including extension, e.g. `colombia.map`. */
    val fileName: String,
    /** Absolute download URL, e.g. `https://download.mapsforge.org/maps/v5/south-america/colombia.map`. */
    val url: String,
    /**
     * Size in bytes parsed from the index size column, or `-1` when unknown/unparseable. The exact
     * length is re-read from the HTTP `Content-Length` at download time; this is for display/warnings.
     */
    val sizeBytes: Long,
) {
    /** File name without the `.map` extension, e.g. `colombia` — a sensible display/title. */
    val name: String get() = fileName.removeSuffix(".map").removeSuffix(".MAP")
}

/**
 * One browsed catalog page: the [path] that produced it (relative to root; `""` is the root), its
 * [parent] path (or null at the root), the sub-directories to drill into and the `.map` files here.
 */
data class MapsforgeCatalogPage(
    val path: String,
    val parent: String?,
    val dirs: List<MapsforgeDir>,
    val maps: List<MapsforgeMap>,
)

/**
 * Handle to an in-flight [OfflineManager.downloadMapsforgeMap] so the host can [cancel] it (a big
 * regional map is hundreds of MB). Cancelling deletes the partial `.part` file and reports done with
 * `ok = false`.
 */
fun interface MapsforgeDownload {
    fun cancel()
}
