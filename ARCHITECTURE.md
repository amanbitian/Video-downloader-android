# Architecture Documentation: Fetch

This document provides a comprehensive technical overview of the architecture, data flows, threading model, and lifecycle management in **Fetch**.

---

## 🏛️ System Architecture Overview

Fetch is designed around a clean separation of concerns between the **Browser UI**, the **Media Discovery & Resolution Pipeline**, the **Download Coordinator**, and the **Background Transfer Engine**.

```
                         ┌────────────────────────────────┐
                         │      Jetpack Compose UI        │
                         │ BrowserScreen + BottomSheet FAB │
                         └───────────────┬────────────────┘
                                         │
                                         ▼
                         ┌────────────────────────────────┐
                         │         BrowserView            │
                         │    WebView + WebViewClient     │
                         └───────────────┬────────────────┘
                                         │
                         [Network Request Interception]
                                         │
                                         ▼
                         ┌────────────────────────────────┐
                         │       Media Pre-Filter         │
                         │   isCandidateUrl() / .svg filter│
                         └───────────────┬────────────────┘
                                         │
                                         ▼
                         ┌────────────────────────────────┐
                         │       Media Discovery          │
                         │   DownloadCoordinator.detect() │
                         └───────────────┬────────────────┘
                                         │
             ┌───────────────────────────┴───────────────────────────┐
             │                                                       │
             ▼                                                       ▼
    ┌─────────────────┐                                     ┌─────────────────┐
    │   HlsResolver   │                                     │  Direct Stream  │
    │  (Master M3U8)  │                                     │   (MP4 / WebM)  │
    └────────┬────────┘                                     └────────┬────────┘
             │                                                       │
             └───────────────────────────┬───────────────────────────┘
                                         │
                                         ▼
                         ┌────────────────────────────────┐
                         │      DownloadCoordinator       │
                         │  StateFlow<List<DownloadItem>> │
                         └───────────────┬────────────────┘
                                         │
                         [Foreground Service Intent]
                                         │
                                         ▼
                         ┌────────────────────────────────┐
                         │        DownloadService         │
                         │    Foreground Service (FGS)    │
                         └───────────────┬────────────────┘
                                         │
             ┌───────────────────────────┴───────────────────────────┐
             │                                                       │
             ▼                                                       ▼
    ┌─────────────────┐                                     ┌─────────────────┐
    │ Direct Downloader│                                    │  Hls Downloader │
    │ (Range Resumable│                                    │ (Segment Order) │
    └────────┬────────┘                                     └────────┬────────┘
             │                                                       │
             └───────────────────────────┬───────────────────────────┘
                                         │
                         [Cache .part file buffering]
                                         │
                                         ▼
                         ┌────────────────────────────────┐
                         │      MediaStore Publisher      │
                         │    Movies/Fetch & Music/Fetch  │
                         └────────────────────────────────┘
```

---

## 🔍 Detailed Component Breakdown

### 1. Browser & UI Layer (`MainActivity.kt`)
* **`FetchApp`**: Root Composable managing top-level navigation tabs (`Browse`, `Downloads`, `Settings`), active candidate observation, and the quality picker bottom sheet (`DownloadBottomSheet`).
* **`BrowserScreen`**: Manages multiple browser tabs (`TabItem`), active tab navigation, tab switcher dialog (`TabSwitcherDialog`), address bar, and the native floating action button (`FloatingActionButton` with candidate badge count).
* **`BrowserView`**: Wraps native Android `WebView` via `AndroidView`. Implements:
  * **WebView Generation Keying (`webViewGeneration` + `key(...)`)**: Guarantees that upon renderer death (`onRenderProcessGone`), the dead instance is discarded and a pristine WebView is recreated.
  * **Reload Loop Protection**: Uses `lastRequestedUrl` (`AtomicReference`) to prevent server redirect feedback loops from repeatedly calling `loadUrl()`.
  * **State Preservation**: Saves and restores WebView bundle state (`saveState` / `restoreState`) across tab switches and memory recreation, while ensuring dead WebViews never execute `saveState()`.

### 2. Media Discovery & Pre-Filtering Pipeline (`DownloadCoordinator.kt` & `HlsResolver.kt`)
* **Thread-Safe Interception**: `shouldInterceptRequest()` operates on worker threads and avoids accessing UI-thread properties (`view?.url`).
* **Pre-Filtering (`isCandidateUrl`)**: Instantly drops non-media requests (`.svg`, `.png`, `.jpg`, `.webp`, `.ico`, `.css`, `.js`, `.ts`, `.m4s`, analytics, favicons) before evaluation.
* **Candidate StateFlow**: Emits detected `MediaCandidate` objects thread-safely via `synchronized(stateLock)` and `MutableStateFlow`.
* **HlsResolver**: Fetches HLS master manifests (`.m3u8`), verifies encryption status (`#EXT-X-KEY`), checks for fMP4 compatibility, and parses quality variants (`1080p`, `720p`, etc.) into absolute URLs.

### 3. Download Coordinator & State Machine (`DownloadCoordinator.kt`)
* **State Persistence**: Serializes download items to JSON and persists snapshots debounced via `SharedPreferences`.
* **Interrupted State Recovery**: Automatically transitions active/queued transfers into `PAUSED` state on initialization (`initialize()`), displaying *"Download interrupted. Tap retry to resume."* rather than hiding unfinished work.

### 4. Background Transfer Engine (`DownloadService.kt`)
* **Foreground Service Safety**: Guarantees immediate `startForeground()` invocation synchronously upon service start to comply with Android 12+ FGS start requirements.
* **Range-Based Resumable Downloads**: Checks for existing `.part` files in `cacheDir`, sets HTTP `Range: bytes=X-` headers, and appends incoming stream chunks.
* **Throttled Progress & Notifications**: Throttles progress calculations, UI updates, and foreground notification refreshes to `1,000ms` (1 second), avoiding main-thread starvation and ANRs.
* **MediaStore Finalization**: Inserts completed media into `MediaStore.Video.Media` or `MediaStore.Audio.Media` using pending-file semantics (`IS_PENDING = 1`), writes the file stream, and marks it active (`IS_PENDING = 0`).

---

## 🧵 Threading & Concurrency Model

| Component | Execution Thread | Concurrency Primitive |
|---|---|---|
| **WebView UI / Navigation** | Main (UI) Thread | Jetpack Compose Recomposition & StateFlow |
| **`shouldInterceptRequest()`** | Worker Thread Pool | Thread-safe `AtomicReference` & SharedFlow |
| **Media Resolution / Parsing** | `Dispatchers.IO` | Coroutines (`withContext(Dispatchers.IO)`) |
| **Download Transfers** | `Dispatchers.IO` | `Semaphore` (Max 3 concurrent transfers) |
| **State Updates / Persistence** | `Dispatchers.IO` (Debounced) | `synchronized(stateLock)` & `SharedPreferences` |

---

## 🛡️ Crash Prevention & Fault Tolerance

1. **Renderer Crash Isolation**: `onRenderProcessGone` intercepts Chromium renderer crashes and safely recreates the WebView instance.
2. **Infinite Redirect Protection**: `lastRequestedUrl` comparison prevents `AndroidView.update` from re-triggering `loadUrl()` on server redirects.
3. **Clipboard Crash Guard**: Verifies `primaryClip != null && itemCount > 0` before reading clipboard contents.
4. **Header Null Safety**: Uses safe calls (`?.`) on `request.requestHeaders` to prevent `NullPointerException` on specific Android WebView sub-resource requests.
