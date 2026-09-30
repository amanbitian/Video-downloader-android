# Architecture Documentation: Fetch

This document provides a technical overview of the architecture, data flows, threading model, and lifecycle management in **Fetch**.

---

## 🏛️ System Architecture Overview

Fetch is organised around a clean separation between the **Browser**, the **Media Discovery Pipeline**, the **Download Coordinator**, the **Background Transfer Engine** and the **Library/Player**.

```
                         ┌────────────────────────────────┐
                         │      Jetpack Compose UI        │
                         │ Browser · Downloads · Settings │
                         └───────────────┬────────────────┘
                                         │
                                         ▼
                         ┌────────────────────────────────┐
                         │       BrowserController        │
                         │  one WebView per tab + clients │
                         └───────────────┬────────────────┘
                                         │
        ┌────────────────────────────────┼────────────────────────────────┐
        ▼                                ▼                                ▼
 ┌──────────────┐              ┌──────────────────┐              ┌──────────────────┐
 │ Request sniff│              │ <video> JS bridge│              │ DownloadListener │
 │ (pre-filter) │              │ (play / metadata)│              │ + long-press menu│
 └──────┬───────┘              └────────┬─────────┘              └────────┬─────────┘
        └────────────────────────────────┼────────────────────────────────┘
                                         ▼
                         ┌────────────────────────────────┐
                         │      DownloadCoordinator       │
                         │ candidates · probe · HLS parse │
                         │  StateFlow<List<DownloadItem>> │
                         └───────────────┬────────────────┘
                                         │  startForegroundService(action, id)
                                         ▼
                         ┌────────────────────────────────┐
                         │        DownloadService         │
                         │ queue · retries · notifications│
                         └───────────────┬────────────────┘
             ┌───────────────────────────┴───────────────────────────┐
             ▼                                                       ▼
    ┌──────────────────┐                                    ┌──────────────────┐
    │ Direct transfer  │                                    │  HLS transfer    │
    │ Range + If-Range │                                    │ segment checkpts │
    └────────┬─────────┘                                    └────────┬─────────┘
             └───────────────────────────┬───────────────────────────┘
                                         ▼  no_backup/parts/<id>.part
                         ┌────────────────────────────────┐
                         │      MediaStore Publisher      │
                         │ Movies · Music · Pictures ·    │
                         │ Download  (…/Fetch)            │
                         └───────────────┬────────────────┘
                                         ▼
                         ┌────────────────────────────────┐
                         │  Downloads list · PlayerActivity│
                         └────────────────────────────────┘
```

---

## 🔍 Component Breakdown

### 1. App shell (`MainActivity.kt`, `ui/`)
* **`MainActivity`** owns the `BrowserController` for its lifetime, handles `ACTION_SEND` / `ACTION_VIEW` intents and the "open copied link?" prompt, and declares `configChanges` so rotation never tears down tabs or fullscreen video.
* **`FetchApp`** hosts the Browse / Downloads / Settings screens, the download sheet (also opened by `DownloadCoordinator.sheetRequests` for explicit downloads), system Back handling and the fullscreen video overlay.
* **`ui/`**: `DownloadSheet`, `DownloadsScreen` (thumbnails, filters, row actions), `SettingsScreen`, `Theme` (light/dark).

