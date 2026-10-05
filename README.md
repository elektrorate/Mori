<p align="center">
  <img src="assets/host-ia-logo.svg" width="128" alt="Host-ia Logo">
</p>

<h1 align="center">Host-ia v4.4.1</h1>
<p align="center"><em><strong>Save anything, From anywhere.</strong></em></p>

**Host-ia is an independently branded derivative of [coflyn/Mori](https://github.com/coflyn/Mori), maintained in [elektrorate/Mori](https://github.com/elektrorate/Mori).** It is not the original Mori project and does not claim affiliation with its author. Original authorship, copyright, and GPL notices remain intact.

The logo above is an [editable vector reconstruction](assets/host-ia-logo.svg) of the user-provided Host-ia logo, not the exact original attachment.

<p align="center">
  <img src="https://img.shields.io/badge/Version-v4.4.1-brown?style=flat-square" alt="Host-ia Version">
  <img src="https://img.shields.io/github/downloads/coflyn/Mori/total?style=flat-square&color=blue" alt="Upstream Mori Downloads">
  <img src="https://img.shields.io/github/stars/coflyn/Mori?style=flat-square&color=gold" alt="Upstream Mori Stars">
  <img src="https://img.shields.io/github/repo-size/coflyn/Mori?style=flat-square&color=purple" alt="Upstream Mori Repo Size">
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Windows-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform">
</p>

<div align="center">

Save and download videos, photos, and music from 16 platforms. Local downloads do not require a Google account. Optional Google Drive storage on Windows and Android requires Google authorization and is off by default. In Drive mode, local temporary files are deleted only after the upload is verified; failed uploads remain local for manual retry.

The GitHub statistics above describe upstream Mori, not Host-ia. The donation link below supports the original author, coflyn.

<a href="https://sociabuzz.com/coflyn/tribe" target="_blank">
  <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="60" />
</a>

</div>

## 📸 Screenshots

These are **historical upstream Mori examples**, retained for reference. They are not screenshots of Host-ia v4.4.1 and do not show the current branding or optional Drive interface.

<p align="center">
  <img src="assets/1.png" width="30%" alt="Historical upstream Mori example 1">
  <img src="assets/2.png" width="30%" alt="Historical upstream Mori example 2">
  <img src="assets/3.png" width="30%" alt="Historical upstream Mori example 3">
</p>
<p align="center">
  <img src="assets/4.png" width="30%" alt="Historical upstream Mori example 4">
  <img src="assets/5.png" width="30%" alt="Historical upstream Mori example 5">
  <img src="assets/6.png" width="30%" alt="Historical upstream Mori example 6">
</p>

---

## 📋 Table of Contents

1. [How to Use](#-how-to-use)
2. [Download & Installation](#-download--installation)
3. [Features](#-features)
4. [Supported Platforms & Scraper Engines](#-supported-platforms--scraper-engines)
5. [For Developers & Building from Source](#-for-developers--building-from-source)
6. [Scraper Architecture](#-scraper-architecture)

7. [Disclaimer](#-disclaimer)
8. [License & Terms of Use](#-license--terms-of-use)

---

## 🚀 How to Use

Saving media with Host-ia takes only three simple steps:

1. **Copy Link**: Copy any video, photo, or music link from your favorite app (TikTok, Instagram, YouTube, Spotify, etc.).
2. **Open Host-ia**: Host-ia automatically detects the link from your clipboard and analyzes it right away. _(On Android, you can also tap **Share** on a post and choose **Host-ia** to download without leaving the app!)_
3. **Download**: Pick your preferred quality (HD video without watermarks, audio MP3, or photo gallery) and tap **Download**. By default, media is saved to your device. If you enable **Save only in Drive**, it is uploaded to your selected Google Drive folder and the local temporary file is deleted only after verification.

## 📥 Download & Installation

Host-ia binaries belong on the fork's **[GitHub Releases](https://github.com/elektrorate/Mori/releases)** page. Version 4.4.1 is the source/configuration target; this documentation does **not** confirm that its builds have completed or that packages are published. Use the actual assets of a completed Host-ia release, or follow [CONTRIBUTING.md](CONTRIBUTING.md) to build locally. [Upstream Mori releases](https://github.com/coflyn/Mori/releases) are original Mori binaries, not Host-ia updates.

Expected release naming for v4.4.1 (not an availability guarantee):

| Platform                                                                                                                        | Expected Packages                                                 | Installation Guide                                                                  |
| :------------------------------------------------------------------------------------------------------------------------------ | :---------------------------------------------------------------- | :---------------------------------------------------------------------------------- |
| <img src="https://cdn.simpleicons.org/android/3DDC84" width="16" /> **Android** | `Host-ia v4.4.1.apk` | [Installation & Play Protect Guide](GUIDE.md#-android-installation--troubleshooting) |
| <img src="https://cdn.simpleicons.org/apple/000000" width="16" /> **macOS** | `Host-ia-v4.4.1-macOS-arm64.dmg`<br>`Host-ia-v4.4.1-macOS-arm64.app.tar.gz` | [Gatekeeper Quarantine Fix](GUIDE.md#-macos-installation--gatekeeper-fix) |
| <img src="https://cdn.jsdelivr.net/gh/devicons/devicon@latest/icons/windows11/windows11-original.svg" width="16" /> **Windows** | `Host-ia-v4.4.1-Windows-x64-Setup.exe`<br>`Host-ia-v4.4.1-Windows-x64.msi` | [Windows Setup Guide](GUIDE.md#-windows-installation) |
| <img src="https://cdn.simpleicons.org/apple/000000" width="16" /> **iOS** | `Host-ia-v4.4.1-iOS-Unsigned.ipa` (workflow)<br>`Host-ia v4.4.1.ipa` (local script) | [AltStore / TrollStore Sideloading](GUIDE.md#-ios-sideloading-guide) |

Local Tauri builds use configuration-generated names such as `Host-ia_4.4.1_x64-setup.exe` and `Host-ia_4.4.1_x64_en-US.msi`; the desktop release workflow renames them as shown above. Existing application data and media folders still use legacy `Mori` names.

> 📖 **Need help installing or troubleshooting?**  
> Read the complete **[Installation, Sideloading & User Guide (GUIDE.md)](GUIDE.md)**.

## 📜 Features

- **16 Platforms in One App**: Save watermark-free videos, high-resolution photos, and audio from TikTok, Instagram, YouTube, Twitter/X, Spotify, Apple Music, Pinterest, Facebook, Threads, Bandcamp, Pixiv, Bilibili, Douyin, RedNote, Reddit, and TeraBox.
- **Over-The-Air (OTA) Scraper Hot-Patching**: Host-ia retains the upstream `coflyn/Mori` scraper update service, separate from the fork's binary release channel.
- **Custom Directory & Storage Freedom**: Pick any folder across your device storage (including SD cards, Movies, Downloads, and custom folders) with native folder pickers and full file management support.
- **One-Tap Playlists & Albums**: Download full music albums or playlists from **Spotify**, **Apple Music**, and **YouTube** in one click instead of saving songs one by one.
- **High-Speed Concurrent Downloads**: Download music albums, playlists, and multi-photo galleries in parallel with up to 5 concurrent worker threads for blazing fast speeds.
- **Floating Download Bubble & Manager**: Non-intrusive monochrome floating bubble tracking active tasks in real-time with an aggregated progress ring, expandable dropup tray, individual task progress bars, and instant cancellation.
- **Quick Save via Android Share Menu**: Spot a video you love? Tap **Share** in any app and select Host-ia to download it in an overlay without switching apps.
- **Multi-Link Batch Mode**: Paste several links at once and let Host-ia queue and download them all automatically in the background.
- **Built-in Custom Fullscreen Player & Preview**: Play videos, stream tracks, and browse photo carousels right inside the app with a sleek custom fullscreen player, real-time title bar, smooth seek scrubbing, double-tap seek, and mute controls.
- **Instant Photo-to-PDF**: Combine photo galleries or multi-image posts into a single, clean PDF file for offline reading or sharing.
- **PIN & Biometric Lock**: Protect your download history with an optional 4-digit PIN code or fingerprint / Face ID lock.
- **Clean Folder Organization**: Local files retain the existing runtime folder names (`Movies/Mori`, `Music/Mori`, `Pictures/Mori`, or `Download/Mori`, depending on platform/settings). These paths are not renamed with the visible branding.
- **Automatic Clipboard Detection**: Opening Host-ia automatically suggests your copied link for instant one-tap downloading.
- **Background Downloads**: Large videos and playlists keep downloading seamlessly even when you minimize the app or turn off the screen.
- **Anti-Corrupt File Protection**: Files are saved securely—media only appears in your gallery once 100% complete, preventing broken or unplayable files.
- **Modern Themes & Live Backgrounds**: Choose from elegant dark modes and interactive animated backgrounds (Stars, Waves, Fireflies).
- **9 Languages**: English, Indonesian, Japanese, Korean, Simplified Chinese, Arabic (with RTL support), Russian, Tagalog, and Hindi.
- **Optional Google Drive Storage**: Off by default, available on Windows and Android with native Drive support. Uploads go to your chosen folder; local temporary files are removed only after native verification. Failed transfers and pending cleanup can be retried in Settings. See [the user guide](GUIDE.md#optional-google-drive-storage) and [native setup details](GOOGLE_DRIVE.md).
- **Ad-Free & No Analytics**: The app runs on your device, but media requests use source platforms and third-party scraper providers. Optional Drive storage sends media to your authorized Google account; do not treat it as local-only storage.

## 🌐 Supported Platforms & Scraper Engines

| Platform                                                                               | Supported Domains / Formats                                    | Features                     | Scraper Engine / Provider                                                               |
| :------------------------------------------------------------------------------------- | :------------------------------------------------------------- | :--------------------------- | :-------------------------------------------------------------------------------------- |
| <img src="https://cdn.simpleicons.org/instagram/E4405F" width="16" /> **Instagram**    | `instagram.com` (`/p/`, `/reel/`, `/stories/`)                 | Reels / Stories / Photos     | **InDown** (`indown.net`) & **SnapSave** (`snapsave.app`)                               |
| <img src="https://cdn.simpleicons.org/tiktok/000000" width="16" /> **TikTok**          | `tiktok.com`, `vt.tiktok.com`                                  | Video (No WM) / Slide Photos | **TikDownloader** (`tikdownloader.io`) & **TikTokIO** (`tiktokio.com`)                  |
| <img src="https://cdn.simpleicons.org/youtube/FF0000" width="16" /> **YouTube**        | `youtube.com`, `youtu.be`, `music.youtube.com`                 | Playlist / Album / MP4 / MP3 | **Ytmp3.gg** (`media.ytmp3.gg`) & **Ytmp3.mobi** (`ytmp3.mobi`)                         |
| <img src="https://cdn.simpleicons.org/x/000000" width="16" /> **Twitter (X)**          | `twitter.com`, `x.com`                                         | HD Video / GIFs              | **TwitterVideoDownloader** (`twittervideodownloader.com`) & **SaveTWT** (`savetwt.com`) |
| <img src="https://cdn.simpleicons.org/spotify/1DB954" width="16" /> **Spotify**        | `open.spotify.com` (`track`, `album`, `playlist`, `/s/`)       | Playlist / Album / MP3       | **SpotiDown** (`spotidown.app`) & **SoundLoaders** (`spotimate.app`)                    |
| <img src="https://cdn.simpleicons.org/applemusic/FA243C" width="16" /> **Apple Music** | `music.apple.com`                                              | Album / Playlist / MP3 Track | **AplMate** (`aplmate.com`)                                                             |
| <img src="https://cdn.simpleicons.org/pinterest/E60023" width="16" /> **Pinterest**    | `pinterest.com`, `pin.it`                                      | Video / HD Images            | Direct `pinimg.com` Parser & **PinDown** (`pindown.io`)                                 |
| <img src="https://cdn.simpleicons.org/facebook/1877F2" width="16" /> **Facebook**      | `facebook.com`, `fb.watch`                                     | Reels / HD Video             | **SnapSave** (`snapsave.app`)                                                           |
| <img src="https://cdn.simpleicons.org/xiaohongshu/FF2442" width="16" /> **RedNote**    | `xiaohongshu.com`, `xhslink.com`, `xhslink.cn`, `rednote.com`  | HD Photos / Videos           | Direct `__INITIAL_STATE__` SSR Extractor                                                |
| <img src="https://cdn.simpleicons.org/threads/000000" width="16" /> **Threads**        | `threads.com`                                                  | Video / Photo Carousel       | **Threadster** (`threadster.app`)                                                       |
| <img src="https://cdn.simpleicons.org/bilibili/00A1D6" width="16" /> **Bilibili**      | `bilibili.com`, `b23.tv`, `bili.im`, `bilibili.tv`             | Video / Audio (DASH 1080p)   | Direct Bilibili Web API (`api.bilibili.com` & `api.bilibili.tv`) & Wbi Resolver         |
| <img src="https://cdn.simpleicons.org/pixiv/0096FA" width="16" /> **Pixiv**            | `pixiv.net` (`artworks`)                                       | Gallery / Ugoira to MP4      | Direct Pixiv AJAX API & Ugoira Zip-to-MP4 Converter                                     |
| <img src="https://cdn.simpleicons.org/tiktok/000000" width="16" /> **Douyin**          | `douyin.com`, `v.douyin.com`                                   | Video (No WM) / Photos       | Direct `iesdouyin.com` API & Multi-Marker SSR Resolver                                  |
| <img src="https://cdn.simpleicons.org/bandcamp/1DA1F2" width="16" /> **Bandcamp**      | `*.bandcamp.com`                                               | Track / Album / MP3          | **BandcampDownloader** (`bandcampdownloader.app`)                                       |
| <img src="https://cdn.simpleicons.org/reddit/FF4500" width="16" /> **Reddit**          | `reddit.com`, `redd.it`                                        | HD Video (Audio) / Photos    | **RapidSave** (`rapidsave.com`)                                                         |
| <img src="https://cdn.simpleicons.org/box/0061D5" width="16" /> **TeraBox**            | `terabox.com`, `teraboxapp.com`, `1024tera.com`, `teraboxlink` | Direct File / Stream (m3u8)  | **Sechno** (`sechno.com`)                                                               |

## 🛠️ For Developers & Building from Source

Interested in customizing the interface, contributing translations, or compiling binaries locally?

👉 **[Contributing & Build Guide (CONTRIBUTING.md)](CONTRIBUTING.md)** — Complete prerequisites, environment setup, and compilation instructions for Android, iOS, macOS, and Windows.  
📜 **[Release History & Changelog (CHANGELOG.md)](CHANGELOG.md)** — Preserved historical Mori release logs; not proof of a completed Host-ia v4.4.1 build.

<details>
<summary><b>🔍 Click to view Tech Stack & Project Structure</b></summary>

### Built With

- **JavaScript (ES6+)**: Core application logic and client-side UI.
- **HTML5 & CSS3**: Custom responsive design system with dark mode and smooth animations.
- **Tauri v2 (Rust)**: Ultra-lightweight desktop engine for macOS & Windows (`.dmg`, `.app`, `.msi`, `.exe`).
- **CapacitorJS**: Native Android and iOS bridge for filesystem, share sheet, clipboard, and biometrics.
- **OkHttp (Native Android)**: High-performance native HTTP engine to seamlessly bypass WebView CORS and network restrictions.
- **Cheerio & Axios**: Fast DOM HTML parsing and HTTP client request handling.
- **pdf-lib**: Client-side PDF generation and bundling.

### Project Structure

```
Mori/
├── android/                    # Capacitor Android native project
│   ├── app/src/main/
│   │   ├── java/com/mori/downloader/
│   │   │   ├── DownloadForegroundService.java # Background download persistent service & wake-lock
│   │   │   ├── MainActivity.java   # Main Activity + native HTTP bridge (CORS bypass)
│   │   │   └── ShareActivity.java  # Native Quick Save Share overlay & MediaStore indexer
│   │   └── res/                # Android layout, drawables, XML configs & splash screens
│   └── gradle/                 # Gradle build scripts & configurations
├── ios/                        # Capacitor iOS Xcode workspace
│   └── App/                    # iOS Xcode project, Info.plist, and CocoaPods
├── src-tauri/                  # Tauri v2 Desktop Rust backend (macOS & Windows)
│   ├── capabilities/           # Application permissions & security capabilities
│   ├── src/                    # Rust native HTTP & local filesystem provider
│   └── tauri.conf.json         # Desktop app configuration & window bounds
├── assets/                     # App icons, mockups, & screenshots
├── public/                     # Frontend web assets (Vanilla JS + CSS)
│   ├── css/                    # Modular CSS architecture
│   │   ├── variables.css       # Design tokens, themes (dark/light), typography, glass, corner presets
│   │   ├── base.css            # CSS reset, typography, header, dynamic greeting, bottom navigation
│   │   ├── components.css      # Reusable buttons, custom toast, floating download bubble & dropup manager
│   │   ├── home.css            # URL input bar, batch textarea, skeleton loader, media preview cards, custom fullscreen
│   │   ├── history.css         # History layout, summary stats card, cards, actions bar, thumbnail overlay
│   │   ├── settings.css        # Settings menu list, sub-page slide transitions, custom dropdowns
│   │   ├── modals.css          # Modal overlays, PIN keypad, user guide, confirm & info dialogs
│   │   ├── rtl.css             # Right-to-Left (RTL) language overrides for Arabic [dir="rtl"]
│   │   └── style.css           # Master stylesheet entry point with sequential @import rules
│   ├── js/
│   │   ├── app.js              # Main application entry point & startup lifecycle
│   │   ├── components/         # Reusable UI components
│   │   │   └── player.js       # In-app media player (custom fullscreen overlay, gestures, smooth scrubbing)
│   │   ├── downloader/         # Modular native download engine
│   │   │   ├── filename.js     # Extension resolution, title sanitization, template & folder logic
│   │   │   ├── headers.js      # Platform-specific Referer/Origin headers builder & URL unwrapper
│   │   │   ├── resolver.js     # Asynchronous link resolver (YouTube, Spotify, Apple Music, workers)
│   │   │   ├── storage.js      # Desktop/Mobile filesystem persistence, retry loops, temp cleanup
│   │   │   ├── postProcess.js  # MediaScanner, feedback haptics/audio, tray notifications, UI reset
│   │   │   └── index.js        # Barrel re-export for downloader sub-modules
│   │   ├── i18n/               # Multi-language translations (9 languages + RTL support)
│   │   │   ├── locales/        # Modular locale dictionaries (en, id, ja, ko, zh, ar, ru, tl, hi)
│   │   │   └── index.js        # Translation registry, t() helper, and fallback resolver
│   │   ├── modules/            # Core business logic & application state
│   │   │   ├── authManager.js  # PIN passcode & biometric lock system
│   │   │   ├── batchManager.js # Multi-link batch queue & playlist manager
│   │   │   ├── bgAnimation.js  # Interactive Live Canvas backgrounds (Stars, Waves, etc.)
│   │   │   ├── core.js         # Shared global state, DOM references, constants
│   │   │   ├── download.js     # Analysis pipeline & download controllers
│   │   │   ├── history.js      # Download history manager, local storage, & cleanup
│   │   │   ├── intents.js      # Auto-clipboard detection & deep link receiver
│   │   │   ├── modals.js       # Confirmation dialogs & information modals
│   │   │   ├── settings/       # Modular user settings & configuration subsystem
│   │   │   │   ├── nativeSync.js # Native SharedPreferences bridge & platform detection
│   │   │   │   ├── appearance.js # Themes (dark/light), color accents, fonts, & visual presets
│   │   │   │   ├── behavior.js   # Toggles (incognito, data saver, Wi-Fi only, keep awake, anti-403)
│   │   │   │   ├── storage.js    # Download subfolder pickers, cache cleanup, wipe data & stats
│   │   │   │   ├── language.js   # Dropdown select engine, i18n switcher, sub-page navigation
│   │   │   │   └── index.js      # Barrel re-export for settings sub-modules
│   │   │   ├── settings.js     # User preferences orchestrator & backward-compatible facade
│   │   │   ├── update.js       # Automatic GitHub release update checker
│   │   │   └── updateScrapers.js # OTA scraper hot-patch downloader & validator
│   │   ├── scrapers/           # Scraper runtime loader & HTTP helper
│   │   │   ├── bundle.js       # Bundled scraper core (esbuild IIFE, all 16 platforms)
│   │   │   ├── httpHelper.js   # Unified HTTP engine (native OkHttp/Tauri bridge + UA rotation)
│   │   │   └── index.js        # Runtime loader with OTA hot-patch support
│   │   ├── ui/                 # UI rendering & presentation layer
│   │   │   ├── downloadBubble.js # Persistent floating download bubble & dropup task manager
│   │   │   ├── nativeDownload.js # Native download flow orchestrator & progress tracking
│   │   │   ├── result.js       # Analysis results view, media slider, & PDF creator
│   │   │   └── resultModal.js  # Detailed preview modal & folder path navigator
│   │   ├── share.js            # Android Quick Save Share Overlay controller
│   │   ├── ui.js               # History rendering & gesture handlers (long-press delete)
│   │   ├── utils/              # Helper utilities subsystem
│   │   │   ├── plugins.js      # Capacitor native plugin registry & auto-sync lifecycle
│   │   │   ├── http.js         # User-Agent presets, cookie parser, query serializer, error handler
│   │   │   ├── device.js       # Haptic feedback triggers, clipboard writer, wake lock, Wi-Fi guard
│   │   │   ├── toast.js        # Standard notification toasts & action feedback alerts
│   │   │   ├── sound.js        # Web Audio API procedural synthesizer & sound pack generator
│   │   │   ├── media.js        # Video canvas thumbnail generator & playback safe controls
│   │   │   ├── pdfHelper.js    # PDF generation & image bundling via pdf-lib
│   │   │   ├── urlUtils.js     # URL sanitization & tracking parameter remover
│   │   │   └── index.js        # Barrel re-exporter providing 100% backward compatibility
│   │   └── vendor/             # Bundled third-party libraries (pdf-lib)
│   │       └── pdf-lib.min.js
│   ├── index.html              # Main single-page application markup
│   └── share.html              # Standalone Android Quick Save Share Overlay markup
├── capacitor.config.json       # Capacitor cross-platform configuration
├── package.json                # Project dependencies & build scripts
├── CHANGELOG.md                # Detailed version-by-version release history
├── CONTRIBUTING.md             # Developer setup, build guidelines, & PR instructions
├── GUIDE.md                    # In-app user manual & usage walkthrough
├── LICENSE                     # GNU General Public License v3.0
└── README.md                   # Primary project overview & documentation
```

</details>

## 🔧 Scraper Architecture

Host-ia retains Mori's scraper core, bundled via esbuild into `public/js/scrapers/bundle.js`, a plain minified IIFE containing all 16 platform scrapers. The project directory remains `Mori/`, and technical names such as `com.mori.downloader`, `MoriDrive`, legacy preference keys, PIN derivation, native bridge/service names, and bundled binary names are unchanged.

- **OTA Hot-Patching**: Host-ia can download and apply an updated `bundle.js` from upstream `coflyn/Mori` without a full app update. Keep this service distinct from binary updates from `elektrorate/Mori`; installing an upstream Mori binary can replace the fork rather than update Host-ia.
- **Open Client Architecture**: The entire frontend, UI design system, and core app logic remain **100% open source under GPL-3.0**.
- **Collaborative Development**: Honest developers who want to improve scrapers or fix broken endpoints are always welcome to coordinate through [CONTRIBUTING.md](CONTRIBUTING.md).

## ⚖️ Disclaimer

- **Personal & Educational Use**: Host-ia is an open-source utility for personal media archiving and research, derived from Mori. Users are responsible for complying with local copyright laws and the terms of service of source platforms.
- **Storage & Network Use**: Host-ia does not operate a media-hosting service. It requests media through source platforms and third-party providers, saves locally by default, and uploads to the user's Google Drive only when that optional destination is enabled.
- **Respect for Third-Party Providers**: Host-ia retains Mori's client-side scraper architecture. Report endpoint concerns for this derivative through [fork issues](https://github.com/elektrorate/Mori/issues). For the original Mori project, use [upstream issues](https://github.com/coflyn/Mori/issues) or the original author's published contact, riazrepo@gmail.com; that contact is not presented as Host-ia support.

## 📄 License & Terms of Use

Host-ia is a derivative of Mori and remains free and open-source software under the **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)**. Rebranding does not transfer ownership of the original project or its copyrights. Retain original copyright, author, license, and source notices, including GPL comments, when modifying or distributing it.

- **Copyleft Enforcement**: When distributing modified versions or binaries, honor GPL-3.0 requirements for providing complete corresponding source code under the same license. Private modifications do not by themselves require public source publication.
- **Distribution Compliance**: Packaging or rebranding does not remove GPL obligations. Commercial distribution is allowed under the GPL when its requirements are honored; closed-source redistribution without the required corresponding source is not.
- **Trademark & Identity**: The name "Mori", app logo, and associated visual designs are the property of the original author. Derivative works must be clearly distinguished and must not claim affiliation with the original project.
- **Independent Branding**: Host-ia identifies this derivative only. It does not rename the original author's work, claim ownership of Mori, or imply the original author endorses this fork.

---

Original Mori author credits (preserved):

Developed with ❤️ by coflyn.  
GitHub: https://github.com/coflyn  
Instagram: @\_coflyn
