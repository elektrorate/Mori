# Native Windows Drive Backend

The only new command is `tauri_drive_action(action, options)` (the AppHandle is
injected by Tauri). This implementation is Windows-only. Other platforms return
an explicit unsupported error; Android uses its separate native implementation.
Existing local download and save commands are unchanged.

## Actions

| Action | Options | Result |
| --- | --- | --- |
| `status` | none | Connection status below |
| `configure` | none | Native file dialog imports Google Desktop credentials JSON; returns status |
| `connect` | none | System browser OAuth and folder Picker; returns status |
| `setEnabled` | `{enabled: boolean}` | Returns status; enabling requires a saved connection/folder |
| `disconnect` | none | Clears native credentials and disables Drive; retains all pending files/jobs |
| `download` | `{url, fileName, headers?, sourceUrl?, title?}` | Stages source bytes, uploads, verifies, then cleans up |
| `saveBytes` | `{data, fileName, sourceUrl?, title?}` | Streams standard base64 into staging, then uses the same upload/verification flow |
| `retry` | `{jobId}` | Resumes/verifies the captured job, or only cleans up an already verified upload |
| `acknowledge` | `{receiptId}` | Deletes only that local receipt; returns `{acknowledged: true, receiptId}` (idempotent) |

Status has exactly the following public fields, with no tokens or session URLs:

```json
{
  "connected": false,
  "enabled": false,
  "folderId": null,
  "folderName": null,
  "pending": [
    {
      "jobId": "opaque-native-job-id",
      "fileName": "document.pdf",
      "title": null,
      "sourceUrl": null,
      "error": "Human-readable failure or interruption description"
    }
  ],
  "completed": [
    {
      "receiptId": "opaque-native-job-id",
      "sourceUrl": "https://www.youtube.com/watch?v=abcdefghijk",
      "title": "Document",
      "driveFileId": "GOOGLE_FILE_ID",
      "uri": "https://drive.google.com/file/d/GOOGLE_FILE_ID/view",
      "fileName": "document.pdf",
      "localDeleted": true
    }
  ]
}
```

`connected` reflects the saved native authorization, not a live token-validity
probe. New downloads require `enabled`; explicit retry does not, but always
requires authorization for the job's original Google account. Folder selection
on reconnect never changes a pending job's captured folder. Connect preserves
the previously saved enable toggle; the default for a first connection is off.
Configure explicitly resets the toggle to off. Use `setEnabled` to opt in.

A fully successful download/save/retry returns:

```json
{
  "receiptId": "opaque-native-job-id",
  "sourceUrl": "https://www.youtube.com/watch?v=abcdefghijk",
  "title": "Document",
  "driveFileId": "GOOGLE_FILE_ID",
  "uri": "https://drive.google.com/file/d/GOOGLE_FILE_ID/view",
  "fileName": "document.pdf",
  "localDeleted": true
}
```

If Drive verification succeeds but local cleanup fails, the result has
`localDeleted: false` and a `pending` job object with its error. The frontend must
not interpret that as cloud-only completion. Other failures reject with a
human-readable string including the pending job ID once staging has begun;
refresh `status` to obtain pending metadata. Concurrent commands reject with a
busy error instead of queuing additional work. The frontend helper must serialize
**all** Drive calls, including status and bulk downloads. The native process lock
also protects against another Mori instance; there is no native blocking queue.

Completion receipts live outside staging jobs in `app_data_dir/drive/receipts/`.
Their stable `receiptId` is the job ID. Native code durably saves a false receipt
before local cleanup, then saves the true receipt before removing job metadata.
Cleanup failures leave `localDeleted: false`; a later verified cleanup retry
updates the same receipt ID. If a response is lost or Mori closes before JS
saves history, `status.completed` still contains the receipt.

The frontend should upsert history by `receiptId`, persist that change, and only
then invoke `acknowledge`. Acknowledgement validates the native ID/path and
removes only the receipt, never a pending job, payload, or Drive file. If a false
receipt was acknowledged after saving history, a later cleanup retry recreates
it with the same ID so history can be updated rather than duplicated.

Receipts are local delivery records, not account authorization. Status returns
all local receipts even after disconnect/account switching, without exposing an
account ID. Retrying a pending job remains bound to its captured Google account.
Configure and disconnect never clear receipts.

## Credentials And Setup

- Enable both Google Drive API and Google Picker API in the OAuth client project.
- Use an OAuth client with application type **Desktop app**, and configure the
  Google consent screen/test users as appropriate for the project's publication
  state. Loopback redirects use `127.0.0.1` and an ephemeral native port.