### 2. Browser (`browser/`)
* **`BrowserController`** keeps one `WebView` per tab, created lazily, so switching tabs or screens never reloads a page. It implements the WebView clients: navigation state, fullscreen (`onShowCustomView`), tapped pop-ups as new tabs (`onCreateWindow`), file chooser, external-app links (only on a user gesture, with component/selector stripped), long-press menu, desktop mode, and renderer-death recovery (the dead WebView is replaced and the tab's `generation` bumped).
* **`BrowserStore`** persists tabs, bookmarks and history in `SharedPreferences`.
* **`BrowserScreen`** is the Compose UI: address bar, progress bar, navigation toolbar, home page, tab switcher, bookmarks/history dialog, link menu.
* **`UrlUtils`**: address normalisation, URL extraction from shared text, host parsing, source blocklist, request pre-filter.

### 3. Media Discovery (`BrowserController` → `DownloadCoordinator`, `HlsResolver`, `MediaProbe`)
* Each visible page gets a **session**; media reported for an old page, a background tab or the page just left is dropped.
* **Sources:** URL-sniffed sub-resources; the injected `<video>`/`<audio>` script (`FetchMedia` bridge); `Range: bytes=0-` requests confirmed by `MediaProbe`; explicit downloads from `DownloadListener` or the long-press menu.
* **Headers** replay what the page sent (real User-Agent, Referer, Origin) plus cookies from `CookieManager`.
* **Resolvers → `ResolvedStream`:** `HlsResolver` (master + media playlists: audio renditions, fMP4 init, byte-range single files) and `DashManifest`/`DashResolver` (templates, timelines, lists, single files, `ContentProtection`, live) turn a manifest into one candidate with a quality per resolution. `AudioPolicy` pairs each quality with an audio track (main role, default/device language, container-compatible codec, bitrate); `Codecs` knows which pairs `MediaMuxer` can combine. Resolvers also return the paths and directories the manifest owns.
* **Grouping (`MediaGrouping`, `DownloadCoordinator.detectQuality`)**: sniffed files that a manifest claims are dropped; byte-range (MSE) slices, quality-named files and a `<video>`'s alternative sources are grouped as qualities of one video (with separately fetched audio paired when a player streams it).
* **Page info:** the page's `og:title` (cleaned by `TitleNormalizer`), `og:image` or poster name and illustrate sniffed candidates.

### 4. Download Coordinator (`DownloadCoordinator.kt`)
* Single source of truth for downloads and candidates (`StateFlow`), guarded by `stateLock`.
* Persists every item (including request headers, validator and HLS checkpoint) to JSON; phase changes persist immediately, progress is debounced.
* On start, running items become `PAUSED` ("Download interrupted. Tap retry to resume.") and legacy `cacheDir` parts are migrated.

### 5. Transfer Engine (`DownloadService.kt`)
* **Foreground-service contract:** every `onStartCommand` calls `startForeground()` first, since every command arrives via `startForegroundService()`. The service stops itself with `stopSelfResult()` once nothing is queued or running. `onTimeout` (Android 15 `dataSync` limit) pauses cleanly.
* **Queue:** a `Semaphore` sized from settings; waiting items are `QUEUED`.
* **Direct:** `Range: bytes=N-` + `If-Range`; a 200 reply means the file changed, so the download restarts; 416 at full length is treated as complete.
* **Tracks (HLS, DASH, direct + separate audio):** the manifest is re-fetched on every (re)start and resolved into tracks — a single file (range-resumed) or init + segments. Segments are appended in order and checkpointed (`trackIndex`, `segmentIndex`, `segmentOffset`), so a retry keeps finished tracks and truncates the current one to its last checkpoint.
* **Merge / convert (`MediaRemuxer`):** tracks are combined with `MediaExtractor` + `MediaMuxer` into MP4 or WebM without re-encoding (samples interleaved by timestamp; TS-sourced AAC is split from ADTS runs into raw frames). If merging fails, the untouched video is kept and the item notes it was saved without audio.
* **Free space:** checked against download + merge + shared-storage copy before a transfer starts.
* **Retries:** up to 3 with exponential backoff for I/O errors and 5xx/408/429.
* **Publishing:** `MediaFiles` resolves the real MIME type (declared → `Content-Type` → extension) and file name; the file is inserted as pending into the matching MediaStore collection (falling back to Downloads), copied, then published.

### 6. Library & Player
* **`MediaActions`**: open (player for audio/video, chooser otherwise), share, delete, thumbnails.
* **`PlayerActivity`**: Media3 ExoPlayer with position saved across recreation; unplayable formats are handed to another app.

---

## 🧵 Threading & Concurrency Model

| Component | Execution Thread | Concurrency Primitive |
|---|---|---|
| **Compose UI, `BrowserController`, WebView clients** | Main thread | Compose snapshot state |
| **`shouldInterceptRequest()`, JS bridge** | WebView worker / binder threads | `AtomicReference` / `@Volatile` tab fields only |
| **HLS parsing, probes** | `Dispatchers.IO`, cancelled per page session | `SupervisorJob` per session |
| **Transfers** | `Dispatchers.IO` | `Semaphore` (1–5 slots), `ConcurrentHashMap` of jobs |
| **State & persistence** | Any → `Dispatchers.IO` (debounced) | `synchronized(stateLock)`, `StateFlow` |

---

## 🛡️ Crash Prevention & Fault Tolerance

1. **Foreground-service timing:** `startForeground()` runs synchronously at the top of every `onStartCommand`.
2. **Renderer crash isolation:** `onRenderProcessGone` replaces only the affected tab's WebView.
3. **Worker-thread safety:** interception and bridge callbacks never read Compose state or WebView properties.
4. **No stale resurrection:** worker progress updates apply only while an item is still running, so pause/cancel always win.
5. **Storage safety:** partial files live in `noBackupFilesDir`, never purged by the system; a failed publish deletes its pending MediaStore row.
6. **Clipboard guard:** only read on window focus, with null/empty checks.
