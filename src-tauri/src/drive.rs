//! Windows-only Drive staging. Nothing sensitive crosses the command boundary.
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use md5::Md5;
use rand::{rngs::OsRng, RngCore};
use reqwest::{
    blocking::{Client, Response},
    Url,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{BufReader, Read, Seek, SeekFrom, Write},
    net::TcpListener,
    os::windows::{ffi::OsStrExt, fs::OpenOptionsExt},
    path::{Path, PathBuf},
    time::{Duration, Instant},
};
use tauri::Manager;
use windows_sys::Win32::{
    Foundation::LocalFree,
    Security::Cryptography::{
        CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    },
    Storage::FileSystem::{MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH},
    UI::{Shell::ShellExecuteW, WindowsAndMessaging::SW_SHOWNORMAL},
};

const DEFAULT_CLIENT_ID: &str =
    "494495230951-8iau9fe3e9o9e8q72lhsr69rvo8utlm8.apps.googleusercontent.com";
const SCOPE: &str = "https://www.googleapis.com/auth/drive.file";
const API: &str = "https://www.googleapis.com/drive/v3";
const FOLDER_MIME: &str = "application/vnd.google-apps.folder";
const FILE_FIELDS: &str = "id,size,md5Checksum,parents,trashed";
const CHUNK_SIZE: usize = 8 * 1024 * 1024;

#[derive(Serialize, Deserialize)]
struct Config {
    client_id: String,
    client_secret: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct Auth {
    refresh_token: String,
    account_id: String,
    client_id: String,
}

#[derive(Default, Serialize, Deserialize)]
struct Settings {
    enabled: bool,
    folder_id: Option<String>,
    folder_name: Option<String>,
    account_id: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct Job {
    job_id: String,
    file_name: String,
    title: Option<String>,
    source_url: Option<String>,
    folder_id: String,
    account_id: String,
    ready: bool,
    size: Option<u64>,
    md5: Option<String>,
    drive_file_id: Option<String>,
    verified: bool,
    error: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Receipt {
    receipt_id: String,
    source_url: Option<String>,
    title: Option<String>,
    drive_file_id: String,
    uri: String,
    file_name: String,
    local_deleted: bool,
}

impl Receipt {
    fn new(job: &Job, local_deleted: bool) -> Result<Self, String> {
        validate_local_id(&job.job_id)?;
        let id = job
            .drive_file_id
            .as_deref()
            .filter(|id| valid_drive_id(id))
            .ok_or_else(|| "Missing verified Drive ID for completion receipt.".to_string())?;
        if !job.verified {
            return Err("Refusing a completion receipt before Drive verification.".into());
        }
        Ok(Self {
            receipt_id: job.job_id.clone(),
            source_url: safe_source_url(job.source_url.as_deref()),
            title: job.title.clone(),
            drive_file_id: id.to_owned(),
            uri: format!("https://drive.google.com/file/d/{id}/view"),
            file_name: job.file_name.clone(),
            local_deleted,
        })
    }
}

pub fn action(
    action: &str,
    options: Option<Value>,
    app: &tauri::AppHandle,
) -> Result<Value, String> {
    let root = app
        .path()
        .app_data_dir()
        .map_err(|_| "Cannot locate Host-ia application data.".to_string())?
        .join("drive");
    fs::create_dir_all(root.join("jobs"))
        .map_err(|_| "Cannot create durable Drive staging.".to_string())?;
    // Serialize both repeated clicks and separate Mori processes using this data.
    let _guard = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .share_mode(0)
        .open(root.join("operation.lock"))
        .map_err(|_| {
            "Drive staging is busy or inaccessible. Wait for any current Drive operation to finish."
                .to_string()
        })?;
    fs::create_dir_all(root.join("receipts"))
        .map_err(|_| "Cannot create durable Drive completion storage.".to_string())?;
    let options = options.unwrap_or_else(|| json!({}));
    match action {
        "status" => status(&root),
        "configure" => {
            let selected = rfd::FileDialog::new()
                .set_title("Import Google Desktop OAuth Credentials")
                .add_filter("Google credentials JSON", &["json"])
                .pick_file()
                .ok_or_else(|| "Credential import was cancelled.".to_string())?;
            let file = File::open(selected)
                .map_err(|_| "Cannot open the selected credentials file.".to_string())?;
            let mut bytes = Vec::new();
            file.take(64 * 1024 + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "Cannot read credentials.".to_string())?;
            if bytes.len() > 64 * 1024 {
                return Err("The credentials file is too large.".into());
            }
            let imported: Value = serde_json::from_slice(&bytes)
                .map_err(|_| "Invalid Google credentials JSON.".to_string())?;
            let installed = imported.get("installed").ok_or_else(|| {
                "Import a Google OAuth Desktop app JSON, not a web client.".to_string()
            })?;
            let client_id = required_string(installed, "client_id")?;
            if !client_id.ends_with(".apps.googleusercontent.com") {
                return Err("Invalid Google desktop client ID.".into());
            }
            let client_secret = required_string(installed, "client_secret")?;
            // Replacing credentials invalidates the old native connection, not pending data.
            let mut settings = load_settings(&root)?;
            settings.enabled = false;
            atomic_json(&root.join("settings.json"), &settings)?;
            clear_credential("oauth")?;
            write_credential(
                "config",
                &Config {
                    client_id,
                    client_secret: Some(client_secret),
                },
            )?;
            status(&root)
        }
        "connect" => connect(&root),
        "setEnabled" => {
            let enabled = options
                .get("enabled")
                .and_then(Value::as_bool)
                .ok_or_else(|| "enabled must be a boolean.".to_string())?;
            let mut settings = load_settings(&root)?;
            if enabled && !connected(&settings, read_credential::<Auth>("oauth")?.as_ref()) {
                return Err("Connect Google Drive and choose a folder first.".into());
            }
            settings.enabled = enabled;
            atomic_json(&root.join("settings.json"), &settings)?;
            status(&root)
        }
        "disconnect" => {
            atomic_json(&root.join("settings.json"), &Settings::default())?;
            clear_credential("oauth")?;
            clear_credential("config")?;
            // Pending payloads and their protected sessions intentionally survive.
            status(&root)
        }
        "download" | "saveBytes" => stage(&root, action, &options),
        "retry" => {
            let id = required_string(&options, "jobId")?;
            let dir = job_dir(&root, &id)?;
            let mut job: Job = read_json(&dir.join("job.json"))?;
            if job.job_id != id {
                return Err("The staging job identity is invalid.".into());
            }
            job.source_url = safe_source_url(job.source_url.as_deref());
            let result = upload(&root, &dir, &mut job);
            finish_job(&dir, &mut job, result)
        }
        "acknowledge" => {
            let id = required_string(&options, "receiptId")?;
            let path = receipt_path(&root, &id)?;
            match fs::remove_file(path) {
                Ok(()) => (),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
                Err(_) => {
                    return Err(
                        "Cannot acknowledge the completion receipt. It was retained.".into(),
                    )
                }
            }
            // Idempotent if the previous acknowledgement response was lost.
            Ok(json!({"acknowledged": true, "receiptId": id}))
        }
        _ => Err("Unknown native Drive action.".into()),
    }
}

fn required_string(value: &Value, key: &str) -> Result<String, String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .ok_or_else(|| format!("{key} must be a nonempty string."))
}

fn optional_string(value: &Value, key: &str) -> Option<String> {
    value.get(key).and_then(Value::as_str).map(str::to_owned)
}

fn safe_source_url(value: Option<&str>) -> Option<String> {
    // sourceUrl is a content-page identity, never a fallback to the transport URL.
    let mut url = Url::parse(value?).ok()?;
    if !["http", "https"].contains(&url.scheme())
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return None;
    }
    let host = url.host_str()?.to_ascii_lowercase();
    if [
        "googlevideo.com",
        "fbcdn.net",
        "cdninstagram.com",
        "twimg.com",
        "tiktokcdn.com",
        "cloudfront.net",
        "akamaihd.net",
        "akamaized.net",
    ]
    .iter()
    .any(|domain| host == *domain || host.ends_with(&format!(".{domain}")))
        || host.starts_with("cdn.")
    {
        return None;
    }
    let path = url.path().to_ascii_lowercase();
    if [".mp4", ".webm", ".m3u8", ".mp3", ".m4a", ".mov", ".ts"]
        .iter()
        .any(|ext| path.ends_with(ext))
    {
        return None;
    }
    // Retain only known content identifiers; arbitrary queries may be signed secrets.
    let identity: Vec<(String, String)> = url
        .query_pairs()
        .filter(|(key, value)| match host.as_str() {
            "youtube.com" | "www.youtube.com" | "m.youtube.com" | "music.youtube.com" => {
                key == "v"
                    && value.len() == 11
                    && value
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
            }
            "facebook.com" | "www.facebook.com" | "m.facebook.com" => {
                ["v", "id", "story_fbid"].contains(&key.as_ref())
                    && !value.is_empty()
                    && value.len() <= 32
                    && value.bytes().all(|b| b.is_ascii_digit())
            }
            _ => false,
        })
        .map(|(key, value)| (key.into_owned(), value.into_owned()))
        .collect();
    url.set_query(None);
    url.set_fragment(None);
    if !identity.is_empty() {
        url.query_pairs_mut().extend_pairs(identity);
    }
    Some(url.to_string())
}

fn credential(name: &str) -> Result<keyring::Entry, String> {
    keyring::Entry::new("com.mori.downloader.drive", name)
        .map_err(|_| "Windows Credential Manager is unavailable.".to_string())
}

fn read_credential<T: for<'de> Deserialize<'de>>(name: &str) -> Result<Option<T>, String> {
    match credential(name)?.get_password() {
        Ok(value) => serde_json::from_str(&value).map(Some).map_err(|_| {
            "The saved native credentials are invalid. Import them again.".to_string()
        }),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(_) => Err("Cannot read Windows Credential Manager.".into()),
    }
}

fn write_credential<T: Serialize>(name: &str, value: &T) -> Result<(), String> {
    let value = serde_json::to_string(value)
        .map_err(|_| "Cannot encode native credentials.".to_string())?;
    credential(name)?
        .set_password(&value)
        .map_err(|_| "Cannot save credentials in Windows Credential Manager.".to_string())
}

fn clear_credential(name: &str) -> Result<(), String> {
    match credential(name)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(_) => Err("Cannot clear Windows Credential Manager.".into()),
    }
}

