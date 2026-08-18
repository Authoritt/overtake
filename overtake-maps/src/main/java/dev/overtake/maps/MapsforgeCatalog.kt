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

/** Which phase the single app-scoped Mapsforge download is in (see [OfflineManager.mapsforgeDownloadState]). */
enum class MapsforgeDownloadStatus {
    /** No download has run yet (the initial / reset state). */
    IDLE,

    /** Actively streaming bytes into `<name>.map.part`. */
    RUNNING,

    /** A transient error hit; waiting out the backoff before the next (resuming) attempt. */
    RETRYING,

    /** Finished: the `.map` is installed and usable. */
    SUCCESS,

    /** Gave up after exhausting retries. The `.part` is KEPT so the rider can resume later. */
    FAILED,

    /** The rider cancelled; the `.part` was deleted. */
    CANCELED,
}

/**
 * Immutable snapshot of the ONE app-scoped Mapsforge `.map` download the library runs at a time,
 * published through [OfflineManager.mapsforgeDownloadState] as a `StateFlow`. It OUTLIVES any single
 * screen: a host observes it to render a progress card, and re-reads it when the rider re-opens the
 * maps screen to RE-ATTACH to a still-running download (rather than starting a second one). The
 * transfer keeps going while no one is looking because it runs on a process-scoped worker, not in a
 * screen's coroutine scope.
 *
 * @property status which [MapsforgeDownloadStatus] the download is in.
 * @property url the source URL (kept so a [MapsforgeDownloadStatus.FAILED] download can be RESUMED).
 * @property name display name WITHOUT the `.map` extension (e.g. `colombia`).
 * @property fileName the on-disk target file name (e.g. `colombia.map`).
 * @property bytesRead bytes already on disk in the `.part` — the running total, resume offset included.
 * @property totalBytes the full size when known (from `Content-Range` / `Content-Length`), else `-1`.
 * @property attempt 1-based number of the current/last streaming attempt.
 * @property maxAttempts how many attempts the retry policy makes before giving up.
 * @property message a human status/error line (empty when there is nothing to say).
 */
data class MapsforgeDownloadState(
    val status: MapsforgeDownloadStatus,
    val url: String,
    val name: String,
    val fileName: String,
    val bytesRead: Long,
    val totalBytes: Long,
    val attempt: Int,
    val maxAttempts: Int,
    val message: String,
) {
    /** True while the download is live (streaming or waiting out a retry) — a host shows its progress card. */
    val isActive: Boolean
        get() = status == MapsforgeDownloadStatus.RUNNING || status == MapsforgeDownloadStatus.RETRYING

    /** Whole-percent progress `0..100`, or `-1` when the total size is unknown (the server omitted it). */
    val percent: Int
        get() = if (totalBytes > 0L) ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1

    companion object {
        /** The "nothing has happened yet" value the state flow holds until the first download starts. */
        val initial: MapsforgeDownloadState =
            MapsforgeDownloadState(MapsforgeDownloadStatus.IDLE, "", "", "", 0L, -1L, 0, 0, "")
    }
}
