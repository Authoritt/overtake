// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import dev.overtake.maps.net.OvertakeHttp
import dev.overtake.maps.render.MapsforgeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The ONE app-scoped, RESUMABLE downloader for catalog `.map` files (hundreds of MB). A `.map` is
 * streamed through a fixed buffer into `<name>.map.part` and atomically renamed to `<name>.map` only
 * once the file reaches its FULL size — a half file never masquerades as a usable map.
 *
 * This is a process-level singleton on purpose: the worker + its [state] live OUTSIDE any screen, so
 * the transfer keeps running when the rider navigates away and the host can re-attach to it by simply
 * re-reading [state]. Only one download runs at a time.
 *
 * Robustness (the reason it exists):
 *  - **Resume via HTTP Range.** On (re)start, if `<name>.map.part` already holds `partSize` bytes we
 *    send `Range: bytes=partSize-` and APPEND. `partSize` is BOTH the count of bytes on disk AND the
 *    0-based index of the next byte we need (we hold indices `0..partSize-1`), so the requested range
 *    begins at exactly the first missing byte — no gap, no overlap (the classic off-by-one, handled).
 *    `206 Partial Content` ⇒ seek to the `Content-Range` start and append; `200 OK` (server ignored
 *    the Range) ⇒ truncate the `.part` and restart from 0. The full size comes from `Content-Range`
 *    `bytes X-Y/TOTAL` (or `partSize + Content-Length` when resuming, or `Content-Length` on a 200);
 *    the rename fires only when the file length equals that total (or on a clean EOF when the server
 *    gave no size at all).
 *  - **Auto-retry with backoff.** A transient failure (timeout, reset, short read, 429/5xx) is retried
 *    up to [MAX_ATTEMPTS] times — resuming via Range each time — with exponential backoff
 *    ([INITIAL_BACKOFF_MS] → … → [MAX_BACKOFF_MS]). The `.part` is KEPT across every transient error;
 *    a hard [MapsforgeDownloadStatus.FAILED] surfaces ONLY after the retries are exhausted (or on a
 *    definitive HTTP refusal), still keeping the `.part` so the rider can resume later. The `.part` is
 *    deleted ONLY on explicit cancel or a genuinely corrupt state (`.part` bigger than the resource).
 *
 * HTTP goes through [OvertakeHttp.openUrl], so every attempt rides the host's pinned uplink (cellular
 * while the phone sits on the bike's internet-less Wi-Fi) exactly like the tile/route/search paths.
 */
internal object MapsforgeMapDownloader {

    private const val BUFFER = 64 * 1024

    /** Short-ish connect (fail fast to a retry if the link is down) … */
    private const val CONNECT_TIMEOUT_MS = 15_000

    /** … but a GENEROUS per-read timeout: a big file on a slow cellular link streams slowly, and a
     *  real stall is caught by the retry loop rather than by an aggressive read deadline. */
    private const val READ_TIMEOUT_MS = 60_000

    /** Emit a progress tick at most this often (bytes) so we don't flood observers. */
    private const val PROGRESS_STEP = 512 * 1024L

    private const val MAX_ATTEMPTS = 6
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L

    private val lock = Any()

    private val _state = MutableStateFlow(MapsforgeDownloadState.initial)

    /** Live progress/status of the single active (or last) download; survives navigation. */
    val state: StateFlow<MapsforgeDownloadState> = _state.asStateFlow()

    @Volatile
    private var worker: Thread? = null

    /** The cancel flag of the CURRENT run (a fresh instance per [start]). */
    @Volatile
    private var canceled: AtomicBoolean = AtomicBoolean(false)