fn config() -> Result<Config, String> {
    Ok(read_credential("config")?.unwrap_or_else(|| Config {
        client_id: DEFAULT_CLIENT_ID.into(),
        client_secret: std::env::var("MORI_DRIVE_CLIENT_SECRET")
            .ok()
            .filter(|s| !s.is_empty()),
    }))
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T, String> {
    let file = File::open(path).map_err(|_| "Cannot read native Drive metadata.".to_string())?;
    serde_json::from_reader(BufReader::new(file))
        .map_err(|_| "Native Drive metadata is invalid; staged files were retained.".to_string())
}

fn load_settings(root: &Path) -> Result<Settings, String> {
    let path = root.join("settings.json");
    if path.exists() {
        read_json(&path)
    } else {
        Ok(Settings::default())
    }
}

fn wide(value: &std::ffi::OsStr) -> Vec<u16> {
    value.encode_wide().chain(Some(0)).collect()
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let temp = path.with_extension("tmp");
    let mut file =
        File::create(&temp).map_err(|_| "Cannot write native Drive metadata.".to_string())?;
    file.write_all(bytes)
        .and_then(|_| file.sync_all())
        .map_err(|_| "Cannot persist native Drive metadata.".to_string())?;
    drop(file);
    durable_move(&temp, path)
}

fn durable_move(source: &Path, target: &Path) -> Result<(), String> {
    let source = wide(source.as_os_str());
    let target = wide(target.as_os_str());
    // Windows rename does not replace existing files; request durable replacement.
    if unsafe {
        MoveFileExW(
            source.as_ptr(),
            target.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    } == 0
    {
        return Err("Cannot commit native Drive metadata.".into());
    }
    Ok(())
}

fn atomic_json<T: Serialize>(path: &Path, value: &T) -> Result<(), String> {
    let bytes = serde_json::to_vec(value)
        .map_err(|_| "Cannot encode native Drive metadata.".to_string())?;
    atomic_write(path, &bytes)
}

fn protect(bytes: &[u8], decrypt: bool) -> Result<Vec<u8>, String> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes
            .len()
            .try_into()
            .map_err(|_| "Protected metadata is too large.".to_string())?,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        if decrypt {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptProtectData(
                &input,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    if ok == 0 {
        return Err(
            "Windows could not protect or read the upload session; staged files were retained."
                .into(),
        );
    }
    let result = if output.cbData == 0 {
        Vec::new()
    } else {
        if output.pbData.is_null() {
            return Err("Windows returned invalid protected metadata.".into());
        }
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() }
    };
    unsafe {
        LocalFree(output.pbData as *mut std::ffi::c_void);
    }
    Ok(result)
}

fn connected(settings: &Settings, auth: Option<&Auth>) -> bool {
    auth.map(|auth| {
        settings.account_id.as_deref() == Some(auth.account_id.as_str())
            && settings.folder_id.is_some()
    })
    .unwrap_or(false)
}

fn pending(job: &Job) -> Value {
    json!({"jobId": job.job_id, "fileName": job.file_name, "title": job.title,
        "sourceUrl": safe_source_url(job.source_url.as_deref()), "error": job.error})
}

fn status(root: &Path) -> Result<Value, String> {
    let settings = load_settings(root)?;
    let auth: Option<Auth> = read_credential("oauth")?;
    let connected = connected(&settings, auth.as_ref());
    let mut jobs = Vec::new();
    for entry in fs::read_dir(root.join("jobs"))
        .map_err(|_| "Cannot list pending Drive jobs.".to_string())?
    {
        let entry = entry.map_err(|_| "Cannot read pending Drive jobs.".to_string())?;
        if !entry
            .file_type()
            .map_err(|_| "Cannot inspect Drive staging.".to_string())?
            .is_dir()
        {
            continue;
        }
        let id = entry.file_name().to_string_lossy().to_string();
        let dir = job_dir(root, &id)?;
        if !dir.join("job.json").exists() {
            continue;
        }
        let mut job: Job = read_json(&dir.join("job.json"))?;
        if job.job_id != id {
            return Err(
                "A pending Drive job has invalid metadata. Its files were retained.".into(),
            );
        }
        // Older staging jobs may contain an unsanitized source URL.
        let source_url = safe_source_url(job.source_url.as_deref());
        if job.source_url != source_url {
            job.source_url = source_url;
            atomic_json(&dir.join("job.json"), &job)?;
        }
        if job.error.is_none() {
            job.error = Some(
                if job.verified {
                    "Upload verified. Local cleanup is pending."
                } else {
                    "Operation interrupted. Retry manually; staged data is retained."
                }
                .into(),
            );
        }
        jobs.push(pending(&job));
    }
    let mut completed = Vec::new();
    for entry in fs::read_dir(root.join("receipts"))
        .map_err(|_| "Cannot list completion receipts.".to_string())?
    {
        let entry = entry.map_err(|_| "Cannot read completion receipts.".to_string())?;
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) != Some("json") {
            continue;
        }
        let id = path
            .file_stem()
            .and_then(|id| id.to_str())
            .ok_or_else(|| "Invalid completion receipt filename.".to_string())?;
        let mut receipt: Receipt = read_json(&receipt_path(root, id)?)?;
        if receipt.receipt_id != id
            || !valid_drive_id(&receipt.drive_file_id)
            || receipt.uri
                != format!(
                    "https://drive.google.com/file/d/{}/view",
                    receipt.drive_file_id
                )
        {
            return Err("Invalid completion receipt identity. The receipt was retained.".into());
        }
        receipt.source_url = safe_source_url(receipt.source_url.as_deref());
        completed.push(receipt);
    }
    Ok(
        json!({"connected": connected, "enabled": connected && settings.enabled,
        "folderId": settings.folder_id, "folderName": settings.folder_name, "pending": jobs, "completed": completed}),
    )
}

fn google_client() -> Result<Client, String> {
    Client::builder()
        .https_only(true)
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(120))
        .build()
        .map_err(|_| "Cannot initialize secure Google networking.".to_string())
}

