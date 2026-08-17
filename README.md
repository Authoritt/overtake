# Overtake

**The reader half of an Android Auto alternative.** Overtake reads what a driving/riding
dashboard needs — the now-playing track, turn-by-turn navigation, and the active call — from
whatever apps are already running on the phone, **without Android Auto**, and exposes them as clean,
observable Kotlin `StateFlow`s. A host app renders them wherever it wants: a car or motorcycle dash,
a second screen, a HUD. Overtake only *reads*; the projection is the host's job.

It is the reader engine extracted from the [OpenCfMoto](https://alexandru.rocks) cockpit and
re-packaged as a small, permission-light Android library.

## What it reads

| Source | How | Public flow |
|---|---|---|
| **Music** (now playing) | `MediaSessionManager` / `MediaController` — observes the active media session of any app (Spotify, YouTube Music, the system player…). No audio-focus hijack, no playback of our own; just a read plus the three transport verbs. | `NowPlaying.state` |
| **Navigation** (turn-by-turn) | `NotificationListenerService` — reads the *ongoing* Google Maps / Waze guidance notification and distils the next maneuver. We do not project the maps app; we mirror its prompt. | `NavGuidance.state` |
| **Calls** | The same notification stream — reads the dialer/telecom `CATEGORY_CALL` card, harvesting the caller, the ring state, and the Answer / Hang-up `PendingIntent`s. | `CallState.state` |

All three share **one grant**: the notification-listener access the user toggles in
*Settings → Notification access*. Android has no dedicated "read media sessions" permission — the
platform reuses the notification-listener grant as the trust anchor, so enabling Overtake's listener
unlocks all three readers at once.

## Public API

Everything lives in the `dev.overtake` package.

- **`NowPlaying`** (object) — music reader with a lifecycle.
  - `val state: StateFlow<NowPlayingState>`
  - `fun start(ctx: Context)` / `fun stop(ctx: Context)` — register/unregister the platform listeners; call in pairs.
  - `fun hasAccess(ctx: Context): Boolean` — is the notification-listener grant enabled?
  - `fun playPause()` · `fun next()` · `fun prev()` — forward transport controls to the active session.
  - `data class NowPlayingState(hasAccess, hasSession, title, artist, art: Bitmap?, playing)`
- **`NavGuidance`** (object) — no lifecycle; fed by the listener.
  - `val state: StateFlow<NavState>`
  - `data class NavState(active, instruction, detail, source)`
- **`CallState`** (object) — no lifecycle; fed by the listener.
  - `val state: StateFlow<CallInfo>`
  - `fun answer()` · `fun hangup()` — fire the dialer's captured `PendingIntent`s.
  - `data class CallInfo(active, caller, status, ringing)`
- **`NowPlayingListener`** (`NotificationListenerService`) — the grant anchor. Declared in this
  library's manifest and merged into the host; the **host enables it** (see below). It owns no state
  and drives `NavGuidance` / `CallState` internally.
- **`OvertakeLog`** (object) — optional. `var logger: ((tag, message, error) -> Unit)?`, default
  `null` (no-op). The readers swallow their own exceptions by design; set this to observe those
  otherwise-silent failures (Logcat, Timber, your own bus).

> `NavGuidance` and `CallState` are updated only from within the library (their `update` is
> `internal`); the host observes their `state` and, for calls, calls `answer()` / `hangup()`.

## Usage

```kotlin
import dev.overtake.NowPlaying
import dev.overtake.NavGuidance
import dev.overtake.CallState

// Music has a lifecycle: start observing (idempotent), stop when done.
NowPlaying.start(context)

lifecycleScope.launch {
    NowPlaying.state.collect { np ->
        if (!np.hasAccess) promptForNotificationAccess()   // grant missing → send user to settings
        else renderMusic(np.title, np.artist, np.art, np.playing)
    }
}
NowPlaying.playPause()   // next() / prev() likewise

// Nav + calls need no start(): the notification listener feeds them once the grant is on.
lifecycleScope.launch {
    NavGuidance.state.collect { nav ->
        if (nav.active) renderNav(nav.instruction, nav.detail, nav.source)
    }
}
lifecycleScope.launch {
    CallState.state.collect { call ->
        if (call.active) renderCall(call.caller, call.status, ringing = call.ringing)
    }
}
CallState.answer()   // or CallState.hangup()

// Optional: surface the readers' silent failures.
dev.overtake.OvertakeLog.logger = { tag, msg, err -> android.util.Log.w("Overtake/$tag", msg, err) }

// When you no longer need the music reader:
NowPlaying.stop(context)
```

## Enabling the notification-listener grant

Overtake contributes the `NowPlayingListener` service (with
`BIND_NOTIFICATION_LISTENER_SERVICE`) to the merged manifest, but Android requires the **user** to
turn it on. The host sends them to the system screen and checks the result:

```kotlin
// Take the user to the toggle:
startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

// Are we enabled? (also reflected as NowPlayingState.hasAccess)
val granted = NowPlaying.hasAccess(context)
```

Until the grant is on, `NowPlaying.state.hasAccess` is `false` and `NavGuidance` / `CallState` stay
inactive.

## Projection is the host's job

Overtake is deliberately the *reader half* only. It does not draw anything, does not own a display,
and does not decide where the dashboard appears. It turns three messy platform sources into three
tidy `StateFlow`s and stops there. Rendering them onto an external screen — a car head unit, a
motorcycle dash over a `Presentation` / virtual display, a paired tablet — and any styling, layout,
or safety gating is entirely the host app's responsibility. That separation is the point: the same
engine can drive any projection.

## Build

Standalone Android library. Requires JDK 17+ and the Android SDK.

```bash
./gradlew :overtake:assembleDebug     # or assembleRelease for the shippable AAR
```

Toolchain: Gradle 9.4.1 · Android Gradle Plugin 9.2.1 · Kotlin 2.2.0 (AGP 9's built-in Kotlin) ·
compileSdk 36 · minSdk 29. Runtime dependencies are only `androidx.core:core-ktx` and
`org.jetbrains.kotlinx:kotlinx-coroutines-android`.

To consume it from a host app, include the module in a composite build
(`implementation(project(":overtake"))`) or depend on the produced AAR. (No Maven artifact is
published yet.)

## License

**AGPL-3.0-or-later.** Overtake is a derivative work of OpenCfMoto and inherits its license — see
[`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). If you distribute Overtake, or run a modified version as
a network-accessible service, you must make the complete corresponding source available under the
same license and preserve the copyright and attribution notices.

Derived from [OpenCfMoto](https://alexandru.rocks) (Alexandru and the OpenCfMoto contributors),
AGPL-3.0. © 2026 Authoritt and the Overtake contributors.