- The built-in desktop client ID is public. No client secret is shipped.
- Prefer `configure` to import a newly downloaded Desktop credentials JSON after
  rotating the previously shared secret. The JSON is read only by native code;
  the client ID/secret are saved in Windows Credential Manager, not app JSON,
  frontend storage, command options, logs, or responses. Mori does not modify or
  delete the user's original imported JSON file.
- Alternatively, native code can read `MORI_DRIVE_CLIENT_SECRET` at runtime for
  the built-in public client ID. Do not put this variable/value in frontend
  configuration, source control, command arguments, or logs. Google may require
  a desktop client secret during token exchange even with PKCE.
- Disconnect clears imported config as well as authorization. Re-import if
  needed before reconnecting. Configure also disables the previous connection
  while replacing its credentials; pending jobs remain intact.

OAuth uses auth code + PKCE S256, cryptographically random state/verifier,
offline authorization, a 180-second loopback callback deadline, and the official
native Picker parameters. The only requested scope is `drive.file`. The folder
is checked with `files.get` for folder MIME, `trashed: false`, and explicit
`capabilities.canAddChildren: true`. Account identity comes from Drive
`about.user.permissionId`, without requesting identity scopes.

## Durability And Safety

Jobs live in `app_data_dir/drive/jobs/<random-job-id>/`. The display filename is
validated and never used as a staging path. The native job contains captured
folder/account, size, MD5, reserved Drive ID, verification state, and the latest
error. Source headers and transport/download URLs are not persisted. Only the
caller-supplied `sourceUrl` content-page identity is considered for public
metadata; native code never substitutes `url`. Non-HTTP(S) URLs, embedded
credentials, known CDN destinations, and obvious media transport paths become
null. Fragments and arbitrary query parameters are removed. Only validated
YouTube video IDs and numeric Facebook content IDs survive their respective
source-page queries. Other page identities retain origin/path only. Callers must
still supply the original content page rather than a transport URL and must not
put credentials in titles or URL paths. Existing pending source identities are
sanitized when status/retry reads them.

Completed bytes are synced to disk before durable promotion from `payload.part`
to `payload.bin`. Interrupted source downloads keep their partial data but are
not uploaded or silently downloaded again: start a fresh source download if the
pending job reports that the source was incomplete. A crash after payload
promotion can recover the completed payload on explicit retry.

Drive uploads use resumable sessions and bounded 8 MiB chunks. Session URLs are
DPAPI-protected for the Windows user. Retry probes an existing session and
resumes at Google's confirmed offset. A pre-generated Drive file ID is persisted
before upload initialization, preventing duplicate creations after uncertain
responses or expired sessions. No token-bearing request follows redirects or
uses a curl fallback. Session destinations must be HTTPS on `www.googleapis.com`
at the Drive upload endpoint.

Deletion requires matching Drive ID, byte size, MD5 checksum, exact parent
folder, and untrashed state. A successful upload response is also checked with an
independent `files.get`, and local bytes are rehashed before cleanup. Missing or
mismatched verification retains local data. Once verified, a job only performs
reverification/cleanup on retry, never a fresh upload. Cleanup never recursively
deletes a job directory containing unknown/unverified files.

There is no indefinite automatic retry, background uploader, cancellation API,
or automatic fallback to a different account, folder, or local Downloads copy.

## Verification

Pure Rust unit tests cover filename/path validation, strict session destinations,
required Drive verification fields, writable folder validation, and scope limits.
Additional pure regressions cover receipt serialization/IDs, source sanitization,
and rejecting changed cloud/local metadata even when `verified` is already true.
On a Windows machine with Rust/Cargo and the existing Tauri prerequisites:

```powershell
cargo check --manifest-path src-tauri/Cargo.toml
cargo test --manifest-path src-tauri/Cargo.toml drive::tests
```

The dependency changes require Cargo.lock regeneration by Cargo. Do not manually
edit its resolution/checksums. Test live consent/folder selection, a large media
upload, PDF bytes, interruption/restart, expired sessions, account switching,
missing checksum, and locked local files before release. Live OAuth requires the
rotated credentials and user consent; pure tests do not use credentials.

The `windows-sys 0.59` DPAPI signatures were checked against the crate docs:
`CryptProtectData`/`CryptUnprotectData` use input/output `CRYPT_INTEGER_BLOB`
pointers; the latter takes a `*mut PWSTR` description output. The code passes
null for unused optional parameters and frees returned data with Foundation's
`LocalFree`. `Win32_Security_Cryptography` enables `Win32_Security` transitively,
so no additional feature is required. Syntax-only validation does not replace
a Windows Cargo type check or live recovery testing.