fn response_json(response: Response, operation: &str) -> Result<Value, String> {
    if !response.status().is_success() {
        // Do not echo Google bodies/URLs: they can contain credentials or session IDs.
        return Err(format!(
            "{operation} failed (HTTP {}). Staged data is retained.",
            response.status().as_u16()
        ));
    }
    response
        .json()
        .map_err(|_| format!("{operation} returned invalid metadata."))
}

fn random_bytes() -> Result<[u8; 32], String> {
    let mut bytes = [0; 32];
    OsRng
        .try_fill_bytes(&mut bytes)
        .map_err(|_| "Secure random generation failed.".to_string())?;
    Ok(bytes)
}

fn random_id() -> Result<String, String> {
    Ok(random_bytes()?.iter().map(|b| format!("{b:02x}")).collect())
}

fn open_browser(url: &Url) -> Result<(), String> {
    let verb = wide(std::ffi::OsStr::new("open"));
    let url = wide(std::ffi::OsStr::new(url.as_str()));
    // Avoid cmd.exe interpreting OAuth query-string metacharacters.
    let result = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            verb.as_ptr(),
            url.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            SW_SHOWNORMAL,
        )
    };
    if result as isize <= 32 {
        return Err("Cannot open the system browser.".into());
    }
    Ok(())
}

fn oauth_callback(listener: TcpListener, state: &str) -> Result<(String, String), String> {
    listener
        .set_nonblocking(true)
        .map_err(|_| "Cannot initialize the OAuth callback.".to_string())?;
    let deadline = Instant::now() + Duration::from_secs(180);
    while Instant::now() < deadline {
        match listener.accept() {
            Ok((mut stream, peer)) => {
                if !peer.ip().is_loopback() {
                    continue;
                }
                let remaining = deadline
                    .saturating_duration_since(Instant::now())
                    .min(Duration::from_secs(2));
                let _ = stream.set_read_timeout(Some(remaining));
                let _ = stream.set_write_timeout(Some(remaining));
                let mut line = Vec::new();
                // Check the absolute deadline even if a local client trickles bytes.
                while line.len() < 16 * 1024 && Instant::now() < deadline {
                    let remaining = deadline
                        .saturating_duration_since(Instant::now())
                        .min(Duration::from_secs(2))
                        .max(Duration::from_millis(1));
                    let _ = stream.set_read_timeout(Some(remaining));
                    let mut byte = [0];
                    if stream.read(&mut byte).ok() != Some(1) {
                        break;
                    }
                    line.push(byte[0]);
                    if byte[0] == b'\n' {
                        break;
                    }
                }
                if line.last() != Some(&b'\n') {
                    continue;
                }
                let Ok(line) = String::from_utf8(line) else {
                    continue;
                };
                let parts: Vec<_> = line.split_whitespace().collect();
                let parsed = if parts.len() == 3
                    && parts[0] == "GET"
                    && parts[1].starts_with("/oauth2callback?")
                {
                    Url::parse(&format!("http://127.0.0.1{}", parts[1])).ok()
                } else {
                    None
                };
                let Some(url) = parsed else {
                    continue;
                };
                let values: Vec<(String, String)> = url.query_pairs().into_owned().collect();
                let one = |key: &str| -> Option<String> {
                    let matches: Vec<_> = values.iter().filter(|(k, _)| k == key).collect();
                    if matches.len() == 1 {
                        Some(matches[0].1.clone())
                    } else {
                        None
                    }
                };
                if one("state").as_deref() != Some(state) {
                    let _ = stream.write_all(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 16\r\n\r\nInvalid request.");
                    continue;
                }
                let body =
                    "Host-ia received the Google response. You can close this tab and return to Host-ia.";
                let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{}", body.len(), body);
                if one("error").is_some() {
                    return Err("Google authorization was denied or cancelled.".into());
                }
                let code = one("code")
                    .filter(|s| !s.is_empty())
                    .ok_or_else(|| "Google did not return an authorization code.".to_string())?;
                let folder = one("picked_file_ids")
                    .filter(|s| valid_drive_id(s))
                    .ok_or_else(|| "Select exactly one Google Drive folder.".to_string())?;
                return Ok((code, folder));
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(50))
            }
            Err(_) => return Err("The Google callback listener failed.".into()),
        }
    }
    Err("Google authorization timed out after 180 seconds. Try connecting again.".into())
}