    /** The in-flight connection, so [cancel] can break a blocked read immediately. */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /**
     * Start — or RE-ATTACH to — the single active download. If a `<name>.map.part` from an earlier run
     * exists this RESUMES it via HTTP Range. Returns true when a download for this [url]+[name] is now
     * running (or already was); false when a DIFFERENT download is in flight (one at a time).
     */
    fun start(filesDir: File, url: String, name: String): Boolean {
        val fileName = if (name.endsWith(".map", ignoreCase = true)) name else "$name.map"
        val display = fileName.removeSuffix(".map").removeSuffix(".MAP")
        synchronized(lock) {
            val w = worker
            if (w != null && w.isAlive) {
                // A download is already running: only "succeed" if it's the SAME target (re-attach).
                val cur = _state.value
                return cur.url == url && cur.fileName == fileName
            }
            val myCanceled = AtomicBoolean(false)
            canceled = myCanceled
            val dir = MapsforgeController.mapsDir(filesDir)
            val dest = File(dir, fileName)
            val part = File(dir, "$fileName.part")
            val startBytes = part.lengthOr0()
            // Publish RUNNING synchronously so the host's UI shows the card the INSTANT it starts,
            // before the first (slower) network round-trip — part of "faster start".
            publish(
                MapsforgeDownloadStatus.RUNNING, url, display, fileName,
                bytesRead = startBytes, totalBytes = -1L, attempt = 1, message = "",
            )
            worker = thread(start = true, name = "mapsforge-dl") {
                runDownload(url, dest, part, display, fileName, myCanceled)
            }
            return true
        }
    }

    /** Cancel the active download: flag it, break the socket, interrupt the worker, delete the `.part`. */
    fun cancel() {
        synchronized(lock) {
            canceled.set(true)
            runCatching { activeConn?.disconnect() }
            worker?.interrupt()
        }
    }

    // --- worker ----------------------------------------------------------------------------------

