# Android Native Drive

Only `android/**` implements this backend. MainActivity registers the Capacitor
plugin `MoriDrive` before `super.onCreate`. ShareActivity uses the same backend
through its native `driveRequest` bridge and a small injected Capacitor request
shim. Neither surface receives OAuth tokens, account credentials, authorization
codes, upload-session URLs, download headers or private temporary paths.

## Google Cloud Setup

1. Enable **Google Drive API** and **Google Picker API** in the same Cloud project.
2. Configure the Google Auth consent screen and `https://www.googleapis.com/auth/drive.file`.
   Add the intended accounts as test users while the consent screen is in testing.
3. Create Android OAuth clients for package **`com.mori.downloader`**, with the
   **SHA-1 of each signing certificate actually used to install the app**.
   Debug, release, and Google Play App Signing certificates need corresponding
   registration. The upload certificate alone is insufficient for Play-installed builds.
4. Obtain fingerprints using `gradlew.bat signingReport` once a JDK and the
   Android SDK are installed. Register certificates externally; do not put
   private keys, client secrets or tokens in JavaScript, assets or logs.

Android does not need a `configure` action, web client secret, WebView OAuth
redirect or a JSON credential import. Google Play services resolves the Android
client from package and signing identity. A device with working Google Play
services and an eligible Google account is required.

## API

`MoriDrive.request({ action, options })` supports:

| Action | Options | Result |
| --- | --- | --- |
| `status` | `{}` | `{connected, enabled, folderId, folderName, pending, completed}` |
| `connect` | `{}` | Native consent/account selection and folder Picker, followed by status |
| `setEnabled` | `{enabled: boolean}` | Persisted native setting, followed by status |
| `disconnect` | `{}` | Disable, clear selected account/folder references, retain pending jobs |
| `download` | `{url, fileName, headers?, sourceUrl?, title?}` | Verified cloud result |
| `saveBytes` | `{data: base64, fileName, sourceUrl?, title?}` | Same staging/upload/verification pipeline, including PDFs |
| `retry` | `{jobId}` | Resume original-account/original-folder job, or cleanup only if confirmed |
| `acknowledge` | `{receiptId}` | Remove a saved-history receipt and return updated status; never delete a pending job |

Drive is **off by default**. `connect` chooses a folder but does not enable it;
call `setEnabled` explicitly. Only HTTPS source URLs and HTTPS redirects are
accepted. Source credentials in supplied headers are not sent to another origin.

Pending entries contain `{jobId, fileName, title, sourceUrl, error}` plus
`uploaded` and `localDeleted: false`. Source URL query/fragment/userinfo are
removed from these public records, except allowlisted public content identifiers:
YouTube `v`/`list`, Facebook `id`/`story_fbid`/`fbid`/`v`, and supported TeraBox
domains' `surl`/`shareid`/`uk`/`fid`/`shorturl`. Passwords, tokens and tracking keys
are excluded. This changes new jobs only; identifiers already stripped from old
jobs cannot be recovered. SDK/HTTP exception details are not forwarded.

Successful transfers return:

```json
{
  "ok": true,
  "jobId": "native-job-uuid",
  "receiptId": "native-job-uuid",
  "sourceUrl": "https://source.example/document",
  "uploaded": true,
  "driveFileId": "google-file-id",
  "fileName": "document.pdf",
  "uri": "https://drive.google.com/file/d/google-file-id/view",
  "localDeleted": true,
  "driveFiles": [{
    "id": "google-file-id",
    "driveFileId": "google-file-id",
    "uri": "https://drive.google.com/file/d/google-file-id/view",
    "name": "document.pdf",
    "fileName": "document.pdf",
    "title": "Document",
    "localDeleted": true
  }]
}
```

Top-level `id`, `name` and `title` also identify the verified cloud file.
Unconfirmed operations reject the Capacitor promise with a sanitized error and
pending job ID; the share request promise also rejects unconfirmed results.
Once cloud verification is durable, cleanup failures still return the cloud
result with `localDeleted: false` and retain a cleanup-only pending job.
Failed current checksum, account, or cloud verification rejects even if the job
has a historical cloud confirmation; it does not return that stale confirmation
as a successful retry.

