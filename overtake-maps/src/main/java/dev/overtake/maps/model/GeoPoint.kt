// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.model

/**
 * A WGS84 coordinate, optionally with elevation. The library's neutral geo primitive — every
 * public surface (routes, places, renderer input) speaks [GeoPoint] rather than any osmdroid /
 * MapLibre point type, so the fork consumes Overtake without leaking a specific map SDK into its
 * own model. Mirrors the fork's `GpxPoint(lat, lon, ele)` reduced to the fields the API needs.
 *
 * @param lat latitude in decimal degrees.
 * @param lon longitude in decimal degrees.
 * @param ele elevation in metres, or null when unknown.
 */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
)
