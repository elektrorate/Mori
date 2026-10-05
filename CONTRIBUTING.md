# Contributing to Host-ia v4.4.1

Contributions to **Host-ia**, an independently branded derivative of [coflyn/Mori](https://github.com/coflyn/Mori), are welcome in [elektrorate/Mori](https://github.com/elektrorate/Mori). Whether you want to fix bugs, add UI features, expand language translations, or improve documentation, your help is welcome. The original Mori project and its author retain their attribution and property rights; this fork does not claim to be the original project.

---

## 📋 Table of Contents

1. [Code of Conduct & Etiquette](#-code-of-conduct--etiquette)
2. [How Can I Contribute?](#-how-can-i-contribute)
3. [Development & Build Guide](#-development--build-guide)
   * [Prerequisites](#prerequisites)
   * [Initial Setup](#initial-setup)
   * [Building for Android](#-building-for-android)
   * [Building for Desktop (macOS & Windows)](#-building-for-desktop-macos--windows)
   * [Building for iOS](#-building-for-ios)
4. [Architecture & Scraper Engine](#-architecture--scraper-engine)
5. [Contributing Translations (i18n)](#-contributing-translations-i18n)
6. [CSS & Styling Architecture](#-css--styling-architecture-publiccss)
7. [Submitting a Pull Request](#-submitting-a-pull-request)

---

## 🤝 Code of Conduct & Etiquette

* **Be Respectful**: Treat everyone in the community with courtesy and respect.
* **Keep it Clean**: Host-ia retains the free, ad-free, and tracker-free client. Do not introduce telemetry, ads, trackers, or monetization schemes. Optional user-authorized Google Drive storage is not analytics and must remain off by default.
* **Respect Copyleft**: Host-ia derives from Mori under **GPL-3.0**. All contributions and distributions must honor the license and preserve original copyright, author, license, and source notices, including GPL comments. See [License & Terms of Use](README.md#-license--terms-of-use).
* **Preserve Technical Identity**: Keep `com.mori.downloader` (including OAuth app registration), legacy paths and preference keys, PIN derivation, client/credential-service identifiers, native bridge/service names, and bundled binary names. Visible Host-ia branding is not an identifier or data migration.

---

## 💡 How Can I Contribute?

### 1. Reporting Bugs & Requesting Features
* Check existing [Host-ia fork issues](https://github.com/elektrorate/Mori/issues) before opening a new one to prevent duplicates. [Original Mori issues](https://github.com/coflyn/Mori/issues) belong to upstream, not this fork's release/support channel.
* Clearly specify your **Platform & OS version** (Android / macOS / Windows / iOS), **Host-ia Version** (target: v4.4.1), and the **Source URL** causing the error. Never include OAuth credentials or tokens in a report.

### 2. Translating & Localization (`public/js/i18n/`)
Host-ia inherits 9 languages (English, Indonesian, Japanese, Korean, Simplified Chinese, Arabic with RTL, Russian, Tagalog, and Hindi). If you want to refine translations or add a new locale, edit the dictionary files in `public/js/i18n/`.

### 3. Frontend & UI Enhancements
Feel free to refine the CSS design system, optimize MoriPlayer controls, enhance glassmorphism effects, or add responsive styling.

---

## 🛠️ Development & Build Guide

### Prerequisites

* **Node.js**: `v18.x` or higher (Node `v20+` recommended)
* **npm**: `v9.x` or higher
* **Git**: Installed and configured

#### Platform-Specific Requirements:
* **Android**: Android Studio, Android SDK 36, JDK 21, and the NDK configured in the project when rebuilding native components. Preserve the included native binaries and their technical names.
* **Desktop (macOS & Windows)**: Rust toolchain (`rustup`, `cargo`), Tauri CLI; Windows also requires the Tauri C++ build tools and WebView2 prerequisites.
* **iOS**: macOS with Xcode 15+ and CocoaPods (`gem install cocoapods`).

---

### Initial Setup

1. **Clone the Host-ia fork** (or your own fork of it):
   ```bash
   git clone https://github.com/elektrorate/Mori.git
   cd Mori
   ```

   The checkout directory remains `Mori/`. Do not rename it as part of branding. Do not pull or install upstream Mori wholesale to overwrite Host-ia changes; review upstream source changes separately.

2. **Install dependencies**:
   ```bash
   npm install
   ```

---

### 📱 Building for Android

Host-ia uses Mori's **CapacitorJS** integration and native **OkHttp** bridge (`MainActivity.java`) to handle network requests and bypass WebView CORS restrictions. Native package and bridge names remain unchanged.

#### Single-Command Build

```bash
# Build Debug APK
npm run build:android

# Build Debug APK on Windows
npm run build:android:windows

# Build Signed Release APK (Requires keystore configured below)
npm run build:android:release
```

Expected v4.4.1 output: `android/app/build/outputs/apk/debug/Host-ia v4.4.1.apk` or `android/app/build/outputs/apk/release/Host-ia v4.4.1.apk`, depending on build type. These are output names, not a claim that builds have completed.

#### Manual Build Steps

```bash
# 1. Sync web assets to Android
npx cap sync android

# 2. Compile via Gradle
cd android
./gradlew assembleRelease
```

#### Setting Up Signing Keystore (One-Time)

The following is the inherited development example, including its technical alias `mori` and certificate subject. Preserve an existing signing identity for compatible updates; do not regenerate a production keystore or copy these example passwords into production. The certificate subject is not Host-ia's visible product name. Keep keystores and real passwords out of version control.

```bash
keytool -genkey -v -keystore android/app/release.keystore -alias mori \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android123 -keypass android123 \
  -dname "CN=Mori, OU=Development, O=MoriApp, L=Unknown, ST=Unknown, C=ID"
```

Configure `signingConfigs` in `android/app/build.gradle`:

```groovy
android {
    signingConfigs {
        release {
            storeFile file('release.keystore')
            storePassword 'android123'
            keyAlias 'mori'
            keyPassword 'android123'
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

---

### 🖥️ Building for Desktop (macOS & Windows)

Host-ia Desktop is powered by **Tauri v2 (Rust)**. The visible `productName` and version in `src-tauri/tauri.conf.json` target `Host-ia` and `4.4.1`; the technical identifier remains `com.mori.downloader`.

#### Development Mode

```bash
npm run tauri:dev
```

#### Building Release Installers

```bash
npm run tauri:build
```

**Expected output artifacts after a successful build:**
* **macOS**: `src-tauri/target/release/bundle/macos/Host-ia.app`. On macOS, `npm run build:macos` packages `Host-ia-v4.4.1-macOS-arm64.dmg` and `Host-ia-v4.4.1-macOS-arm64.app.tar.gz`.
* **Windows**: `src-tauri/target/release/bundle/msi/Host-ia_4.4.1_x64_en-US.msi` and `src-tauri/target/release/bundle/nsis/Host-ia_4.4.1_x64-setup.exe` (configuration-generated names; locale/architecture may vary). The desktop release workflow renames them to `Host-ia-v4.4.1-Windows-x64.msi` and `Host-ia-v4.4.1-Windows-x64-Setup.exe`.

Distribute Host-ia binary releases through [elektrorate/Mori releases](https://github.com/elektrorate/Mori/releases), not [original Mori releases](https://github.com/coflyn/Mori/releases). A version/name change is not proof of build completion, signing, or publication. Retain runtime data paths and credential-service identifiers even though installers and `Host-ia.app` use the new visible name.

---

### 🍎 Building for iOS

#### Running on Simulator or Physical Device

```bash
# 1. Sync web assets & CocoaPods dependencies
npx cap sync ios

# 2. Open Xcode workspace
npx cap open ios

# 3. Select target device and press Run (Cmd + R)
```

#### Building Unsigned IPA (For Sideloading via AltStore / TrollStore)

```bash
# Single-command build
npm run build:ios:ipa
```

The local script targets `Host-ia v4.4.1.ipa`; the mobile release workflow uses `Host-ia-v4.4.1-iOS-Unsigned.ipa`. Neither naming convention guarantees a completed build.

**Manual steps:**
```bash
# 1. Sync assets
npx cap sync ios

# 2. Compile archive without code signing
xcodebuild -workspace ios/App/App.xcworkspace -scheme App -configuration Release \
  -sdk iphoneos -archivePath build/Host-ia.xcarchive archive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""

# 3. Package into IPA
mkdir -p Payload && cp -r build/Host-ia.xcarchive/Products/Applications/App.app Payload/
zip -r "Host-ia v4.4.1.ipa" Payload && rm -rf Payload build
```

---

## ⚙️ Architecture & Scraper Engine

* **Frontend**: Vanilla ES6 JavaScript + modern CSS design system located in `public/`.
* **Scraper Runtime**: The core scraping engine is compiled via esbuild into `public/js/scrapers/bundle.js` as an IIFE bundle containing all 16 platform engines.
* **HTTP Layer**: Cross-origin requests are routed through `public/js/scrapers/httpHelper.js`, utilizing native OkHttp on Android and native Rust HTTP on Desktop to bypass CORS.
* **Separate Update Channels**: Host-ia binary updates use `elektrorate/Mori`. OTA scraper metadata and `public/js/scrapers/bundle.js` intentionally remain sourced from `coflyn/Mori`; keep the original upstream URLs and technical keys for this service. Do not substitute an upstream installer for a fork binary update.
* **Optional Drive Storage**: Native Windows/Android support stages media privately, uploads it to the selected Google Drive folder, verifies the remote copy, and only then deletes the local temporary file. Failed uploads and pending cleanup remain available for manual retry. Preserve OAuth/client/credential-service identifiers and consult [GOOGLE_DRIVE.md](GOOGLE_DRIVE.md) for setup and native tests. Never embed credentials in frontend code.

> [!NOTE]
> **Scraper Core Contributions**: The scraper sources are maintained privately in `src-scrapers/` to deter low-effort ad-injected clones and scrapers abuse. The public repository runs from the precompiled `public/js/scrapers/bundle.js`. Honest developers who wish to fix broken endpoints or suggest scraper algorithms are encouraged to discuss them in an issue or PR with the author.

---

## 🌐 Contributing Translations (i18n)

Host-ia inherits multiple languages out-of-the-box (`en`, `id`, `ja`, `ko`, `zh`, `ar`, `ru`, `tl`, `hi`). All language strings are modularized into individual locale files located in:

```
public/js/i18n/
├── locales/
│   ├── en.js        # English (Source of Truth / Fallback)
│   ├── id.js        # Bahasa Indonesia
│   ├── ja.js        # 日本語 (Japanese)
│   ├── ko.js        # 한국어 (Korean)
│   ├── zh.js        # 中文 (Simplified Chinese)
│   ├── ar.js        # العربية (Arabic with RTL)
│   ├── ru.js        # Русский (Russian)
│   ├── tl.js        # Tagalog (Filipino)
│   └── hi.js        # हिन्दी (Hindi)
└── index.js         # Translation registry & fallback helper
```

### Improving an Existing Language
1. Open the relevant file in `public/js/i18n/locales/<lang>.js`.
2. Update the translation value for the desired key.
3. Verify the changes by switching to that language in Host-ia Settings. Translate visible product text without changing technical keys, actual paths, or original author credits.

### Adding a New Language
1. Create a new locale file in `public/js/i18n/locales/<new_code>.js` (e.g. `es.js` for Spanish).
2. Copy the key structure from `en.js` and translate the values.
3. Import and register the new locale in `public/js/i18n/index.js`:
   ```javascript
   import es from "./locales/es.js";
   export const translations = { ..., es };
   ```
4. Add the language name to `langNames` in `public/js/modules/settings/language.js` and the language option in `public/index.html`.

> [!TIP]
> **Safe Fallback**: If a key is not yet translated in your locale, Host-ia automatically falls back to English (`en`), preventing blank text or broken UI.

---

## 🎨 CSS & Styling Architecture (`public/css/`)

Host-ia's stylesheet retains the modular structure inherited from Mori under `public/css/`:

```
public/css/
├── variables.css    # Design tokens, themes (dark/light), font, speed, glass, corner presets
├── base.css         # Reset, body typography, layout wrappers, bottom navigation
├── components.css   # Reusable buttons, custom toasts, floating progress toasts, badges
├── home.css         # Main URL input bar, batch input, skeleton loader, media cards
├── history.css      # History items, summary stats card, thumbnail overlay & spinners
├── settings.css     # Menu lists, subpage slide transitions, dropdowns, live BG controls
├── modals.css       # Universal modal backdrops, PIN keypad, guide, confirm, batch modal
├── rtl.css          # Right-to-Left (RTL) overrides for Arabic layout
└── style.css        # Master stylesheet entry point importing all modules
```

When modifying styles:
- Edit the specific component/page file instead of cluttering a single file.
- Design tokens and CSS custom properties live in `variables.css`.
- Ensure all interactive controls support both dark and light themes.

---

## 🚀 Submitting a Pull Request

1. Fork [elektrorate/Mori](https://github.com/elektrorate/Mori) and create a descriptive branch:
   ```bash
   git checkout -b feat/your-feature-name
   ```
2. Keep your commits clean and follow conventional commit syntax (`feat: ...`, `fix: ...`, `docs: ...`).
3. Ensure the project builds cleanly without lint or syntax errors.
4. Push to your fork and submit a Pull Request to `main` in `elektrorate/Mori` for Host-ia changes, not to upstream Mori by default.
5. Describe what your PR changes, why it is needed, and how it was verified.

---

Original Mori author credits (preserved):

Developed with ❤️ by coflyn.  
GitHub: https://github.com/coflyn  
Instagram: @\_coflyn
