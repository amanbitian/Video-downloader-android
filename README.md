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
* Sniffs media requests (`.mp4`, `.webm`, `.m3u8`, `.mpd`, audio…) in `shouldInterceptRequest()` after a static-asset pre-filter.
* Injected script reports `<video>`/`<audio>` sources when they load or start playing.
* Extension-less media (requests sent with `Range: bytes=0-`) is confirmed with a HEAD / one-byte probe of its `Content-Type`.
* Any file the page offers as a download (PDF, ZIP, images, …) opens the download sheet directly.
* Candidates carry the page's real User-Agent, Referer and cookies, are named after the page (`og:title`), and show their size.
* YouTube is blocked, as Google Play requires.

### 3. Download Engine (`DownloadService` & `DownloadCoordinator`)
* **Validated resume:** `.part` files in no-backup storage, `Range` + `If-Range` (ETag / Last-Modified) so a changed file is never appended to; HTTP 416 handled.
* **HLS:** unencrypted TS playlists, checkpointed after every segment so an interrupted download resumes mid-stream. Variants that carry no audio are labelled.
* **Automatic retries** with exponential backoff for network errors and 5xx/408/429.
* **Queue:** configurable simultaneous downloads (1–5), queued items shown as such, optional Wi-Fi-only.
* **State survives process death**, including request headers, so paused downloads can resume after a restart.
* **Saved to shared storage** via MediaStore: `Movies/Fetch`, `Music/Fetch`, `Pictures/Fetch`, `Download/Fetch`, with the correct extension for the real content type.
* **Notifications:** progress with Pause/Cancel, and completion/failure alerts that open the Downloads screen.

### 4. Library & Player
* Downloads list with thumbnails, type filters, open, share, remove, delete file, and clear finished.
* Built-in Media3 (ExoPlayer) player for video and audio, including HLS `.ts` output; formats it can't decode are handed to another app.

### 5. Settings
* Simultaneous downloads, Wi-Fi only, light/dark/system theme, clear browsing data.

---

## ⚠️ Not supported yet
DASH downloads · fMP4 / AES-128 HLS · muxing separate HLS audio · converting `.ts` to `.mp4` · multi-connection downloads · password-protected private folder · SD-card location picker · `blob:`/MediaSource-only players.

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