    private fun runDownload(
        url: String,
        dest: File,
        part: File,
        display: String,
        fileName: String,
        canceled: AtomicBoolean,
    ) {
        var attempt = 0
        var backoff = INITIAL_BACKOFF_MS
        // The full size, learned from the first response that carries it; kept across attempts so
        // RETRYING/progress ticks still show the right total.
        var knownTotal = _state.value.totalBytes
        val setTotal: (Long) -> Unit = { t ->
            knownTotal = t
            publish(MapsforgeDownloadStatus.RUNNING, url, display, fileName, part.lengthOr0(), knownTotal, attempt, "")
        }
        val progress: (Long) -> Unit = { written ->
            publish(MapsforgeDownloadStatus.RUNNING, url, display, fileName, written, knownTotal, attempt, "")
        }
        try {
            // Bring the host's preferred (cellular) uplink up before the big request (see OvertakeHttp).
            runCatching { OvertakeHttp.ensureCellularUplink() }
            while (true) {
                if (canceled.get()) throw CanceledException()
                attempt++
                publish(MapsforgeDownloadStatus.RUNNING, url, display, fileName, part.lengthOr0(), knownTotal, attempt, "")
                when (val r = attemptOnce(url, dest, part, canceled, setTotal, progress)) {
                    is Attempt.Success -> {
                        publish(
                            MapsforgeDownloadStatus.SUCCESS, url, display, fileName,
                            dest.lengthOr0(), dest.lengthOr0(), attempt, dest.name,
                        )
                        return
                    }
                    is Attempt.Canceled -> throw CanceledException()
                    is Attempt.Fatal -> {
                        // A definitive server refusal (e.g. 404/403). Keep the `.part` — it is harmless
                        // and lets a later manual retry resume if the URL comes back.
                        publish(
                            MapsforgeDownloadStatus.FAILED, url, display, fileName,
                            part.lengthOr0(), knownTotal, attempt, r.message,
                        )
                        return
                    }
                    is Attempt.Retryable -> {
                        if (attempt >= MAX_ATTEMPTS) {
                            publish(
                                MapsforgeDownloadStatus.FAILED, url, display, fileName,
                                part.lengthOr0(), knownTotal, attempt, r.message,
                            )
                            return
                        }
                        publish(
                            MapsforgeDownloadStatus.RETRYING, url, display, fileName,
                            part.lengthOr0(), knownTotal, attempt, r.message,
                        )
                        MapsLog.w(
                            "map",
                            "[MAP] mapsforge dl '$fileName' attempt $attempt failed: ${r.message} — retry in ${backoff}ms",
                        )
                        sleepBackoff(backoff, canceled)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                        runCatching { OvertakeHttp.ensureCellularUplink() }
                    }
                }
            }
        } catch (_: CanceledException) {
            cleanup(part)
            publish(MapsforgeDownloadStatus.CANCELED, url, display, fileName, 0L, -1L, attempt, "canceled")
        } catch (e: Exception) {
            // Never lose the partial on an unexpected error either.
            publish(
                MapsforgeDownloadStatus.FAILED, url, display, fileName,
                part.lengthOr0(), knownTotal, attempt, e.message ?: "error",
            )
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                activeConn = null
            }
        }
    }

    /**
     * ONE streaming attempt. Opens the connection (with `Range` when resuming), resolves the write
     * offset + total size from the response, then streams the body into the `.part`, appending or
     * restarting as the response dictates. Returns an [Attempt] outcome; never throws for I/O — a
     * transient failure becomes [Attempt.Retryable] so the caller can back off and resume.
     */
    private fun attemptOnce(
        url: String,
        dest: File,
        part: File,
        canceled: AtomicBoolean,
        setTotal: (Long) -> Unit,
        progress: (Long) -> Unit,
    ): Attempt {
        val partSize = part.lengthOr0()
        var conn: HttpURLConnection? = null
        try {
            conn = OvertakeHttp.openUrl(url).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", OvertakeHttp.userAgent)
                setRequestProperty("Accept", "application/octet-stream, */*")
                // RESUME: request only the bytes we don't have yet. We hold indices [0 .. partSize-1];
                // the next needed index is `partSize`, so `bytes=partSize-` starts at exactly the first
                // missing byte (no gap / no overlap). This is the off-by-one that MUST be right.
                if (partSize > 0L) setRequestProperty("Range", "bytes=$partSize-")
            }
            activeConn = conn
            conn.connect()
            if (canceled.get()) return Attempt.Canceled
            val code = conn.responseCode

            val writeOffset: Long
            var total: Long
            when {
                code == HttpURLConnection.HTTP_PARTIAL -> { // 206: server honoured the Range.
                    val cr = parseContentRange(conn.getHeaderField("Content-Range"))
                    if (cr != null && cr.first >= 0L) {
                        val start = cr.first
                        if (start > partSize) {
                            // Server started FURTHER ahead than we have — a gap we can't fill. Restart clean.
                            truncateToZero(part)
                            return Attempt.Retryable("range gap (server start $start > have $partSize)")
                        }
                        writeOffset = start          // start <= partSize: append (==) or overwrite tail (<)
                        total = cr.second            // -1 when the server sent `/*`
                    } else {
                        // 206 without a usable Content-Range: Content-Length is the REMAINING length.
                        val remaining = conn.contentLengthLong
                        writeOffset = partSize
                        total = if (remaining >= 0L) partSize + remaining else -1L
                    }
                }
                code == HttpURLConnection.HTTP_OK -> { // 200: server IGNORED the Range → full body from 0.
                    writeOffset = 0L
                    total = conn.contentLengthLong
                }
                code == 416 -> { // Requested Range Not Satisfiable.
                    val known = parseContentRange(conn.getHeaderField("Content-Range"))?.second ?: -1L
                    return if (known in 1..partSize && known == partSize) {
                        // We already hold exactly the whole file — just publish it.
                        finalize(part, dest)
                        Attempt.Success
                    } else {
                        // Our `.part` is bigger than the resource (corrupt/stale) or the size is unknown.
                        truncateToZero(part)
                        Attempt.Retryable("range not satisfiable (have $partSize, total $known)")
                    }
                }
                code == 429 || code in 500..599 -> return Attempt.Retryable("HTTP $code")
                else -> return Attempt.Fatal("HTTP $code")
            }

            if (total > 0L) setTotal(total)

            RandomAccessFile(part, "rw").use { raf ->
                // Force the on-disk length to be EXACTLY the resume offset before appending: a no-op
                // when writeOffset == partSize (normal resume), a truncate when the server made us
                // restart (200) or handed back an earlier start — so no stale tail survives.
                raf.setLength(writeOffset)
                raf.seek(writeOffset)
                var written = writeOffset
                var lastTick = written
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (canceled.get()) return Attempt.Canceled
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        written += n
                        if (total > 0L && written > total) {
                            // Wrote past the declared size — the source is inconsistent. Restart clean.
                            truncateToZero(part)
                            return Attempt.Retryable("overrun ($written > $total)")
                        }
                        if (written - lastTick >= PROGRESS_STEP) {
                            lastTick = written
                            progress(written)
                        }
                    }
                }
                runCatching { raf.fd.sync() }
                progress(written)
                return when {
                    total <= 0L -> { finalize(part, dest); Attempt.Success } // clean EOF, unknown total = done
                    written == total -> { finalize(part, dest); Attempt.Success }
                    else -> Attempt.Retryable("short read ($written/$total)") // closed early → resume next attempt
                }
            }
        } catch (_: SocketTimeoutException) {
            return if (canceled.get()) Attempt.Canceled else Attempt.Retryable("timeout")
        } catch (e: InterruptedIOException) {
            return if (canceled.get()) Attempt.Canceled else Attempt.Retryable(e.message ?: "interrupted")
        } catch (e: IOException) {
            return if (canceled.get()) Attempt.Canceled else Attempt.Retryable(e.message ?: "I/O error")
        } finally {
            activeConn = null
            runCatching { conn?.disconnect() }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Atomic publish: rename the completed `.part` over the final `.map`. */
    private fun finalize(part: File, dest: File) {
        if (dest.exists()) runCatching { dest.delete() }
        if (!part.renameTo(dest)) {
            // Cross-filesystem or locked target — copy then drop the temp, still atomic-ish.
            part.copyTo(dest, overwrite = true)
            runCatching { part.delete() }
        }
    }

    /** Delete the partial (explicit cancel / corrupt state ONLY — never on a transient error). */
    private fun cleanup(part: File) {
        runCatching { if (part.exists()) part.delete() }
    }

    private fun truncateToZero(part: File) {
        runCatching { RandomAccessFile(part, "rw").use { it.setLength(0L) } }
    }

    /**
     * Parse an HTTP `Content-Range` header into `(start, total)`, where `-1` means "the header used a
     * literal star for that field, so it is unknown":
     *  - `bytes 0-1023/2048`     -> `(0, 2048)`  — normal partial content.
     *  - total-unknown form       -> `(0, -1)`   — server gave the range but a star for the size.
     *  - 416 form (start is star) -> `(-1, 2048)` — only the total is meaningful there.
     */
    private fun parseContentRange(header: String?): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return null
        val v = header.trim().removePrefix("bytes").trimStart()
        val slash = v.indexOf('/')
        if (slash < 0) return null
        val rangePart = v.substring(0, slash).trim()
        val totalPart = v.substring(slash + 1).trim()
        val total = if (totalPart == "*") -1L else totalPart.toLongOrNull() ?: -1L
        val start = if (rangePart == "*") -1L else rangePart.substringBefore('-').trim().toLongOrNull() ?: -1L
        return start to total
    }

    private fun sleepBackoff(ms: Long, canceled: AtomicBoolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (canceled.get()) throw CanceledException()
            try {
                Thread.sleep(150L)
            } catch (_: InterruptedException) {
                if (canceled.get()) throw CanceledException()
            }
        }
    }

    private fun publish(
        status: MapsforgeDownloadStatus,
        url: String,
        name: String,
        fileName: String,
        bytesRead: Long,
        totalBytes: Long,
        attempt: Int,
        message: String,
    ) {
        _state.value = MapsforgeDownloadState(
            status = status,
            url = url,
            name = name,
            fileName = fileName,
            bytesRead = bytesRead,
            totalBytes = totalBytes,
            attempt = attempt,
            maxAttempts = MAX_ATTEMPTS,
            message = message,
        )
    }

    private fun File.lengthOr0(): Long = if (exists()) length() else 0L

    private sealed interface Attempt {
        object Success : Attempt
        object Canceled : Attempt
        data class Retryable(val message: String) : Attempt
        data class Fatal(val message: String) : Attempt
    }

    private class CanceledException : IOException("canceled")
}