fn connect(root: &Path) -> Result<Value, String> {
    let previous = load_settings(root)?;
    let config = config()?;
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .map_err(|_| "Cannot open a local OAuth callback port.".to_string())?;
    let port = listener
        .local_addr()
        .map_err(|_| "Cannot read the OAuth callback port.".to_string())?
        .port();
    let redirect = format!("http://127.0.0.1:{port}/oauth2callback");
    let verifier = URL_SAFE_NO_PAD.encode(random_bytes()?);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let state = URL_SAFE_NO_PAD.encode(random_bytes()?);
    let mut url = Url::parse("https://accounts.google.com/o/oauth2/v2/auth")
        .map_err(|_| "Invalid OAuth endpoint.".to_string())?;
    url.query_pairs_mut().extend_pairs([
        ("client_id", config.client_id.as_str()),
        ("redirect_uri", redirect.as_str()),
        ("response_type", "code"),
        ("scope", SCOPE),
        ("access_type", "offline"),
        ("prompt", "consent"),
        ("trigger_onepick", "true"),
        ("mimetypes", FOLDER_MIME),
        ("allow_folder_selection", "true"),
        ("code_challenge", challenge.as_str()),
        ("code_challenge_method", "S256"),
        ("state", state.as_str()),
        ("include_granted_scopes", "false"),
    ]);
    open_browser(&url)?;
    let (code, folder_id) = oauth_callback(listener, &state)?;
    let client = google_client()?;
    let mut form = vec![
        ("client_id", config.client_id.as_str()),
        ("code", code.as_str()),
        ("redirect_uri", redirect.as_str()),
        ("grant_type", "authorization_code"),
        ("code_verifier", verifier.as_str()),
    ];
    if let Some(secret) = &config.client_secret {
        form.push(("client_secret", secret));
    }
    let token = response_json(client.post("https://oauth2.googleapis.com/token").form(&form).send()
        .map_err(|_| "Secure Google token exchange failed. Import rotated Desktop credentials if required.".to_string())?, "Google authorization")?;
    validate_scope(&token)?;
    let access = required_string(&token, "access_token")?;
    let refresh_token = token
        .get("refresh_token")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            "Google did not grant offline access. Reconnect and consent again.".to_string()
        })?
        .to_owned();
    let account_id = account(&client, &access)?;
    let folder = get_file(
        &client,
        &access,
        &folder_id,
        "id,name,mimeType,trashed,capabilities(canAddChildren)",
    )?
    .ok_or_else(|| "The selected Google folder is not accessible.".to_string())?;
    validate_folder(&folder, &folder_id)?;
    let folder_name = required_string(&folder, "name")?;
    write_credential(
        "oauth",
        &Auth {
            refresh_token,
            account_id: account_id.clone(),
            client_id: config.client_id,
        },
    )?;
    atomic_json(
        &root.join("settings.json"),
        &Settings {
            enabled: previous.enabled,
            folder_id: Some(folder_id),
            folder_name: Some(folder_name),
            account_id: Some(account_id),
        },
    )?;
    status(root)
}

fn validate_scope(token: &Value) -> Result<(), String> {
    if let Some(scope) = token.get("scope").and_then(Value::as_str) {
        let scopes: Vec<_> = scope.split_whitespace().collect();
        if scopes != [SCOPE] {
            return Err(
                "Google returned unexpected permissions. Only drive.file is allowed.".into(),
            );
        }
    }
    Ok(())
}

fn access_token(client: &Client, auth: &Auth) -> Result<String, String> {
    let config = config()?;
    if config.client_id != auth.client_id {
        return Err("Google Desktop credentials changed. Reconnect first.".into());
    }
    let mut form = vec![
        ("client_id", config.client_id.as_str()),
        ("refresh_token", auth.refresh_token.as_str()),
        ("grant_type", "refresh_token"),
    ];
    if let Some(secret) = &config.client_secret {
        form.push(("client_secret", secret));
    }
    let value = response_json(
        client
            .post("https://oauth2.googleapis.com/token")
            .form(&form)
            .send()
            .map_err(|_| "Cannot refresh Google authorization. Reconnect if needed.".to_string())?,
        "Google token refresh",
    )?;
    validate_scope(&value)?;
    required_string(&value, "access_token")
}

fn account(client: &Client, token: &str) -> Result<String, String> {
    let value = response_json(
        client
            .get(format!("{API}/about"))
            .query(&[("fields", "user(permissionId)")])
            .bearer_auth(token)
            .send()
            .map_err(|_| "Cannot verify the Google account.".to_string())?,
        "Google account verification",
    )?;
    required_string(&value["user"], "permissionId")
}

fn valid_drive_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 256
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

fn get_file(client: &Client, token: &str, id: &str, fields: &str) -> Result<Option<Value>, String> {
    if !valid_drive_id(id) {
        return Err("Invalid Drive file ID.".into());
    }
    let response = client
        .get(format!("{API}/files/{id}"))
        .query(&[("fields", fields), ("supportsAllDrives", "true")])
        .bearer_auth(token)
        .send()
        .map_err(|_| "Cannot read Google Drive metadata. Staged data is retained.".to_string())?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(None);
    }
    response_json(response, "Google Drive metadata").map(Some)
}

fn validate_folder(folder: &Value, id: &str) -> Result<(), String> {
    if folder["id"].as_str() != Some(id)
        || folder["mimeType"].as_str() != Some(FOLDER_MIME)
        || folder["trashed"].as_bool() != Some(false)
        || folder["capabilities"]["canAddChildren"].as_bool() != Some(true)
    {
        return Err("Choose an untrashed Google Drive folder where you can add files.".into());
    }
    Ok(())
}

fn safe_file_name(name: &str) -> Result<String, String> {
    if name.is_empty()
        || name.len() > 240
        || name.trim() != name
        || name.ends_with('.')
        || name
            .chars()
            .any(|c| c.is_control() || "<>:\"/\\|?*".contains(c))
    {
        return Err("fileName must be a safe filename, not a path.".into());
    }
    let stem = name
        .split('.')
        .next()
        .unwrap_or("")
        .trim_end()
        .to_ascii_uppercase();
    if ["", "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"].contains(&stem.as_str())
        || (stem.len() == 4
            && (stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.as_bytes()[3].is_ascii_digit())
    {
        return Err("fileName is reserved on Windows.".into());
    }
    Ok(name.to_owned())
}

fn validate_local_id(id: &str) -> Result<(), String> {
    if id.len() != 64
        || !id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err("Invalid native job or receipt ID.".into());
    }
    Ok(())
}

