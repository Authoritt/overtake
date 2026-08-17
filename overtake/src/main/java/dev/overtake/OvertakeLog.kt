// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
// A pluggable, library-local logging sink. Overtake depends on no host logging facility and never
// logs by default: every reader swallows its own exceptions (a throwing NotificationListener callback
// would tear down the whole notification grant). This hook lets a host observe those otherwise-
// invisible failures without Overtake taking a logging dependency — route it into Logcat, Timber, or
// the host's own bus. Default is null = no-op.
package dev.overtake

/**
 * Process-global, pluggable log sink for Overtake's deliberately-silent failure paths.
 *
 * The reader objects catch and swallow their own exceptions by design; when [logger] is set they
 * additionally forward a short diagnostic. Thread-safe enough for the library's needs: [logger] is a
 * single volatile reference, written by the host (typically once, at startup) and read from the main
 * thread and the notification-listener callback thread.
 */
object OvertakeLog {

    /**
     * Host-supplied sink, or null (the default) for no logging. Signature: `(tag, message, error)`.
     * The tag and message are never null; [error] is null for pure warnings.
     */
    @Volatile
    var logger: ((tag: String, message: String, error: Throwable?) -> Unit)? = null

    /** Forward a warning to [logger] if one is installed. Never throws — a bad host sink can't crash us. */
    internal fun w(tag: String, message: String, error: Throwable? = null) {
        val sink = logger ?: return
        runCatching { sink(tag, message, error) }
    }
}
