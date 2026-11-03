# 📜 Dynamic Edge AI — Version History

---

## 🚀 Version 1.1.2 (Build 4) — 2026-11-03
**Status:** Staged Locally / Ready for Wireless Device Push & GitHub Release  
**Package:** `com.dynamicedgeai`  
**Target SDK:** 36 (Android 15+) | **Min SDK:** 26 (Android 8.0 Oreo)  
**Git Tag:** `v1.1.2`  
**Build Variant:** `release` & `debug` (compatible debug-signature for seamless in-place updates)  
**Release APK Filename:** `DynamicEdgeAI-v1.1.2-release.apk`  
**Release APK SHA-256:** `DD76FF7A71F974E9650BE3835852981A5A0F2C5DD8443F13369D79FC242D3F4F`  
**Debug APK Filename:** `DynamicEdgeAI-v1.1.2-debug.apk`  
**Debug APK SHA-256:** `7A224A53080A6F3B78A64B66B9D310FD94007E0231E0562E05E6DC9927A497AD`  
**Signing Certificate SHA-256:** `0940e42a8cbf0746bf7d48358eb0afbf43b77dcf0c4580069867370436893645` (CN=Android Debug)  
**Deployment Status:** Built locally, verified, and ready for deployment.

### 🌟 New Features & Enhancements
- **Model Manager Real-Time Download Speed & Resumption:**
  - Real-time rolling window download speed calculation (~1s update intervals; MB/s, KB/s, GB/s).
  - Dynamic ETA computation, total and downloaded byte counters, and progress bar.
  - Resilient resumable downloading (`DownloadResumeManager`) utilizing HTTP Range and `If-Range` headers with ETag and Last-Modified validators.
  - Full support for HTTP 206 Partial Content, HTTP 200 non-resumable fallback, and HTTP 416 Range Not Satisfiable.
  - Live network transition survival (`ConnectivityManager.NetworkCallback`) across Wi-Fi $\leftrightarrow$ Mobile Data without partial byte corruption.
  - "Paused" and "Waiting for network" states.
- **Multi-Conversation Management & Persistence:**
  - Full relational persistence via SQLite (`dynamicedgeai_chat.db`), `ChatDbHelper`, and `ConversationRepository`.
  - Independent chat threads with automated contextual titling, last-message preview, and timestamps.
  - Main header and quick-menu "New Chat" and "Conversations" buttons with bottom sheet dialog supporting switching, deletion, and renaming.
- **Memory & Context Budgeting System:**
  - Architectural separation between chat history turns, active sliding context window (`ContextBuilder`), and persistent memory (`MemoryManager`).
  - Automated heuristic fact extraction (user preferences, project details, instructions) with regex filtering against secrets and credentials.
  - User memory toggle switch and interactive inspection/deletion bottom sheet dialog.
  - Contextual memory injection seamlessly assembled into inference prompts within token budgets.
- **Message Actions: Copy & Edit:**
  - Dedicated copy button on all user and assistant chat messages.
  - Edit action on user messages with dialog prompt, automatic cascading deletion of downstream messages in SQLite, and immediate AI re-generation.
- **Multimodal Visual Analysis & Image Attachments:**
  - Image attachment picker (`btnAttachImage`) with thumbnail preview box and removal action.
  - Model capability registry (`ModelCapabilityRegistry`) managing multimodal compatibility per model.
  - Full multimodal payload routing in `MessageRouter`: Base64 `inline_data` for Gemini Cloud API and MediaPipe `MPImage` for local multimodal runners.
- **Gemma 4 E4B-it Compatibility Architecture Upgrade:**
  - Root cause resolved: upgraded MediaPipe GenAI stack from obsolete `0.10.14` (May 2024) to modern `0.10.35` (LiteRT-LM flatbuffer engine) and integrated `tasks-core:0.10.35`.
  - Decoupled `LocalModelRunner.kt` into model engine initialization (`LlmInference`) and per-turn session execution (`LlmInferenceSession`) with GPU backend preference and automated CPU fallback.
  - Added structured diagnostic dialog in `ModelManagerActivity` for corrupted or unsupported models.

---

## 🚀 Version 1.1.1 (Build 3) — 2026-11-03
**Status:** Staged Locally / Deployed to Test Device (Unpublished on GitHub)  
**Package:** `com.dynamicedgeai`  
**Target SDK:** 36 (Android 15+) | **Min SDK:** 26 (Android 8.0 Oreo)  
**Git Tag:** `v1.1.1`  
**Build Variant:** `release` (compatible debug-signature for seamless in-place updates)  
**APK Filename:** `DynamicEdgeAI-v1.1.1-release.apk`  
**APK SHA-256:** `73F3CC2BE3E6CC67D755875D66BF669CD9423B6A56A78B2E080E5CD1BE88915D`  
**Signing Certificate SHA-256:** `0940e42a8cbf0746bf7d48358eb0afbf43b77dcf0c4580069867370436893645` (CN=Android Debug)  
**Deployment Status:** Built locally, verified, and successfully installed & running on Vivo device (`V2561`). Not yet published to GitHub Releases.

