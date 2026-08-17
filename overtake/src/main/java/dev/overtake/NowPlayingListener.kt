// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The [NotificationListenerService] behind Overtake's read-only mirrors. The host app must declare
 * intent to use it (this library contributes the manifest entry) and the user must enable it in
 * *Settings -> Notification access*.
 *
 * Reading other apps' active media sessions (Spotify, YouTube Music, the system player...) via
 * [android.media.session.MediaSessionManager.getActiveSessions] is gated on the caller being an
 * **enabled notification listener**. Android has no dedicated "media session read" permission; the
 * platform reuses the notification-listener grant as the trust anchor.
 *
 * So this class is registered in the manifest and granted by the user in
 * *Settings -> Notification access*. Once granted, [NowPlaying] can pass this component to
 * `getActiveSessions(...)` and observe playback.
 *
 * It also taps the notification **stream** the same grant exposes: on connect and on every
 * posted/removed notification we hand the current active set to [CockpitNotifications], which distils
 * it into the nav-guidance ([NavGuidance]) and call ([CallState]) panels. The media-session path
 * above is unchanged; this class still owns no state of its own.
 */
class NowPlayingListener : NotificationListenerService() {

    // `activeNotifications` throws if accessed before the listener is connected, so every read is
    // guarded — a throwing callback would tear down the whole notification-access binding.
    private fun pushActive() {
        runCatching { CockpitNotifications.refresh(activeNotifications ?: emptyArray()) }
            .onFailure { OvertakeLog.w("NowPlayingListener", "pushActive failed", it) }
    }

    override fun onListenerConnected() = pushActive()

    override fun onNotificationPosted(sbn: StatusBarNotification?) = pushActive()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = pushActive()
}
