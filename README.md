# Fetch

Fetch is a deliberately small Android media utility. It lets a user browse or paste an HTTPS URL, recognizes ordinary direct audio/video resources, and downloads them to the device media library.

## What the first version includes

- Kotlin + Jetpack Compose UI with Browse, Downloads, and Settings surfaces
- An actual `WebView` browser, not a screen which pretends to be one
- Direct-resource candidate detection from browser requests and browser download events
- Candidate deduplication by normalized URL and MIME type
- Browser-session handoff for direct downloads (user agent, referrer, and cookies)
- HLS master-playlist resolution with deliberate per-quality choices for unencrypted MPEG-TS streams
- A foreground download service with useful progress notification and pause control
- HTTP range request resume when the server permits it
- Persistent download metadata; interrupted direct transfers return as an explicit paused item after relaunch
- A three-transfer concurrency ceiling and throttled progress/notification refreshes
- `.part` files during transfer; MediaStore publication only after the file has completed
- Clear failure text for HTTP and connection errors
- No DRM circumvention: protected streams are explicitly out of scope

## Intentional limits of this initial build

This is a safe direct-media foundation, not a claim to support every streaming site. The app supports direct sources and unencrypted MPEG-TS HLS variants. DASH, encrypted HLS, and fMP4 HLS need dedicated media-pipeline support and are intentionally rejected. A Room/WorkManager migration, adaptive server-specific retry policy, and a private vault remain separate engineering milestones.

## Open in Android Studio

Open this folder in a current Android Studio build. Android Studio will resolve the declared Android Gradle Plugin and dependencies, then run the `app` configuration. The app requires Android 10 (API 29) or later because it uses scoped MediaStore writes and pending-file finalization.

## Product constraints

Use Fetch only for content you are authorized to save. The application downloads direct, non-DRM resources; it neither bypasses protected streams nor attempts to extract media from protected playback.