fn receipt_path(root: &Path, id: &str) -> Result<PathBuf, String> {
    validate_local_id(id)?;
    let path = root.join("receipts").join(format!("{id}.json"));
    match fs::symlink_metadata(&path) {
        Ok(metadata) if !metadata.file_type().is_file() => {
            return Err("Completion receipts must be regular files.".into())
        }
        Ok(_) => (),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
        Err(_) => return Err("Cannot inspect the completion receipt.".into()),
    }
    Ok(path)
}

fn job_dir(root: &Path, id: &str) -> Result<PathBuf, String> {
    validate_local_id(id)?;
    let dir = root.join("jobs").join(id);
    if dir.exists()
        && fs::symlink_metadata(&dir)
            .map_err(|_| "Cannot inspect the staging job.".to_string())?
            .file_type()
            .is_symlink()
    {
        return Err("Staging jobs cannot be symbolic links.".into());
    }
    Ok(dir)
}

fn stage(root: &Path, action: &str, options: &Value) -> Result<Value, String> {
    let settings = load_settings(root)?;
    let auth: Auth =
        read_credential("oauth")?.ok_or_else(|| "Connect Google Drive first.".to_string())?;
    if !settings.enabled || !connected(&settings, Some(&auth)) {
        return Err("Google Drive downloads are disabled or disconnected.".into());
    }
    let file_name = safe_file_name(&required_string(options, "fileName")?)?;
    let id = random_id()?;
    let dir = job_dir(root, &id)?;
    fs::create_dir(&dir).map_err(|_| "Cannot create a unique durable staging job.".to_string())?;
    let mut job = Job {
        job_id: id,
        file_name,
        title: optional_string(options, "title"),
        source_url: safe_source_url(options.get("sourceUrl").and_then(Value::as_str)),
        folder_id: settings
            .folder_id
            .ok_or_else(|| "Select a Drive folder first.".to_string())?,
        account_id: auth.account_id,
        ready: false,
        size: None,
        md5: None,
        drive_file_id: None,
        verified: false,
        error: None,
    };
    atomic_json(&dir.join("job.json"), &job)?;
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(dir.join("payload.part"))
            .map_err(|_| "Cannot create the staging file.".to_string())?;
        if action == "saveBytes" {
            let data = options
                .get("data")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
                .ok_or_else(|| "data must be nonempty standard base64 bytes.".to_string())?;
            let mut decoded = base64::read::DecoderReader::new(data.as_bytes(), &STANDARD);
            std::io::copy(&mut decoded, &mut file).map_err(|_| {
                "Cannot stage document bytes; check base64 data and available disk space."
                    .to_string()
            })?;
        } else {
            download(options, &mut file)?;
        }
        file.sync_all()
            .map_err(|_| "Cannot durably finish the staging file.".to_string())?;
        drop(file);
        durable_move(&dir.join("payload.part"), &dir.join("payload.bin"))?;
        prepare_payload(&dir, &mut job)?;
        upload(root, &dir, &mut job)
    })();
    finish_job(&dir, &mut job, result)
}

fn download(options: &Value, file: &mut File) -> Result<(), String> {
    let url = Url::parse(&required_string(options, "url")?)
        .map_err(|_| "Invalid download URL.".to_string())?;
    if !["http", "https"].contains(&url.scheme())
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("Downloads require an HTTP(S) URL without embedded credentials.".into());
    }
    // The source client never receives OAuth credentials and has no curl fallback.
    let client = Client::builder()
        .redirect(reqwest::redirect::Policy::limited(10))
        .connect_timeout(Duration::from_secs(30))
        .timeout(Duration::from_secs(3600))
        .user_agent("Host-ia/4.4.1")
        .build()
        .map_err(|_| "Cannot initialize native download networking.".to_string())?;
    let mut request = client.get(url);
    if let Some(headers) = options.get("headers").filter(|v| !v.is_null()) {
        let headers = headers
            .as_object()
            .ok_or_else(|| "headers must be an object of strings.".to_string())?;
        for (name, value) in headers {
            let value = value
                .as_str()
                .ok_or_else(|| "Download header values must be strings.".to_string())?;
            request = request.header(name, value);
        }
    }
    let mut response = request
        .send()
        .map_err(|_| "Native download failed. Any partial staging data is retained.".to_string())?;
    if response.status() != reqwest::StatusCode::OK {
        return Err(format!(
            "Native download failed (HTTP {}); a complete file is required.",
            response.status().as_u16()
        ));
    }
    let expected = response.content_length();
    let count = std::io::copy(&mut response, file).map_err(|_| {
        "Download interrupted. The partial file was retained; it will not be uploaded.".to_string()
    })?;
    if expected.is_some_and(|expected| expected != count) {
        return Err("Downloaded size does not match the source. Partial data was retained.".into());
    }
    Ok(())
}

fn hash_file(path: &Path) -> Result<(u64, String), String> {
    let mut file = File::open(path)
        .map_err(|_| "The staged file is unavailable. No new upload was started.".to_string())?;
    let mut hash = Md5::new();
    let mut buffer = [0u8; 64 * 1024];
    let mut size = 0u64;
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| "Cannot verify the staged file.".to_string())?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
        size += count as u64;
    }
    Ok((size, format!("{:x}", hash.finalize())))
}

fn prepare_payload(dir: &Path, job: &mut Job) -> Result<(), String> {
    let (size, md5) = hash_file(&dir.join("payload.bin"))?;
    job.size = Some(size);
    job.md5 = Some(md5);
    job.ready = true;
    atomic_json(&dir.join("job.json"), job)
}

fn verify_payload(size: u64, md5: &str, job: &Job) -> Result<(), String> {
    if job.size != Some(size) || job.md5.as_deref() != Some(md5) {
        return Err(
            "The staged file changed. It was retained and will not be uploaded or deleted.".into(),
        );
    }
    Ok(())
}

fn verify_file(metadata: &Value, job: &Job) -> Result<(), String> {
    let parents = metadata.get("parents").and_then(Value::as_array);
    if metadata["id"].as_str() != job.drive_file_id.as_deref()
        || metadata["trashed"].as_bool() != Some(false)
        || metadata["size"]
            .as_str()
            .and_then(|s| s.parse::<u64>().ok())
            != job.size
        || metadata["md5Checksum"].as_str() != job.md5.as_deref()
        || !parents.is_some_and(|p| p.len() == 1 && p[0].as_str() == Some(job.folder_id.as_str()))
        || job.drive_file_id.is_none()
        || job.size.is_none()
        || job.md5.is_none()
    {
        return Err("Drive file ID, size, MD5, folder, or trash state could not be verified. The local file was retained.".into());
    }
    Ok(())
}

fn validate_session(value: &str) -> Result<Url, String> {
    let url = Url::parse(value).map_err(|_| "Invalid protected upload session.".to_string())?;
    if url.scheme() != "https"
        || url.host_str() != Some("www.googleapis.com")
        || url.port_or_known_default() != Some(443)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || url.path() != "/upload/drive/v3/files"
        || !url
            .query_pairs()
            .any(|(k, v)| k == "upload_id" && !v.is_empty())
    {
        return Err(
            "Rejected an untrusted upload-session destination. The local file was retained.".into(),
        );
    }
    Ok(url)
}

