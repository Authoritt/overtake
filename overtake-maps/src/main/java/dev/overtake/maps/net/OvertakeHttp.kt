// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.net

import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Neutral HTTP helper shared by the routing / tile / search backends. This is the SKELETON (Stage 0):
 * the request mechanics move in at Stage 2 (mirroring the fork's `AppHttp`), so the signatures are
 * fixed here and that move is a body-only change. Deliberately free of any specific map SDK.
 *
 * The host decouples us from Android connectivity via [networkProvider]: rather than binding a
 * `ConnectivityManager` in the library, the host supplies the [android.net.Network] our requests
 * should pin to (e.g. the cellular uplink on a bike Wi-Fi head unit). Null means "use the default
 * network".
 *
 * Visibility: `public` (not `internal`) so a consumer in a SEPARATE Gradle build — the OpenCfMoto
 * cockpit fork consuming this via a composite build — can install [networkProvider] at startup
 * (`OvertakeHttp.networkProvider = { host.internetNetwork() }`). If the surface is ever tightened,
 * route the seam through a public entry (e.g. `OvertakeMaps`) instead of re-`internal`-ing this.
 */
object OvertakeHttp {

    /** Guard against runaway responses (generic APIs). */
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

    /** Routing bodies can be large on long trips; allow more before we abort. */
    const val MAX_ROUTE_BYTES = 24 * 1024 * 1024

    /**
     * Host hook: returns the [android.net.Network] to pin requests to, or null for the default. Set
     * once by the host at startup. Volatile: written by the host, read from request threads.
     */
    @Volatile
    var networkProvider: (() -> android.net.Network?)? = null

    /** HTTP User-Agent sent on every request; the host overrides it via OvertakeMapsConfig. */
    @Volatile
    var userAgent: String = "Overtake"

    /** A non-2xx response (or transport failure). [retryable] is true for 429 / 5xx (back off + retry). */
    class HttpException(
        val code: Int,
        message: String,
        val retryable: Boolean,
    ) : IOException(message)

    /** True when the host reports a usable pinned network (proxy for "we have internet"). */
    fun hasInternetPin(): Boolean = networkProvider?.invoke() != null

    private val lastCallByHost = ConcurrentHashMap<String, Long>()

    /** Block just enough so [host] is hit at most once per [minIntervalMs] (courteous to public servers). */
    fun throttle(host: String, minIntervalMs: Long) {
        if (minIntervalMs <= 0) return
        synchronized(lastCallByHost) {
            val now = System.currentTimeMillis()
            val last = lastCallByHost[host] ?: 0L
            val wait = last + minIntervalMs - now
            if (wait in 1..minIntervalMs) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                }
            }
            lastCallByHost[host] = System.currentTimeMillis()
        }
    }

    /** GET returning the body text, or throws [HttpException] / IOException. */
    fun getText(
        url: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
        accept: String = "application/json",
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String = TODO("Stage 2: move AppHttp.getText mechanics in")

    /** POST an application/x-www-form-urlencoded body (Overpass). */
    fun postForm(
        url: String,
        formBody: String,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 25_000,
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String = TODO("Stage 2: move AppHttp.postForm mechanics in")

    /** POST a JSON body (OpenRouteService directions with avoids / alternatives). */
    fun postJson(
        url: String,
        jsonBody: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        accept: String = "application/json, application/geo+json",
        maxBytes: Int = MAX_RESPONSE_BYTES,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = TODO("Stage 2: move AppHttp.postJson mechanics in")

    /** Open a raw connection (the osmdroid tile fetch pins through here). */
    fun openUrl(url: String): HttpURLConnection = TODO("Stage 2: move AppHttp.openUrl mechanics in")
}