## Completion Receipts

Every confirmed main-app and share upload stores a receipt in the same encrypted
state transaction as confirmation, before any content deletion. Cleanup updates
that receipt and removes the staging job in one state transaction. `status.completed`
returns only:

```json
{
  "receiptId": "native-job-uuid",
  "sourceUrl": "https://www.facebook.com/watch?v=987",
  "title": "Video",
  "driveFileId": "google-file-id",
  "uri": "https://drive.google.com/file/d/google-file-id/view",
  "fileName": "video.mp4",
  "localDeleted": true
}
```

Receipts have no expiry and survive disconnect and `clearPendingHistoryList`.
Retries update the same receipt ID; they do not append duplicates. A receipt with
`localDeleted: false` may be acknowledged; its staging job remains pending and
must still pass fresh verification on cleanup retry. Acknowledged receipts are
not recreated by that job's subsequent cleanup. A repeated acknowledgement is
an idempotent no-op, and acknowledging a nonexistent receipt does not suppress a
future completion.

**Frontend integration contract (not implemented outside Android):** preserve
`completed` in status validation and `receiptId` in transfer results. On startup,
resume, and transfer completion, merge each receipt into durable history by
`driveFileId`/`receiptId`. Only after that history write succeeds call
`request({action: "acknowledge", options: {receiptId}})`. Do not auto-ack merely
because a promise resolved or the older share-history queue was drained. History
matching should use the sanitized platform identifiers, not passwords/tracking
parameters. Native receipts contain no account references, tokens, session URLs,
headers or private local paths.

## Durability And Shares

- Each job captures its selected folder ID and SDK-authorized stable Google
  account ID before writing any downloaded bytes. Reconnecting does not retarget
  jobs. All cleanup retries also require reconnecting the original account.
- Content is staged only under app-private `files/drive-pending/<jobId>/`.
  Incomplete source attempts remain there; a retry starts a fresh attempt without
  deleting the previous unverified bytes. No MediaStore/gallery scan is performed.
- Job state, source headers/URLs, pre-generated Drive file ID, resumable session
  and acknowledged offsets are atomically persisted in an Android Keystore
  AES-GCM encrypted file under `noBackupFilesDir`. Tokens are never persisted by
  Mori. Backup rules also exclude private staged content.
- Uploads stream 8 MiB chunks. Retry probes the existing session's server Range;
  a pre-generated persisted file ID prevents creating a second file after an
  ambiguous initiation/final-chunk timeout. Expired sessions reuse that file ID.
- Only a separate Drive `files.get` verifying ID, byte size, MD5, the exact parent
  folder and explicit boolean `trashed: false` enables deletion. Cleanup performs
  native authorization for the captured account, hashes the remaining payload,
  fetches fresh cloud metadata, and checks the payload again before deletion.
  Cleanup-only retries never reupload. Other attempts are removed first; a failed
  attempt cleanup retains the payload for the next checksum check. If a crash
  occurred after all bytes were deleted, fresh cloud/account verification may
  finalize the receipt without performing any filesystem deletion; an empty
  directory can remain in that recovery case.
- Cleanup deletes only payload/attempt names recorded in the job. Unknown files,
  directories and symlinks stop cleanup without recursive deletion. Older jobs
  whose interrupted attempt names were never recorded retain those unknown
  remnants rather than guessing ownership.
- Native share results persist separately as `mori_pending_drive_history_list`
  records with `driveFiles`, **never `localUri` or local file paths**. The existing
  `getPendingHistoryList`/`clearPendingHistoryList` bridge includes/drains both
  queues, allowing the frontend's cloud-aware history merge to consume them.
- Existing `MoriShareBridge.downloadFile` calls also route through this backend
  when native Drive is enabled. Completion calls
  `onDownloadComplete(filename, "", cloudResult)`; the empty saved path prevents
  local-file history updates. Request promises use the existing
  `window.__moriShareCallbacks` map with JSON-string responses.