fn save_session(dir: &Path, job: &Job, value: &str) -> Result<(), String> {
    validate_session(value)?;
    let session = json!({"jobId": job.job_id, "driveFileId": job.drive_file_id,
        "folderId": job.folder_id, "accountId": job.account_id, "url": value});
    let bytes = serde_json::to_vec(&session)
        .map_err(|_| "Cannot encode the upload session.".to_string())?;
    atomic_write(&dir.join("session.dpapi"), &protect(&bytes, false)?)
}

fn load_session(dir: &Path, job: &Job) -> Result<Option<Url>, String> {
    let path = dir.join("session.dpapi");
    if !path.exists() {
        return Ok(None);
    }
    let bytes =
        fs::read(path).map_err(|_| "Cannot read the protected upload session.".to_string())?;
    let session: Value = serde_json::from_slice(&protect(&bytes, true)?)
        .map_err(|_| "Invalid protected upload session.".to_string())?;
    if session["jobId"].as_str() != Some(job.job_id.as_str())
        || session["driveFileId"].as_str() != job.drive_file_id.as_deref()
        || session["folderId"].as_str() != Some(job.folder_id.as_str())
        || session["accountId"].as_str() != Some(job.account_id.as_str())
    {
        return Err(
            "The protected session belongs to another staging target. The local file was retained."
                .into(),
        );
    }
    validate_session(&required_string(&session, "url")?).map(Some)
}

fn uploaded_offset(response: &Response, total: u64) -> Result<u64, String> {
    let Some(range) = response.headers().get(reqwest::header::RANGE) else {
        return Ok(0);
    };
    let range = range
        .to_str()
        .map_err(|_| "Invalid resumable upload range.".to_string())?;
    let last = range
        .strip_prefix("bytes=0-")
        .and_then(|s| s.parse::<u64>().ok())
        .ok_or_else(|| "Invalid resumable upload range.".to_string())?;
    let offset = last
        .checked_add(1)
        .filter(|n| *n <= total)
        .ok_or_else(|| "Google returned an impossible upload offset.".to_string())?;
    Ok(offset)
}

fn upload(root: &Path, dir: &Path, job: &mut Job) -> Result<Value, String> {
    let auth: Auth = read_credential("oauth")?
        .ok_or_else(|| "Reconnect the original Google account before retrying.".to_string())?;
    if auth.account_id != job.account_id {
        return Err("This job belongs to a different Google account. Reconnect its original account to retry; its folder will not be changed.".into());
    }
    let client = google_client()?;
    let token = access_token(&client, &auth)?;
    if account(&client, &token)? != job.account_id {
        return Err(
            "Google account verification changed. No upload or deletion was performed.".into(),
        );
    }
    let payload = dir.join("payload.bin");
    if job.verified {
        let id = job
            .drive_file_id
            .as_deref()
            .ok_or_else(|| "Verified job is missing its Drive file ID.".to_string())?;
        let metadata = get_file(&client, &token, id, FILE_FIELDS)?.ok_or_else(|| {
            "The verified Drive copy is no longer accessible. Local data was retained.".to_string()
        })?;
        verify_file(&metadata, job)?;
        // A cleanup retry never initiates another upload, even if local data is gone.
        return cleanup(root, dir, job);
    }
    if !job.ready {
        if !payload.exists() {
            return Err("The source download was not completed. Partial data is retained; retry cannot upload it. Start a new download from the source.".into());
        }
        prepare_payload(dir, job)?;
    }
    let (size, md5) = hash_file(&payload)?;
    verify_payload(size, &md5, job)?;
    let folder = get_file(
        &client,
        &token,
        &job.folder_id,
        "id,name,mimeType,trashed,capabilities(canAddChildren)",
    )?
    .ok_or_else(|| "The job's original Drive folder is no longer accessible.".to_string())?;
    validate_folder(&folder, &job.folder_id)?;

    if job.drive_file_id.is_none() {
        let ids = response_json(
            client
                .get(format!("{API}/files/generateIds"))
                .query(&[("count", "1"), ("space", "drive"), ("type", "files")])
                .bearer_auth(&token)
                .send()
                .map_err(|_| "Cannot reserve a Drive file ID.".to_string())?,
            "Drive ID reservation",
        )?;
        let id = ids["ids"][0]
            .as_str()
            .filter(|id| valid_drive_id(id))
            .ok_or_else(|| "Google did not return a valid reserved file ID.".to_string())?;
        job.drive_file_id = Some(id.to_owned());
        atomic_json(&dir.join("job.json"), job)?;
    }
    let id = job
        .drive_file_id
        .clone()
        .ok_or_else(|| "Missing reserved Drive file ID.".to_string())?;
    if let Some(metadata) = get_file(&client, &token, &id, FILE_FIELDS)? {
        verify_file(&metadata, job)?;
        job.verified = true;
        atomic_json(&dir.join("job.json"), job)?;
        return cleanup(root, dir, job);
    }

    let mut session = load_session(dir, job)?;
    let mut offset = 0;
    if let Some(url) = &session {
        let response = client
            .put(url.clone())
            .bearer_auth(&token)
            .header(reqwest::header::CONTENT_LENGTH, "0")
            .header(reqwest::header::CONTENT_RANGE, format!("bytes */{size}"))
            .send()
            .map_err(|_| "Upload session probe failed. Retry manually to resume.".to_string())?;
        match response.status().as_u16() {
            200 | 201 => {
                return verify_and_cleanup(
                    &client,
                    &token,
                    root,
                    dir,
                    job,
                    response_json(response, "Upload session verification")?,
                )
            }
            308 => offset = uploaded_offset(&response, size)?,
            404 | 410 => session = None,
            code => {
                return Err(format!(
                    "Upload session probe failed (HTTP {code}). Staged data is retained."
                ))
            }
        }
    }
    if session.is_none() {
        // A durable, preallocated ID makes uncertain create responses safe to retry.
        let metadata = json!({"id": id, "name": job.file_name, "parents": [job.folder_id]});
        let response = client
            .post("https://www.googleapis.com/upload/drive/v3/files")
            .query(&[
                ("uploadType", "resumable"),
                ("supportsAllDrives", "true"),
                ("fields", FILE_FIELDS),
            ])
            .bearer_auth(&token)
            .header("X-Upload-Content-Type", mime_type(&job.file_name))
            .header("X-Upload-Content-Length", size.to_string())
            .json(&metadata)
            .send()
            .map_err(|_| {
                "Could not start a resumable upload. The reserved ID is retained for safe retry."
                    .to_string()
            })?;
        if !response.status().is_success() {
            return Err(format!("Resumable upload initialization failed (HTTP {}). Retry manually; no alternate copy will be created.", response.status().as_u16()));
        }
        let location = response
            .headers()
            .get(reqwest::header::LOCATION)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| "Google did not return a resumable upload session.".to_string())?;
        save_session(dir, job, location)?;
        session = Some(validate_session(location)?);
    }
    let session = session.ok_or_else(|| "No protected upload session is available.".to_string())?;
    let mut file =
        File::open(&payload).map_err(|_| "Cannot open the staged upload.".to_string())?;
    // One bounded chunk is held in memory, regardless of media file size.
    while offset < size || size == 0 {
        let count = (size - offset).min(CHUNK_SIZE as u64) as usize;
        file.seek(SeekFrom::Start(offset))
            .map_err(|_| "Cannot seek within the staged upload.".to_string())?;
        let mut bytes = vec![0; count];
        file.read_exact(&mut bytes)
            .map_err(|_| "Cannot read a complete upload chunk.".to_string())?;
        let range = if size == 0 {
            "bytes */0".to_owned()
        } else {
            format!("bytes {}-{}/{size}", offset, offset + count as u64 - 1)
        };
        let response = client.put(session.clone()).bearer_auth(&token)
            .header(reqwest::header::CONTENT_TYPE, mime_type(&job.file_name))
            .header(reqwest::header::CONTENT_LENGTH, count.to_string()).header(reqwest::header::CONTENT_RANGE, range)
            .body(bytes).send().map_err(|_| "Upload interrupted. The completed local file and resumable session were retained. Retry manually.".to_string())?;
        match response.status().as_u16() {
            200 | 201 => {
                drop(file);
                return verify_and_cleanup(&client, &token, root, dir, job, response_json(response, "Drive upload")?);
            }
            308 => {
                let next = uploaded_offset(&response, size)?;
                if next <= offset || next > offset + count as u64 { return Err("Google did not confirm forward upload progress. The local file was retained.".into()); }
                offset = next;
            }
            code => return Err(format!("Drive upload failed (HTTP {code}). The completed local file and session were retained for manual retry.")),
        }
    }
    // Even a 308 confirming all bytes is not enough to delete the local copy.
    Err("Google has not confirmed final upload metadata. Retry manually to verify the reserved file.".into())
}

