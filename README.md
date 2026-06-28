# VoIP.ms MMS #

A clean, modern Android messaging app for [VoIP.ms](https://voip.ms) — with
**MMS / picture messaging**, faster sync, and privacy-respecting push
notifications.

## A fork of VoIP.ms SMS ##

This is a fork of [VoIP.ms SMS](https://github.com/michaelkourlas/voipms-sms-client)
by Michael Kourlas, the creator of the original app. This fork builds on top of
his work to add MMS support and the improvements listed below. Thanks to Michael
for releasing it as open source.

The original — under the Apache 2.0 license, whose copyright notices this fork
keeps intact — is available on
[Google Play](https://play.google.com/store/apps/details?id=net.kourlas.voipms_sms)
and [F-Droid](https://f-droid.org/packages/net.kourlas.voipms_sms).

> **Note:** this fork ships as a separate app (**VoIP.ms MMS**, application id
> `com.verbalp.voipms_mms`), so it can be installed alongside the original
> without conflict. See [Migration](#migration) to bring your message history
> over.

## What's new in this fork ##

### MMS & media support
* **Send and receive MMS** — picture, audio and video messages.
* Inline media display in conversations, with on-demand download, caching, and
  an optional auto-download setting.
* **Send images** via the system photo picker, with automatic compression and an
  inline preview before sending.
* Inline audio playback, multi-file attachments, and image thumbnails in
  notifications.
* Long text messages are sent as MMS instead of being truncated.
* **Non-Latin text preserved** — Chinese, emoji and other non-Latin characters
  are no longer dropped or truncated by the API.
* Escaped apostrophes returned by the VoIP.ms API are unescaped correctly.

### Reactions (tapbacks) support
* Inbound SMS reactions (👍 / ❤️ / etc.) are recognized and shown as a small
  emoji chip on the message they react to, instead of a raw
  `Liked "…"` / `a attribué la mention …` text line. A friendly preview
  (e.g. `👍 …`) is also used in the conversation list and notifications.

### Performance improvements
* **Conversation list loads in <100 ms** (was several seconds on large
  databases) thanks to lazy contact loading, a single grouped query, and
  composite database indexes.
* Bulk duplicate detection and batched transactions during sync.
* **Faster refresh:** pull-to-refresh now starts syncing immediately, and the
  VoIP.ms API calls run in parallel with bounded concurrency — a full multi-DID
  refresh dropped from ~17 s to ~6 s on a test device.
* Resilient requests: exponential backoff with jitter, and graceful handling of
  the VoIP.ms per-minute rate limit instead of aborting the whole sync.

### Sync reliability
* Fixed background sync silently dropping messages on Android 15+ (caused by a
  foreground-service start exception that wasn't being caught).

### Flexible push notifications (FCM *or* UnifiedPush)
* The **`primary`** build supports **both** push methods and lets you switch at
  runtime, in **Settings → Synchronization → Notification method**:
  * **Google Play Services (FCM)** — the Google/Firebase push path.
  * **UnifiedPush** — privacy-respecting push via **UnifiedPush + ntfy**, with no
    Google Play Services or Firebase.
* The **`fdroid`** build is 100% FOSS and uses **UnifiedPush only**.
* Both methods use the same small **self-hostable relay** (a Cloudflare Worker),
  with a **Default / Custom relay** toggle and **per-DID targeted sync** so a push
  only fetches the number it was sent for — making notifications fast.
* **UnifiedPush requires the [ntfy](https://ntfy.sh) app** (a UnifiedPush
  distributor) installed and opened on the device — it is what actually delivers
  the push without Google. Without a distributor, the app falls back to periodic
  background sync (no instant notifications).

## Migration ##

Both the original **VoIP.ms SMS** app and this **VoIP.ms MMS** app store messages
in a standard SQLite database and can export/import it, so you can carry your
full message history over. Because the two apps have different application ids,
they install side by side and you won't lose anything during the move.

1. **Export from the original app.** In **VoIP.ms SMS**, go to
   **Settings → Database → Export database** and save the file somewhere you can
   find it (for example, your *Downloads* folder).
2. **Install VoIP.ms MMS** (this app).
3. **Sign in and set up.** Open VoIP.ms MMS, sign in to your VoIP.ms account, and
   configure your DIDs and notifications.
   *Account credentials and app settings are **not** part of the database export,
   so they must be re-entered here.*
4. **Import into this app.** Go to **Settings → Database → Import database** and
   select the file you exported in step 1. The database is automatically upgraded
   to this app's schema, and your conversations and media will appear.
5. Once you've confirmed everything is there, you can uninstall the original app.

> Only messages (and their media references) are transferred — not your account
> credentials or settings.

## Building ##

The project uses two product flavors:

* **`primary`** — a combined build supporting **both** push methods, selectable at
  runtime: Google's (closed-source) Firebase libraries for **FCM**, **and**
  **UnifiedPush** (ntfy). This is the build distributed for sideloading. It
  requires a `google-services.json` (FCM) at `voipms-sms/`.
* **`fdroid`** — completely open source; **UnifiedPush (ntfy) only**, with no
  Firebase.

Build a debug APK with Gradle, for example:

```sh
./gradlew assemblePrimaryFullDebug   # FCM + UnifiedPush (needs google-services.json)
./gradlew assembleFdroidFullDebug    # UnifiedPush only, fully FOSS
```

## Documentation ##

General app documentation is available in the
[HELP.md file](HELP.md).

## License ##

VoIP.ms MMS, like the original VoIP.ms SMS, is licensed under the
[Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0). The original
copyright notices by Michael Kourlas are retained throughout the source.

Google Play and the Google Play logo are trademarks of Google LLC.