- Completion notifications say **Saved to Google Drive** and open the cloud file.
  Cloud staging never requests all-files access. Disabled local mode keeps its
  existing download/gallery behavior.

## Share WebView Policy

The implementation loads APK `public/share.html` at `https://localhost/share.html`
using native asset interception. Localhost HTTP/HTTPS and the legacy
`file:///android_asset/public/` shape map only to APK assets, never network/file
system content; other local documents, userinfo, nondefault ports and traversal
paths are rejected. File/content access and mixed-content access are disabled.

Main-frame external HTTP(S) navigation uses an external `ACTION_VIEW` intent.
Unknown requests are blocked by `shouldInterceptRequest`, not just navigation
callbacks. The packaged page's CSP disables frames, objects and workers. Remote
scripts, HTML, SVG and arbitrary stylesheets are denied; an isolated TLS client
can supply raster images, audio/video, Google Fonts CSS and font data, with MIME
checks and nonexecuting response policy. Inline app handlers and the packaged
scraper loader's `new Function` require the local CSP's inline/eval allowances.
Scraper HTTP still uses the existing native HTTP bridge. Configuration/Drive/HTTP
callbacks check that the current page is trusted before JavaScript delivery.

Authorization request/Picker/validation phases are retained in a ViewModel across
configuration changes. Saved state contains phase and generation, not tokens.
True process recreation with a lost ViewModel cancels authorization rather than
waiting for an uncertain/stale Picker callback. The caller must connect again.
Back cancels the retained operation. Device behavior remains unverified as noted
below.

There is **no app-closed/background scheduling guarantee**. The native executor
is independent of the WebView, but Android may terminate the process. Reopen and
manually retry pending jobs. If `saveBytes` is interrupted before the bytes have
finished staging, the caller must resubmit those bytes; any partial local bytes
remain preserved. Authorization requiring fresh consent returns `AUTHORIZE_AGAIN`;
use native connect in the main app, then retry. Disconnect does not revoke the
Google account's existing consent grant; it clears Mori's selected destination.

## Verification

Google's Picker guide, AuthorizationRequest.Builder/ResourceParameter,
AuthorizationResult, AuthorizationClient, GoogleSignInAccount and ClearTokenRequest
reference docs were checked. Official Google Maven metadata identifies
`play-services-auth:22.0.0`; its actual AAR was inspected to confirm
`addResourceParameter(ResourceParameter, String)`, `setPrompt(int)`,
`setOptOutIncludingGrantedScopes(boolean)`, Picker constants,
`getTokenResponseParams(): Bundle`, and `toGoogleSignInAccount()`.
The latter is deprecated but is the documented account identity accessor on
AuthorizationResult in this pinned version; it is not used for legacy sign-in UI.

References:

- https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker
- https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder
- https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult
- https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-auth/maven-metadata.xml
- https://developers.google.com/workspace/drive/api/guides/manage-uploads

`MoriDriveValidationTest` contains six JUnit tests for filenames, Range parsing,
strict cloud verification (including missing/string trash flags), public content
identifiers, local-origin asset policy and remote MIME policy. All six JUnit tests
passed, along with the existing basic test (seven total). `node --test drive-bridge.test.mjs` passed **8 tests**, exercising
the actual injected share request shim: completed-status/result fields, explicit
acknowledgement, existing HTTP callback alias, cleanup results, failed fresh
verification and sanitized bridge errors. These tests mock the native response;
they do not test native receipt persistence or Android network/lifecycle behavior.
Java syntax parsing passed for all eight main/test Java sources checked.
The existing project targets **Java 21**, Android SDK 36 and NDK 28.2.13676358.
JDK 21 and the Android SDK are now installed. Native Java compilation, unit tests,
`assembleDebug` and APK signature verification succeeded. The APK certificate
matches the Android OAuth registration. The user confirmed successful native
authorization/folder selection, a real test upload to the chosen folder and
local temporary-file deletion on their phone. This confirmation is not a test
of WebView/CSP enforcement, rotation/process recreation, receipt crash recovery,
network-loss/quota faults or all media providers; those scenarios remain unverified.
