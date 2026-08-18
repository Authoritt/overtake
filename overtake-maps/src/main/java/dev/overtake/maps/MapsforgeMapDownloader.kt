// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import dev.overtake.maps.net.OvertakeHttp
import dev.overtake.maps.render.MapsforgeController
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Streams a catalog `.map` (hundreds of MB) into [MapsforgeController.mapsDir] without ever buffering
 * the whole file in memory: it copies the HTTP body through a fixed buffer into a `<name>.map.part`
 * temp file, then does an ATOMIC rename to `<name>.map` only on complete success. A failure or a
 * cancel deletes the `.part` so a half file never masquerades as a usable map. Progress is reported
 * as (bytesRead, totalBytes) — `totalBytes` from the HTTP `Content-Length` (or `-1` if the server
 * omits it).
 *
 * HTTP goes through [OvertakeHttp.openUrl], so the download rides the host's pinned uplink (cellular
 * while the phone sits on the bike's internet-less Wi‑Fi) exactly like the tile/route/search paths.
 * Runs on its own worker thread; callbacks fire on THAT thread — the host marshals to its UI thread.
 */
internal object MapsforgeMapDownloader {

    private const val BUFFER = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Emit a progress tick at most this often (bytes) so we don't flood the host's UI thread. */
    private const val PROGRESS_STEP = 512 * 1024L

    fun download(
        filesDir: File,
        url: String,
        name: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onDone: (ok: Boolean, message: String, file: File?) -> Unit,
    ): MapsforgeDownload {
        val canceled = AtomicBoolean(false)
        val fileName = if (name.endsWith(".map", ignoreCase = true)) name else "$name.map"
        val dir = MapsforgeController.mapsDir(filesDir)
        val dest = File(dir, fileName)
        val part = File(dir, "$fileName.part")

        val worker = thread(start = true, name = "mapsforge-dl") {
            var ok = false
            var message = ""
            try {
                // Bring the host's preferred uplink up before the big request (see OvertakeHttp).
                OvertakeHttp.ensureCellularUplink()
                runCatching { part.delete() }

                val conn = OvertakeHttp.openUrl(url).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", OvertakeHttp.userAgent)
                    setRequestProperty("Accept", "application/octet-stream, */*")
                }
                try {
                    conn.connect()
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        message = "HTTP $code"
                        return@thread onDone(false, message, null).also { cleanup(part) }
                    }
                    val total = conn.contentLengthLong
                    onProgress(0L, total)

                    conn.inputStream.use { input ->
                        part.outputStream().buffered(BUFFER).use { output ->
                            val buf = ByteArray(BUFFER)
                            var read = 0L
                            var lastTick = 0L
                            while (true) {
                                if (canceled.get()) throw CanceledException()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                if (read - lastTick >= PROGRESS_STEP) {
                                    lastTick = read
                                    onProgress(read, total)
                                }
                            }
                            output.flush()
                            onProgress(read, total)
                        }
                    }
                } finally {
                    runCatching { conn.disconnect() }
                }

                if (canceled.get()) throw CanceledException()

                // Atomic publish: rename the completed .part over the final name.
                if (dest.exists()) runCatching { dest.delete() }
                if (!part.renameTo(dest)) {
                    // Cross-filesystem or locked target — copy then drop the temp, still atomic-ish.
                    part.copyTo(dest, overwrite = true)
                    runCatching { part.delete() }
                }
                ok = true
                message = dest.name
            } catch (_: CanceledException) {
                ok = false
                message = "canceled"
                cleanup(part)
            } catch (e: IOException) {
                ok = false
                message = e.message ?: "I/O error"
                cleanup(part)
            } catch (e: Exception) {
                ok = false
                message = e.message ?: "error"
                cleanup(part)
            }
            onDone(ok, message, if (ok) dest else null)
        }

        return MapsforgeDownload {
            canceled.set(true)
            worker.interrupt()
        }
    }

    private fun cleanup(part: File) {
        runCatching { if (part.exists()) part.delete() }
    }

    private class CanceledException : IOException("canceled")
}
