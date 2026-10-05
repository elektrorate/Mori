# Host-ia v4.4.1 Installation & User Guide

This guide covers installing, sideloading, troubleshooting, and using Host-ia on Android, macOS, Windows, and iOS. Host-ia is an independently branded derivative of [coflyn/Mori](https://github.com/coflyn/Mori), maintained in [elektrorate/Mori](https://github.com/elektrorate/Mori), not an official Mori release. Original authorship, copyrights, and GPL notices are preserved; see [License & Terms of Use](README.md#-license--terms-of-use).

---

## 📥 Host-ia Release Binaries

Use the fork's **[GitHub Releases](https://github.com/elektrorate/Mori/releases)** page for Host-ia binaries. The v4.4.1 names below describe the source/configuration target, not confirmation that a build has finished or a release is available. Follow these installation steps only after obtaining a completed Host-ia build. [Original Mori releases](https://github.com/coflyn/Mori/releases) are upstream binaries, not Host-ia updates; do not install them as an update to this fork.

| Platform | Recommended Asset | Installation Method |
| :--- | :--- | :--- |
| **Android** | `Host-ia v4.4.1.apk` | Direct Sideload / Install |
| **macOS** | `Host-ia-v4.4.1-macOS-arm64.dmg` or `.app.tar.gz` | Move `Host-ia.app` to `/Applications` |
| **Windows** | `Host-ia-v4.4.1-Windows-x64-Setup.exe` or `Host-ia-v4.4.1-Windows-x64.msi` | Standard Windows Installer |
| **iOS** | `Host-ia-v4.4.1-iOS-Unsigned.ipa` (workflow) or `Host-ia v4.4.1.ipa` (local script) | Sideload (AltStore / TrollStore / Scarlet) |

Local Tauri output may instead be named `Host-ia_4.4.1_x64-setup.exe` or `Host-ia_4.4.1_x64_en-US.msi`. Use the actual file from your completed build; release workflows rename these installers.

---

## 📱 Android Installation & Troubleshooting

### Standard Installation
1. Download `Host-ia v4.4.1.apk` on your phone from a completed fork release or your own build.
2. Open the downloaded APK and tap **Install**.
3. Grant storage permissions when prompted so Host-ia can save media to your device.

The Android application ID and OAuth registration remain `com.mori.downloader`. A differently signed original Mori installation cannot be upgraded with a fork APK using another certificate. Back up history and pending downloads before uninstalling: uninstalling removes private app data and pending Drive temporary files.

### "Blocked by Play Protect" Warning
Sideloaded Host-ia APKs may trigger a Google Play Protect advisory. Verify the source and signing certificate before proceeding; a warning is not proof that a file is safe.
1. Tap **"More Details"**.
2. Only if you trust and have verified the build, tap **"Install Anyway"** if offered. Do not disable device security globally.
3. *(Optional)* Scan the `.apk` on **[VirusTotal](https://www.virustotal.com/)**. A scan is an additional check, not a safety guarantee.

---

## 🍏 macOS Installation & Gatekeeper Fix

### Standard Installation
* **Option A (DMG Installer - Recommended):** Download `Host-ia-v4.4.1-macOS-arm64.dmg`, double-click to open, and drag **Host-ia** into your **Applications** folder.
* **Option B (Tarball Archive):** Download `Host-ia-v4.4.1-macOS-arm64.app.tar.gz`, extract it, and move `Host-ia.app` into your **Applications** folder.

### "Host-ia is damaged and can't be opened" (Gatekeeper Quarantine)
When downloading `.app` or `.dmg` bundles via web browsers (Safari, Brave, Chrome), macOS automatically tags unnotarized binaries with the `com.apple.quarantine` attribute.

This warning can also indicate an invalid or damaged build. Verify its origin and integrity first. Only for a trusted build affected by quarantine, choose one of these methods:
* **Method 1 (Quick Terminal Command - Recommended):**
  Open Terminal and run:
  ```bash
  sudo xattr -rd com.apple.quarantine /Applications/Host-ia.app
  ```
* **Method 2 (Finder):**
  Right-Click (or Control + Click) `Host-ia.app` in Finder → Select **Open** → Click **Open** on the confirmation dialog, if macOS offers it.

---

## 🪟 Windows Installation

1. Download `Host-ia-v4.4.1-Windows-x64-Setup.exe` (Standard Setup) or `Host-ia-v4.4.1-Windows-x64.msi` (Windows Installer Package), or use the configuration-generated names noted above.
2. Run the installer and follow the setup wizard.
3. If Windows SmartScreen displays *"Windows protected your PC"*, inspect **"More info"** and verify the source. Select **"Run anyway"** only for a trusted build if the option is available. Unsigned builds may be blocked by Smart App Control; do not disable security protections automatically.

Visible installer and application names are Host-ia, but existing runtime data folders, credential-service names, and download paths still use their legacy `Mori` identifiers. Do not rename those folders or credentials as an installation step.

---

## 📲 iOS Sideloading Guide

For a completed Host-ia IPA build distributed outside the Apple App Store, use a compatible sideloading tool. The local script names it `Host-ia v4.4.1.ipa`; the workflow names it `Host-ia-v4.4.1-iOS-Unsigned.ipa`.

### Option 1: AltStore / Sideloadly (Recommended for all iOS versions)
* **Best for:** Devices running any modern iOS version without jailbreak.
* **Requirements:** A PC or Mac for initial installation.
* **Steps:**
  1. Install [AltStore](https://altstore.io/) or [Sideloadly](https://sideloadly.io/) on your computer.
  2. Download the completed Host-ia IPA named above.
  3. Connect your iPhone via USB and select the IPA to install with your free Apple ID.
  4. *Note:* App signatures refresh every 7 days automatically when on the same Wi-Fi.

### Option 2: TrollStore (Permanent - No Expire)
* **Best for:** Compatible iOS versions (iOS 14.0 – 16.6.1 / 17.0).
* **Steps:**
  1. Open the downloaded Host-ia IPA directly in TrollStore.
  2. Tap **Install**. It installs permanently without 7-day expiration or computer refresh.

### Option 3: Scarlet / Esign (On-Device Direct Install)
* **Best for:** Direct installation without a PC using enterprise developer certificates.
* **Steps:**
  1. Open Scarlet or Esign on your iPhone.
  2. Import the Host-ia IPA and tap **Sign & Install**.

---

## 💡 How to Use Host-ia

1. **One-Tap Download**:
   - Copy a video/photo/music link from any of the 16 supported platforms.
   - Open Host-ia (or tap the **Paste** button). Host-ia auto-detects copied links.
   - Tap **Analyze** → Choose format/quality → Tap **Download**.
2. **Quick Save via Share Menu (Android)**:
   - In TikTok, Instagram, or YouTube, tap **Share** → Select **Host-ia**.
   - Host-ia opens a quick overlay to download the media without leaving your current app.
3. **Multi-Link Batch Mode**:
   - Paste several URLs (separated by newlines or spaces) in the input box.
   - Host-ia queues and analyzes them automatically.
4. **Download Entire Playlists / Albums**:
   - Paste a Spotify, Apple Music, or YouTube playlist link.
   - Tap **Download All** to download all tracks in sequence with automatic retry for failed items.
5. **Photo-to-PDF Gallery Export**:
   - When viewing a multi-photo post (Instagram, TikTok Slides, RedNote), select **Combine into Single PDF** to export the entire album into a single PDF document.
6. **Privacy PIN & Biometric Lock**:
   - Go to **Settings** → **General** → **App Lock**.
   - Set a 4-digit PIN or enable Fingerprint / Face ID to keep your downloads private.

---

## 🔒 Safety & Privacy Verification

Host-ia retains the ad-free, open-source client without analytics or telemetry.
* App logic runs on your device, but downloading makes network requests to source platforms and third-party scraper providers. Update checks also contact GitHub.
* Local storage is the default. Existing paths such as `Download/Mori`, `Movies/Mori`, `Music/Mori`, and `Pictures/Mori` remain unchanged, depending on platform/settings.
* Enabling optional Drive storage sends downloaded media to your authorized Google Drive account; it is not local-only storage.
* You can scan a binary on **[VirusTotal](https://www.virustotal.com/)** before installation. Scanning uploads that binary to a third-party service and does not replace source/signature verification.

## Optional Google Drive Storage

Google Drive storage is **off by default** and requires native support on **Windows or Android**. Local downloads work without a Google account. For OAuth setup and platform details, see [GOOGLE_DRIVE.md](GOOGLE_DRIVE.md); Android OAuth must continue to use `com.mori.downloader` and the SHA-1 of the installed APK's signing certificate.

1. In Host-ia **Settings > Google Drive**, import desktop credentials on Windows when needed, then connect your Google account and choose a Drive folder. Connecting alone does not enable Drive storage.
2. Enable **Save only in Drive**. Media is downloaded into private temporary storage, uploaded, and checked against Drive metadata before the local temporary file is deleted.
3. Run **Test upload (.txt)** and confirm both the verified-upload/local-cleanup message and the `Host-ia_drive_test_*.txt` file in your selected Drive folder.
4. If an upload fails, the local temporary file and pending job are retained. If local cleanup is pending, do not assume the temporary file was deleted. Retry from Settings; cleanup verifies the remote copy again before deletion.
5. Keep pending files until retry succeeds. Do not uninstall to solve a transfer failure: uninstalling removes private temporary files. Disconnecting does not delete files already in Drive or pending temporary files. Pending jobs require manual retry after reopening; completion with the app closed is not guaranteed.

## Update Channels

Use binary updates from [elektrorate/Mori](https://github.com/elektrorate/Mori/releases) for Host-ia. The original `coflyn/Mori` OTA scraper service is intentionally retained for scraper bundles only. Do not replace Host-ia with an upstream Mori installer when troubleshooting scraper updates.

---

Original Mori author credits (preserved):

Developed with ❤️ by coflyn.  
GitHub: https://github.com/coflyn  
Instagram: @\_coflyn