fn mime_type(name: &str) -> &'static str {
    match name
        .rsplit('.')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "pdf" => "application/pdf",
        "mp4" => "video/mp4",
        "webm" => "video/webm",
        "mp3" => "audio/mpeg",
        "m4a" => "audio/mp4",
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        _ => "application/octet-stream",
    }
}

fn verify_and_cleanup(
    client: &Client,
    token: &str,
    root: &Path,
    dir: &Path,
    job: &mut Job,
    returned: Value,
) -> Result<Value, String> {
    verify_file(&returned, job)?;
    let id = job
        .drive_file_id
        .as_deref()
        .ok_or_else(|| "Missing upload ID.".to_string())?;
    let metadata = get_file(client, token, id, FILE_FIELDS)?.ok_or_else(|| {
        "Cannot independently verify the uploaded Drive file. Local data is retained.".to_string()
    })?;
    verify_file(&metadata, job)?;
    let (size, md5) = hash_file(&dir.join("payload.bin"))?;
    verify_payload(size, &md5, job)?;
    job.verified = true;
    job.error = None;
    atomic_json(&dir.join("job.json"), job)?;
    cleanup(root, dir, job)
}

fn cleanup(root: &Path, dir: &Path, job: &mut Job) -> Result<Value, String> {
    if !job.verified {
        return Err("Refusing cleanup before Drive verification.".into());
    }
    let mut receipt = Receipt::new(job, false)?;
    let path = receipt_path(root, &receipt.receipt_id)?;
    // Persist delivery intent before any local cleanup, including pending cleanup.
    atomic_json(&path, &receipt)?;
    let mut result = serde_json::to_value(&receipt)
        .map_err(|_| "Cannot encode the completion receipt.".to_string())?;
    let payload = dir.join("payload.bin");
    let deletion = (|| {
        match fs::symlink_metadata(&payload) {
            Ok(metadata) => {
                if !metadata.file_type().is_file() {
                    return Err(
                        "The staged payload is not a regular file. It was retained.".to_string()
                    );
                }
                let (size, md5) = hash_file(&payload)?;
                verify_payload(size, &md5, job)?;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(_) => {
                return Err("Cannot inspect the local payload for verified cleanup.".to_string())
            }
        }
        fs::remove_file(&payload).map_err(|_| "Drive upload verified, but Windows could not delete the local staging file. Retry only performs verified cleanup.".to_string())
    })();
    if let Err(error) = deletion {
        job.error = Some(error);
        atomic_json(&dir.join("job.json"), job)?;
        result["pending"] = pending(job);
        return Ok(result);
    }
    // A durable success receipt must outlive removal of the staging job metadata.
    receipt.local_deleted = true;
    atomic_json(&path, &receipt)?;
    // Delete only our known metadata, never recursively delete unverified data.
    let metadata_cleanup = (|| -> std::io::Result<()> {
        for name in ["session.dpapi", "session.tmp", "job.tmp"] {
            match fs::remove_file(dir.join(name)) {
                Ok(()) => (),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
                Err(error) => return Err(error),
            }
        }
        fs::remove_file(dir.join("job.json"))?;
        fs::remove_dir(dir)
    })();
    if metadata_cleanup.is_err() {
        job.error = Some("Drive upload verified and local payload deleted, but staging metadata cleanup failed. Retry cleanup.".into());
        atomic_json(&dir.join("job.json"), job)?;
        receipt.local_deleted = false;
        atomic_json(&path, &receipt)?;
        result["pending"] = pending(job);
        return Ok(result);
    }
    result["localDeleted"] = json!(true);
    Ok(result)
}

fn finish_job(dir: &Path, job: &mut Job, result: Result<Value, String>) -> Result<Value, String> {
    match result {
        Ok(value) => Ok(value),
        Err(error) => {
            job.error = Some(error.clone());
            if atomic_json(&dir.join("job.json"), job).is_err() {
                return Err(format!(
                    "{error} Job {} was retained, but its latest error could not be persisted.",
                    job.job_id
                ));
            }
            Err(format!("{error} Pending job: {}.", job.job_id))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job() -> Job {
        Job {
            job_id: "a".repeat(64),
            file_name: "test.pdf".into(),
            title: None,
            source_url: None,
            folder_id: "folder_1".into(),
            account_id: "account".into(),
            ready: true,
            size: Some(12),
            md5: Some("1234".into()),
            drive_file_id: Some("file_1".into()),
            verified: false,
            error: None,
        }
    }

    #[test]
    fn file_names_cannot_escape_staging() {
        for name in [
            "../test.pdf",
            "..\\test.pdf",
            "C:\\test.pdf",
            "C:test.pdf",
            "/test.pdf",
            "CON.pdf",
            "NUL",
            "LPT1.txt",
            ".",
            "..",
            "a.",
            "a ",
            "a\0.pdf",
            "a:b.pdf",
        ] {
            assert!(safe_file_name(name).is_err(), "{name:?}");
        }
        assert_eq!(safe_file_name("My movie.mp4").unwrap(), "My movie.mp4");
    }

    #[test]
    fn job_ids_are_not_paths() {
        let root = Path::new("C:\\staging");
        assert_eq!(
            job_dir(root, &"a".repeat(64)).unwrap(),
            root.join("jobs").join("a".repeat(64))
        );
        for id in ["../job", "..\\job", "C:\\job", "", "ABC"] {
            assert!(job_dir(root, id).is_err());
        }
    }

    #[test]
    fn session_destinations_are_strictly_google() {
        assert!(
            validate_session("https://www.googleapis.com/upload/drive/v3/files?upload_id=abc")
                .is_ok()
        );
        for url in [
            "http://www.googleapis.com/upload/drive/v3/files?upload_id=a",
            "https://www.googleapis.com.evil.test/upload/drive/v3/files?upload_id=a",
            "https://evil.test/upload/drive/v3/files?upload_id=a",
            "https://user@www.googleapis.com/upload/drive/v3/files?upload_id=a",
            "https://www.googleapis.com:444/upload/drive/v3/files?upload_id=a",
            "https://www.googleapis.com/other?upload_id=a",
            "https://www.googleapis.com/upload/drive/v3/files",
            "https://www.googleapis.com/upload/drive/v3/files?upload_id=a#fragment",
        ] {
            assert!(validate_session(url).is_err(), "{url}");
        }
    }

    #[test]
    fn deletion_requires_all_verification_fields() {
        let mut job = job();
        let good = json!({"id":"file_1", "size":"12", "md5Checksum":"1234", "parents":["folder_1"], "trashed":false});
        for verified in [false, true] {
            job.verified = verified;
            assert!(verify_file(&good, &job).is_ok());
            for field in ["id", "size", "md5Checksum", "parents", "trashed"] {
                let mut missing = good.clone();
                missing.as_object_mut().unwrap().remove(field);
                assert!(verify_file(&missing, &job).is_err());
            }
            for (field, wrong) in [
                ("id", json!("other")),
                ("size", json!("13")),
                ("md5Checksum", json!("different")),
                ("parents", json!(["other"])),
                ("trashed", json!(true)),
            ] {
                let mut metadata = good.clone();
                metadata[field] = wrong;
                assert!(verify_file(&metadata, &job).is_err());
            }
        }
    }

    #[test]
    fn verified_jobs_still_require_matching_local_bytes() {
        let mut job = job();
        for verified in [false, true] {
            job.verified = verified;
            assert!(verify_payload(12, "1234", &job).is_ok());
            assert!(verify_payload(13, "1234", &job).is_err());
            assert!(verify_payload(12, "changed", &job).is_err());
        }
        job.size = None;
        assert!(verify_payload(12, "1234", &job).is_err());
        job.size = Some(12);
        job.md5 = None;
        assert!(verify_payload(12, "1234", &job).is_err());
    }

    #[test]
    fn receipts_have_only_public_camel_case_delivery_fields() {
        let mut job = job();
        assert!(Receipt::new(&job, false).is_err());
        job.verified = true;
        job.source_url =
            Some("https://www.youtube.com/watch?v=abcdefghijk&signature=redacted#fragment".into());
        job.title = Some("Document".into());
        for local_deleted in [false, true] {
            let receipt = Receipt::new(&job, local_deleted).unwrap();
            let value = serde_json::to_value(&receipt).unwrap();
            assert_eq!(
                value,
                json!({
                    "receiptId": job.job_id, "sourceUrl": "https://www.youtube.com/watch?v=abcdefghijk",
                    "title": "Document", "driveFileId": "file_1", "uri": "https://drive.google.com/file/d/file_1/view",
                    "fileName": "test.pdf", "localDeleted": local_deleted,
                })
            );
            let restored: Receipt = serde_json::from_value(value.clone()).unwrap();
            assert_eq!(serde_json::to_value(restored).unwrap(), value);
        }
        job.source_url = None;
        job.title = None;
        let value = serde_json::to_value(Receipt::new(&job, true).unwrap()).unwrap();
        assert!(value["sourceUrl"].is_null());
        assert!(value["title"].is_null());
        job.drive_file_id = Some("../file".into());
        assert!(Receipt::new(&job, true).is_err());
    }

    #[test]
    fn receipt_ids_cannot_escape_completion_storage() {
        let root = Path::new("C:\\staging");
        let id = "a".repeat(64);
        assert_eq!(
            receipt_path(root, &id).unwrap(),
            root.join("receipts").join(format!("{id}.json"))
        );
        for id in ["../job", "..\\job", "C:\\job", "", "ABC", "receipt.json"] {
            assert!(receipt_path(root, id).is_err());
        }
    }

    #[test]
    fn source_identities_drop_sensitive_queries_and_transport_urls() {
        for (input, expected) in [
            (
                "https://example.test/item?id=42&signature=redacted#fragment",
                "https://example.test/item",
            ),
            (
                "https://www.youtube.com/watch?v=abcdefghijk&access_token=redacted",
                "https://www.youtube.com/watch?v=abcdefghijk",
            ),
            (
                "https://www.facebook.com/watch/?v=123456&token=redacted",
                "https://www.facebook.com/watch/?v=123456",
            ),
        ] {
            assert_eq!(safe_source_url(Some(input)).as_deref(), Some(expected));
        }
        for input in [
            "file:///C:/private.pdf",
            "not a URL",
            "https://user:placeholder@example.test/item",
            "https://video.googlevideo.com/videoplayback?signature=redacted",
            "https://cdn.example.test/item?signature=redacted",
            "https://example.test/video.mp4?signature=redacted",
        ] {
            assert!(safe_source_url(Some(input)).is_none());
        }
        assert!(safe_source_url(None).is_none());
    }

    #[test]
    fn folder_must_be_explicitly_writable_and_untrashed() {
        let good = json!({"id":"folder", "mimeType":FOLDER_MIME, "trashed":false, "capabilities":{"canAddChildren":true}});
        assert!(validate_folder(&good, "folder").is_ok());
        let mut read_only = good.clone();
        read_only["capabilities"]["canAddChildren"] = json!(false);
        assert!(validate_folder(&read_only, "folder").is_err());
        assert!(validate_folder(&good, "other").is_err());
    }

    #[test]
    fn only_drive_file_scope_is_allowed() {
        assert!(validate_scope(&json!({"scope":SCOPE})).is_ok());
        assert!(validate_scope(&json!({"scope":format!("{SCOPE} openid")})).is_err());
        assert!(validate_scope(&json!({"scope":"https://www.googleapis.com/auth/drive"})).is_err());
    }
}
