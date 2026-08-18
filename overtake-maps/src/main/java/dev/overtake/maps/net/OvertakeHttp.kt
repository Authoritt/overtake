// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

    /**
     * Host hook: proactively (re)establish the uplink requests should ride. On a bike Wi-Fi head
     * unit the fork pins a CELLULAR uplink so routing reaches the internet even while the phone sits
     * on the bike's internet-less Wi-Fi. The routing engines call [ensureCellularUplink] before a
     * request burst. Null = nothing to do (the default network is used). Set once by the host at
     * startup, alongside [networkProvider].
     */
    @Volatile
    var uplinkEnsurer: (() -> Unit)? = null

    /**
     * Host hook: true when the pinned uplink is specifically CELLULAR (vs another validated internet
     * network). Diagnostics only — the "uplink=cellular/internet/NONE" log line; routing decisions
     * use [hasInternetPin]. Null → reported as not-cellular.
     */
    @Volatile
    var cellularPinChecker: (() -> Boolean)? = null

    /** Ask the host to (re)establish its preferred uplink before a request burst (see [uplinkEnsurer]). */
    fun ensureCellularUplink() {
        uplinkEnsurer?.invoke()
    }

    /** True when the host reports the pinned uplink is cellular (diagnostics only; see [cellularPinChecker]). */
    fun isCellularPin(): Boolean = cellularPinChecker?.invoke() ?: false

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
    ): String {
        val conn = open(url).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", accept)
        }
        return conn.readOrThrow(maxBytes)
    }

    /** POST an application/x-www-form-urlencoded body (Overpass). */
    fun postForm(
        url: String,
        formBody: String,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 25_000,
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String {
        val conn = open(url).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        conn.outputStream.bufferedWriter().use { it.write(formBody) }
        return conn.readOrThrow(maxBytes)
    }

    /** POST a JSON body (OpenRouteService directions with avoids / alternatives). */
    fun postJson(
        url: String,
        jsonBody: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        accept: String = "application/json, application/geo+json",
        maxBytes: Int = MAX_RESPONSE_BYTES,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val conn = open(url).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", accept)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            for ((k, v) in extraHeaders) setRequestProperty(k, v)
        }
        conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonBody) }
        return conn.readOrThrow(maxBytes)
    }

    /** Open a raw connection (the osmdroid tile fetch pins through here). */
    fun openUrl(url: String): HttpURLConnection = open(url)

    /** URL-encode a query value (UTF-8), for building request URLs / form bodies. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Open [url] over the host's pinned [android.net.Network] when one is supplied, else the process
     * default. `Network.openConnection` binds the returned connection to THAT network's socket factory
     * and DNS resolver internally — so the request rides the host's cellular pin even while the process
     * default route is the bike's internet-less Wi‑Fi (the OkHttp path in later stages must set
     * socketFactory + DNS by hand to achieve the same; a raw URLConnection gets it from the Network).
     */
    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val net = networkProvider?.invoke()
        val conn = net?.openConnection(u) ?: u.openConnection()
        return conn as HttpURLConnection
    }

    private fun HttpURLConnection.readOrThrow(maxBytes: Int): String {
        try {
            val code = responseCode
            if (code !in 200..299) {
                // Drain the error stream so the connection can be reused/closed cleanly.
                runCatching { errorStream?.readBoundedText(maxBytes) }
                val retryable = code == 429 || code in 500..599
                val msg = when {
                    code == 429 -> "Map service is busy (rate limited) — try again shortly"
                    code in 500..599 -> "Map service temporarily unavailable (HTTP $code)"
                    else -> "Request failed (HTTP $code)"
                }
                throw HttpException(code, msg, retryable)
            }
            return inputStream.readBoundedText(maxBytes)
        } finally {
            disconnect()
        }
    }

    private fun java.io.InputStream.readBoundedText(maxBytes: Int): String {
        // HttpURLConnection transparently handles gzip when we don't set Accept-Encoding.
        val out = StringBuilder()
        reader(Charsets.UTF_8).use { r ->
            val buf = CharArray(16 * 1024)
            var total = 0
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("Response too large")
                out.append(buf, 0, n)
            }
        }
        return out.toString()
    }
}
