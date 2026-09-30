# Fetch: Modern Android Video Downloader & Browser

**Fetch** is a production-grade, highly optimized Android application that combines a full-featured WebView browser with an intelligent media detection engine and a robust, resumable download manager.

---

## 🚀 Core Features

### 1. Modern Target UX
* **Browse Normally:** Navigate any media site, social feed, or video platform.
* **Native Floating Action Button (FAB):** When downloadable media is discovered, a native Compose FAB appears with a live badge count (`↓ N`).
* **Quality Picker Bottom Sheet:** Tapping the FAB opens a `ModalBottomSheet` presenting clean, actionable download options (direct streams, HLS qualities, DASH streams).
* **Multi-Tab Support:** Open, switch between, and close multiple browser tabs with independent navigation states.
* **WebView State Preservation:** Seamlessly preserves browsing history, scroll position, and tab states across backgrounding and memory recreation using bundle serialization (`saveState` / `restoreState`).

### 2. Robust Media Discovery & Pre-Filtering Pipeline
* **Aggressive Pre-Filtering:** Instantly discards non-media traffic (`.svg`, `.png`, `.jpg`, `.webp`, `.ico`, `.css`, `.js`, `.ts`, `.m4s`) in `shouldInterceptRequest()` before any UI recomposition occurs.
* **Thread-Safe Interception:** Zero UI-thread property access (`view?.url`) on worker callback threads, ensuring absolute thread safety.
* **Multi-Stream Support:** Detects and resolves Direct media files, HLS (`.m3u8`) master playlists with quality variant parsing, and DASH packaged streams.

### 3. Resumable Download Engine (`DownloadService` & `DownloadCoordinator`)
* **Range-Based Resumable Transfers:** Downloads to temporary `.part` files supporting HTTP `Range` requests (`bytes=X-`), allowing seamless pause, resume, and recovery after app interruption or process death.
* **State Machine & Persistence:** Tracks transfer phases (`QUEUED`, `CONNECTING`, `DOWNLOADING`, `PAUSED`, `VERIFYING`, `COMPLETED`, `FAILED`, `CANCELLED`) and persists state to `SharedPreferences`.
* **MediaStore Publication:** Finalizes completed transfers directly into public media directories (`Movies/Fetch` or `Music/Fetch`) via `MediaStore` using pending-file semantics.

### 4. OS Lifecycle & Crash-Free Reliability
* **Renderer-Death Recovery:** Automatically detects WebView renderer crashes (`onRenderProcessGone`), discards the dead instance cleanly, and recreates a fresh WebView session (`webViewGeneration`).
* **Foreground Service Safety:** Synchronously invokes `startForeground()` immediately upon service start to prevent Android 12+ `ForegroundServiceDidNotStartInTimeException` crashes.
* **Throttled Updates:** Throttles download progress ticks and foreground notification intervals to `1,000ms` (1 second) to eliminate main-thread flooding and prevent UI ANRs/freezes.

---

## 🛠️ Tech Stack & Architecture
* **Language:** Kotlin 2.0+
* **UI Toolkit:** Jetpack Compose & Material 3
* **Concurrency:** Kotlin Coroutines & StateFlow
* **Networking:** OkHttp & `HttpURLConnection` with connection pooling and range headers
* **Storage:** Scoped Storage via `MediaStore` & `SharedPreferences` persistence

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
