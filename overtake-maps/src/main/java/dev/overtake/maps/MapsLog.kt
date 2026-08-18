// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps

import dev.overtake.OvertakeLog

/**
 * Map-module logging. Overtake takes no logging dependency; this forwards to the SAME host-installed
 * sink as the reader module ([dev.overtake.OvertakeLog.logger]) rather than reinventing one. It lives
 * in :overtake-maps only because OvertakeLog.w() is `internal` to :overtake and can't be called
 * across the module boundary — the sink itself (the host's `logger`) is what is shared. Stages 1-4
 * route all map diagnostics through here. Never throws: a bad host sink can't crash us.
 */
internal object MapsLog {
    fun w(tag: String, message: String, error: Throwable? = null) {
        val sink = OvertakeLog.logger ?: return
        runCatching { sink(tag, message, error) }
    }
}