### 🌟 New Features & Enhancements
- **GitHub Auto-Updater Core (`com.dynamicedgeai.updater`):**
  - Integrated GitHub Releases API querying (`https://api.github.com/repos/mpsinghji/DynamicEdgeAI/releases/latest` and `/releases`).
  - Strict version comparison logic: updates are offered ONLY when `remoteVersionCode > installedVersionCode`.
  - Stable release filtering: automatically ignores draft releases and prereleases.
  - Asset matching: matches assets following the `DynamicEdgeAI-vVERSION-release.apk` convention.
  - Background resumable streaming download with real-time download speed and progress reporting.
- **Pre-Install Security & Compatibility Verification (`ApkVerifier`):**
  - Package identity verification (ensures downloaded archive matches `com.dynamicedgeai`).
  - Version code verification (guarantees downloaded APK has `versionCode > installedVersionCode`).
  - Signing certificate continuity verification: inspects APK signing certificates and validates against installed app signatures to prevent `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
  - SHA-256 checksum verification when hashes are supplied in GitHub release notes.
- **Android PackageInstaller Integration:**
  - Secure `FileProvider` URI generation (`${applicationId}.fileprovider`).
  - Added `android.permission.REQUEST_INSTALL_PACKAGES` permission and Android 8.0+ `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` permission handoff with user consent.
- **Compact Update Card UI:**
  - Implemented Material CardView UI supporting 7 distinct states:
    1. *Idle / Check for Updates*
    2. *Checking for updates...*
    3. *Update available* (version badge, release title, size, release notes)
    4. *Downloading update...* (progress bar, percentage, real-time MB/s)
    5. *Download completed* (integrity verified, ready to install)
    6. *Installing update...* (PackageInstaller launch handoff)
    7. *You're up to date* / *Update failed* (retry & dismiss controls)
  - Integrated into the slide-up menu (`bottom_sheet_menu.xml`) with a direct "Updates" action button.
- **Single Source of Truth Versioning (`AppVersion`):**
  - Replaced hardcoded version strings across all screens (`MainActivity` header, slide-up quick menu, `ModelManagerActivity` toolbar) with dynamic values sourced directly from `BuildConfig`.
- **Gemma 4 E4B-it Integration:**
  - On-device catalog integration preserved. (Operational testing reserved for user).

---

## 📦 Version 1.1.0 (Build 2) — 2026-11-03
**Status:** Previous Release (Device Backup archived at `releases/DynamicEdgeAI-v1.1.0-device-backup.apk`)  
**Package:** `com.dynamicedgeai`  
**APK Filename:** `DynamicEdgeAI-v1.1.0-debug.apk`  
**APK SHA-256:** `D5B2864440EDAEF453C3474C1E8D9E068A55E7DA0E9E1F1813F5B4BAF9D2BB24`  

### 🌟 Release Capabilities
- **Google Gemma 4 E4B-it Model Support:**
  - Added model registry definition for `gemma-4-E4B-it.litertlm` (3,659,530,240 bytes).
  - Configured MediaPipe / LiteRT LLM inference runner pipeline and diagnostics.
  - Multi-threaded segmented downloading from Hugging Face CDN.
- **Initial Version Badging:**
  - In-app version labels added to main header, model manager, and bottom sheet.

---

## 📦 Version 1.0.0 (Build 1) — Baseline Release
**Status:** Baseline Installation (Device Backup archived at `releases/DynamicEdgeAI-v1.0-device-backup.apk`)  
**Package:** `com.dynamicedgeai`  
**APK Filename:** `DynamicEdgeAI-v1.0-device-backup.apk`  
**APK SHA-256:** `300C87ED9370D30530A0A8FFF722E890A37DD1902A459B3D425D0FBE2313B578`  

### 🌟 Initial Release Capabilities
- **Adaptive Decision Engine:** Dynamic routing between on-device local execution and Cloud execution (Gemini 1.5 Flash).
- **Device Telemetry:** Live monitoring of RAM, thermal state, CPU, battery, and network.
- **Privacy Mode:** User toggle forcing 100% on-device local processing.
- **Models:** Gemma 2B CPU int4, Qwen 2.5 3B, TinyLlama 1.1B.
