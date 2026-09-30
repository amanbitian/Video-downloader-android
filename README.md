# Fetch: Modern Android Video Downloader & Browser

**Fetch** is an Android app that combines a full-featured WebView browser with a media detection engine, a resumable download manager and an offline player.

---

## 🚀 Core Features

### 1. Browser
* **Real tabs:** every tab keeps its own WebView, so switching tabs or screens never reloads a page or loses its back stack. Tabs survive process death and restarts.
* **Navigation:** back / forward / reload / stop / home, page-load progress, system Back walks page history.
* **Fullscreen video**, pop-up windows opened by a tap (untapped pop-ups are blocked), file uploads, desktop-site mode.
* **Long-press menu** on links and images: open in new/background tab, download, copy, share.
* **Home page** with site shortcuts and bookmarks; **bookmarks & history**.
* **Share links into Fetch** from any app, open http(s) links with it, and get offered a link you just copied.

### 2. Media Discovery
* Sniffs media requests (`.mp4`, `.webm`, `.m3u8`, `.mpd`, audio…) from pages **and service workers** through one pipeline, after a static-asset pre-filter.
* **One video, several qualities:** HLS/DASH manifests become one entry with a row per resolution (codec, bitrate, `~size` from bitrate × duration). Files a manifest owns — its playlists, segments and byte-range slices fetched by streaming (MSE) players — are absorbed instead of listed as separate videos. Files named by quality (`clip_480p.mp4`, `clip_1080p.mp4`) and alternative `<source>` formats of one `<video>` are grouped the same way.
* **Page isolation:** every page is a session; lookups for the previous page are cancelled, and any result that still arrives is committed only if its session is current (checked under the state lock).
* Injected script reports `<video>`/`<audio>` sources when they load or start playing.
* Extension-less media (requests sent with `Range: bytes=0-`) is confirmed with a HEAD / one-byte probe of its `Content-Type`.
* Any file the page offers as a download (PDF, ZIP, images, …) opens the download sheet directly.
* Candidates carry the page's real User-Agent, Referer and cookies, are named after the page (`og:title`, with site suffixes like " - YouTube" removed conservatively), and show a thumbnail (`og:image` or the video poster).
* DRM (`ContentProtection`, HLS keys) and live streams are reported in the sheet up front instead of failing mid-download.
* YouTube is blocked, as Google Play requires.

### 3. Download Engine (`DownloadService` & `DownloadCoordinator`)
* **Validated resume:** `.part` files in no-backup storage, `Range` + `If-Range` (ETag / Last-Modified) so a changed file is never appended to; HTTP 416 handled.
* **HLS & DASH:** TS and fMP4 segments, byte-range single-file playlists, SegmentTemplate (`$Number$`/`$Time$`, timelines), SegmentList and single-file representations — checkpointed after every segment so an interrupted download resumes mid-stream.
* **Separate audio is merged:** video and the chosen audio (main track, default or device language, a codec that fits the container) are downloaded as separate tracks and combined with `MediaMuxer` into MP4 (H.264/H.265 + AAC) or WebM (VP8/VP9 + Opus/Vorbis) — no re-encoding. HLS `.ts` output is converted to MP4 the same way.
* **Free-space check** before large downloads (download + merge + shared-storage copy); orphaned temp files are swept on start.
* **Automatic retries** with exponential backoff for network errors and 5xx/408/429.
* **Queue:** configurable simultaneous downloads (1–5), queued items shown as such, optional Wi-Fi-only.
* **State survives process death**, including request headers, so paused downloads can resume after a restart.
* **Saved to shared storage** via MediaStore: `Movies/Fetch`, `Music/Fetch`, `Pictures/Fetch`, `Download/Fetch`, with the correct extension for the real content type.
* **Notifications:** progress with Pause/Cancel, and completion/failure alerts that open the Downloads screen.

### 4. Library & Player
* Downloads list with thumbnails, type filters, open, share, remove, delete file, and clear finished.
* Built-in Media3 (ExoPlayer) player for video and audio; formats it can't decode are handed to another app.

### 5. Settings
* Simultaneous downloads, Wi-Fi only, light/dark/system theme, clear browsing data.

---

## ⚠️ Not supported yet
DRM-protected or AES-128-encrypted streams · live streams · DASH beyond the first period · AV1 · subtitles · multi-connection downloads · password-protected private folder · SD-card location picker · players whose only source is a `blob:` URL with no fetchable manifest.

---

## 🛠️ Tech Stack & Architecture
* **Language:** Kotlin 2.0+
* **UI Toolkit:** Jetpack Compose & Material 3
* **Concurrency:** Kotlin Coroutines & StateFlow
* **Networking:** `HttpURLConnection` with range requests
* **Playback:** AndroidX Media3 ExoPlayer
* **Storage:** Scoped Storage via `MediaStore`, `SharedPreferences` persistence

See [ARCHITECTURE.md](ARCHITECTURE.md) for details.

---

## 📦 Building & Running

1. **Prerequisites:**
   * Android Studio Koala / Ladybug or newer.
   * JDK 21 installed.
2. **Build from Command Line:**
   ```bash
   ./gradlew assembleDebug
   ```
3. **Install Debug Build:**
   ```bash
   ./gradlew installDebug
   ```
4. **Unit tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```
