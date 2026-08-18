// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import java.io.File

/** Which map backend [OvertakeMaps.create] wires up when it returns a [MapProvider.Native]. */
enum class RendererKind { MAPLIBRE, OSMDROID }

/**
 * Everything Overtake needs from the host to build a [MapProvider]. All map-stack tunables live here
 * so the host configures the library once, at startup, and never reaches into its internals.
 *
 * @param userAgent HTTP User-Agent for tile / routing / search requests (courtesy to public servers).
 * @param filesDir host-owned directory for offline graphs and the osmdroid tile cache.
 * @param defaultOrsApiKey optional OpenRouteService key; when set, routing can honour avoid-toggles.
 * @param rendererKind default renderer for a [MapProvider.Native] (MapLibre vector by default).
 * @param styleDayUrl MapLibre GL style for day (default: OpenFreeMap Liberty).
 * @param styleNightUrl MapLibre GL style for night (default: OpenFreeMap Dark).
 * @param osmTileSource osmdroid tile-source name (default: MAPNIK).
 * @param okHttpClientProvider optional supplier of the host's own OkHttpClient (e.g. one already
 *   pinned to the cellular uplink); null lets Overtake build its own.
 */
data class OvertakeMapsConfig(
    val userAgent: String,
    val filesDir: File,
    val defaultOrsApiKey: String = "",
    val rendererKind: RendererKind = RendererKind.MAPLIBRE,
    val styleDayUrl: String = "https://tiles.openfreemap.org/styles/liberty",
    val styleNightUrl: String = "https://tiles.openfreemap.org/styles/dark",
    val osmTileSource: String = "MAPNIK",
    val okHttpClientProvider: (() -> okhttp3.OkHttpClient)? = null,
)
