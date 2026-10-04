use std::collections::HashMap;

#[cfg(windows)]
mod drive;

#[tauri::command]
async fn tauri_drive_action(
    action: String,
    options: Option<serde_json::Value>,
    app: tauri::AppHandle,
) -> Result<serde_json::Value, String> {
    #[cfg(windows)]
    {
        tauri::async_runtime::spawn_blocking(move || drive::action(&action, options, &app))
            .await
            .map_err(|_| "The native Drive operation could not finish.".to_string())?
    }
    #[cfg(not(windows))]
    {
        let _ = (action, options, app);
        Err("Native Drive downloads are only available on Windows in this build.".to_string())
    }
}

#[tauri::command]
async fn tauri_http_request(
    url: String,
    method: Option<String>,
    headers: Option<HashMap<String, String>>,
    body: Option<String>,
) -> Result<serde_json::Value, String> {
    let client = reqwest::Client::builder()
        .http1_only()
        .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .redirect(reqwest::redirect::Policy::limited(10))
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())?;

    let m = method.unwrap_or_else(|| "GET".to_string()).to_uppercase();
    let mut req = match m.as_str() {
        "POST" => client.post(&url),
        "PUT" => client.put(&url),
        "DELETE" => client.delete(&url),
        _ => client.get(&url),
    };

    if let Some(hdrs) = &headers {
        for (k, v) in hdrs {
            req = req.header(k, v);
        }
    }

    if let Some(b) = &body {
        req = req.body(b.clone());
    }

    let (status, text, res_headers, final_url) = match req.send().await {
        Ok(res) => {
            let u = res.url().to_string();
            let s = res.status().as_u16();
            let mut hdrs = HashMap::new();
            for (k, v) in res.headers() {
                if let Ok(v_str) = v.to_str() {
                    hdrs.insert(k.as_str().to_string(), v_str.to_string());
                }
            }
            let t = res.text().await.unwrap_or_default();
            (s, t, hdrs, u)
        }
        Err(_) => {
            // Reqwest network error, attempt curl fallback
            if let Ok(curl_res) = run_curl_fallback(&url, &m, headers.as_ref(), body.as_deref()) {
                return Ok(curl_res);
            }
            return Err("Network request failed".to_string());
        }
    };

    let is_challenge_or_blocked = status == 403 || status == 429 || (status >= 400 && text.trim().starts_with('<'));
    if is_challenge_or_blocked {
        if let Ok(curl_res) = run_curl_fallback(&url, &m, headers.as_ref(), body.as_deref()) {
            let curl_status = curl_res["status"].as_u64().unwrap_or(0);
            let curl_data = curl_res["data"].as_str().unwrap_or("");
            if curl_status == 200 || !curl_data.trim().starts_with("<!DOCTYPE") {
                return Ok(curl_res);
            }
        }
    }

    Ok(serde_json::json!({
        "status": status,
        "headers": res_headers,
        "data": text,
        "url": final_url
    }))
}

fn resolve_desktop_directory(folder: Option<&str>) -> std::path::PathBuf {
    let download_dir = dirs::download_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    match folder {
        Some(f) if !f.trim().is_empty() => {
            let mut trimmed = f.trim().to_string();

            if trimmed.starts_with('~') {
                if let Some(home) = dirs::home_dir() {
                    let sub = trimmed.trim_start_matches('~').trim_start_matches('/').trim_start_matches('\\');
                    trimmed = home.join(sub).to_string_lossy().to_string();
                }
            }

            #[cfg(unix)]
            {
                if (trimmed.starts_with("Users/")
                    || trimmed.starts_with("home/")
                    || trimmed.starts_with("Volumes/")
                    || trimmed.starts_with("private/")
                    || trimmed.starts_with("var/")
                    || trimmed.starts_with("tmp/"))
                    && !trimmed.starts_with('/')
                {
                    trimmed = format!("/{}", trimmed);
                }
            }

            let path_obj = std::path::Path::new(&trimmed);
            let normalized = trimmed.replace('\\', "/");
            let norm_lower = normalized.to_lowercase();
            let norm_check = norm_lower.trim_start_matches('/');

            if norm_check.starts_with("movies/") || norm_check.starts_with("videos/") || norm_check == "movies" || norm_check == "videos" {
                let base = dirs::video_dir().unwrap_or_else(|| download_dir.clone());
                let sub = normalized.trim_start_matches('/');
                if sub.contains('/') {
                    base.join(&sub[sub.find('/').unwrap() + 1..])
                } else {
                    base
                }
            } else if norm_check.starts_with("music/") || norm_check.starts_with("audio/") || norm_check == "music" || norm_check == "audio" {
                let base = dirs::audio_dir().unwrap_or_else(|| download_dir.clone());
                let sub = normalized.trim_start_matches('/');
                if sub.contains('/') {
                    base.join(&sub[sub.find('/').unwrap() + 1..])
                } else {
                    base
                }
            } else if norm_check.starts_with("pictures/") || norm_check.starts_with("photos/") || norm_check == "pictures" || norm_check == "photos" {
                let base = dirs::picture_dir().unwrap_or_else(|| download_dir.clone());
                let sub = normalized.trim_start_matches('/');
                if sub.contains('/') {
                    base.join(&sub[sub.find('/').unwrap() + 1..])
                } else {
                    base
                }
            } else if norm_check.starts_with("documents/") || norm_check == "documents" {
                let base = dirs::document_dir().unwrap_or_else(|| download_dir.clone());
                let sub = normalized.trim_start_matches('/');
                if sub.contains('/') {
                    base.join(&sub[sub.find('/').unwrap() + 1..])
                } else {
                    base
                }
            } else if norm_check.starts_with("desktop/") || norm_check == "desktop" {
                let base = dirs::desktop_dir().unwrap_or_else(|| download_dir.clone());
                let sub = normalized.trim_start_matches('/');
                if sub.contains('/') {
                    base.join(&sub[sub.find('/').unwrap() + 1..])
                } else {
                    base
                }
            } else if norm_check.starts_with("downloads/") || norm_check.starts_with("download/") || norm_check == "downloads" || norm_check == "download" {
                let base = download_dir;
                let sub = normalized.trim_start_matches('/');
                if sub.contains('/') {
                    base.join(&sub[sub.find('/').unwrap() + 1..])
                } else {
                    base
                }
            } else if path_obj.is_absolute() {
                path_obj.to_path_buf()
            } else {
                download_dir.join(&trimmed)
            }
        }
        _ => download_dir.join("Mori"),
    }
}

#[tauri::command]
async fn tauri_download_file(
    url: String,
    filename: String,
    folder: Option<String>,
    headers: Option<HashMap<String, String>>,
) -> Result<serde_json::Value, String> {
    let client = reqwest::Client::builder()
        .http1_only()
        .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .redirect(reqwest::redirect::Policy::limited(10))
        .timeout(std::time::Duration::from_secs(120))
        .build()
        .map_err(|e| e.to_string())?;

    let mut req = client.get(&url);

    if let Some(hdrs) = &headers {
        for (k, v) in hdrs {
            req = req.header(k, v);
        }
    }

    let target_dir = resolve_desktop_directory(folder.as_deref());

    std::fs::create_dir_all(&target_dir).map_err(|e| format!("Directory error: {}", e))?;
    let (stem, ext) = {
        let p = std::path::Path::new(&filename);
        let s = p.file_stem().and_then(|s| s.to_str()).unwrap_or("Mori_Media");
        let e = p.extension().and_then(|s| s.to_str()).unwrap_or("mp4").to_lowercase();
        (s.to_string(), e)
    };
    let normalized_filename = format!("{}.{}", stem, ext);
    let mut target_file = target_dir.join(&normalized_filename);

    if target_file.exists() {
        let mut counter = 1;
        loop {
            let candidate = target_dir.join(format!("{}_{}.{}", stem, counter, ext));
            if !candidate.exists() {
                target_file = candidate;
                break;
            }
            counter += 1;
        }
    }

    let res = req.send().await;
    let mut download_succeeded = false;

    if let Ok(r) = res {
        if r.status().is_success() {
            if let Ok(bytes) = r.bytes().await {
                if std::fs::write(&target_file, &bytes).is_ok() {
                    download_succeeded = true;
                }
            }
        }
    }

    // Fallback to curl if reqwest download was blocked (e.g. Cloudflare CDN)
    if !download_succeeded {
        let mut cmd = std::process::Command::new("curl");
        cmd.args(["--http1.1", "-L", "-s", "-o", target_file.to_str().unwrap_or(""), &url]);
        if let Some(hdrs) = &headers {
            for (k, v) in hdrs {
                cmd.arg("-H").arg(format!("{}: {}", k, v));
            }
        }
        if let Ok(out) = cmd.output() {
            if out.status.success() && target_file.exists() && target_file.metadata().map(|m| m.len() > 0).unwrap_or(false) {
                download_succeeded = true;
            }
        }
    }

    if !download_succeeded {
        return Err("Download failed via native and fallback engines.".to_string());
    }

    Ok(serde_json::json!({
        "status": true,
        "path": target_file.to_string_lossy(),
        "uri": format!("file://{}", target_file.to_string_lossy())
    }))
}


#[tauri::command]
async fn tauri_read_file_bytes(path: String) -> Result<Vec<u8>, String> {
    let mut clean_path = path.as_str();
    if clean_path.starts_with("file:///") {
        clean_path = &clean_path[7..]; // keep leading slash e.g. /Users/...
    } else if clean_path.starts_with("file://") {
        clean_path = &clean_path[7..];
    }

    // Try direct read first
    if let Ok(bytes) = std::fs::read(clean_path) {
        return Ok(bytes);
    }
    if let Ok(bytes) = std::fs::read(clean_path.trim_start_matches('/')) {
        return Ok(bytes);
    }

    #[cfg(unix)]
    {
        if (clean_path.starts_with("Users/")
            || clean_path.starts_with("home/")
            || clean_path.starts_with("Volumes/")
            || clean_path.starts_with("private/")
            || clean_path.starts_with("var/")
            || clean_path.starts_with("tmp/"))
            && !clean_path.starts_with('/')
        {
            let fixed = format!("/{}", clean_path);
            if let Ok(bytes) = std::fs::read(&fixed) {
                return Ok(bytes);
            }
        }
    }

    // Strip Android prefix if present
    let stripped = clean_path
        .replace("storage/emulated/0/", "")
        .replace("/storage/emulated/0/", "");
    
    if let Ok(bytes) = std::fs::read(&stripped) {
        return Ok(bytes);
    }

    // Fallback: resolve relative to user's Downloads directory
    let download_dir = dirs::download_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    let rel_path = stripped.trim_start_matches('/');
    
    let target = if rel_path.starts_with("Downloads/") || rel_path.starts_with("downloads/") {
        let parent = download_dir.parent().unwrap_or(&download_dir);
        parent.join(rel_path)
    } else if rel_path.starts_with("Mori/") {
        download_dir.join(rel_path)
    } else {
        download_dir.join("Mori").join(rel_path)
    };

    if let Ok(bytes) = std::fs::read(&target) {
        return Ok(bytes);
    }

    std::fs::read(&target).map_err(|e| {
        format!(
            "Read file error (path: '{}', resolved target: '{}'): {}",
            clean_path,
            target.display(),
            e
        )
    })
}

#[tauri::command]
async fn tauri_fetch_bytes(
    url: String,
    headers: Option<HashMap<String, String>>,
) -> Result<Vec<u8>, String> {
    let client = reqwest::Client::builder()
        .http1_only()
        .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .redirect(reqwest::redirect::Policy::limited(10))
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .map_err(|e| e.to_string())?;

    let mut req = client.get(&url);

    if let Some(hdrs) = headers {
        for (k, v) in hdrs {
            req = req.header(k, v);
        }
    }

    let res = req.send().await.map_err(|e| format!("HTTP request failed: {}", e))?;
    if !res.status().is_success() {
        return Err(format!("HTTP error status: {}", res.status()));
    }

    let bytes = res.bytes().await.map_err(|e| format!("Body read failed: {}", e))?;
    Ok(bytes.to_vec())
}

#[tauri::command]
async fn tauri_save_bytes_file(
    bytes: Vec<u8>,
    filename: String,
    folder: Option<String>,
) -> Result<String, String> {
    let target_dir = resolve_desktop_directory(folder.as_deref());

    std::fs::create_dir_all(&target_dir).map_err(|e| format!("Directory error: {}", e))?;
    let (stem, ext) = {
        let p = std::path::Path::new(&filename);
        let s = p.file_stem().and_then(|s| s.to_str()).unwrap_or("Mori_Document");
        let e = p.extension().and_then(|s| s.to_str()).unwrap_or("pdf").to_lowercase();
        (s.to_string(), e)
    };
    let normalized_filename = format!("{}.{}", stem, ext);
    let mut target_file = target_dir.join(&normalized_filename);

    if target_file.exists() {
        let mut counter = 1;
        loop {
            let candidate = target_dir.join(format!("{}_{}.{}", stem, counter, ext));
            if !candidate.exists() {
                target_file = candidate;
                break;
            }
            counter += 1;
        }
    }

    std::fs::write(&target_file, bytes).map_err(|e| format!("Write error: {}", e))?;
    Ok(target_file.to_string_lossy().to_string())
}

#[tauri::command]
async fn tauri_open_url(url: String) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(&url)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("cmd")
            .args(["/C", "start", "", &url])
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open")
            .arg(&url)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
async fn tauri_get_folder_size(folder: Option<String>) -> Result<u64, String> {
    let target_dir = resolve_desktop_directory(folder.as_deref());

    fn dir_size(path: &std::path::Path) -> u64 {
        let mut total = 0;
        if let Ok(entries) = std::fs::read_dir(path) {
            for entry in entries.flatten() {
                if let Ok(meta) = entry.metadata() {
                    if meta.is_file() {
                        total += meta.len();
                    } else if meta.is_dir() {
                        total += dir_size(&entry.path());
                    }
                }
            }
        }
        total
    }

    Ok(dir_size(&target_dir))
}

#[tauri::command]
async fn tauri_pick_folder() -> Result<Option<String>, String> {
    let folder = rfd::AsyncFileDialog::new()
        .set_title("Select Download Directory")
        .pick_folder()
        .await;
    Ok(folder.map(|f| f.path().to_string_lossy().to_string()))
}

#[tauri::command]
async fn tauri_open_folder(path: String) -> Result<(), String> {
    let mut clean_path = path.trim().to_string();
    if clean_path.starts_with("file:///") {
        clean_path = clean_path[7..].to_string();
    } else if clean_path.starts_with("file://") {
        clean_path = clean_path[7..].to_string();
    }
    #[cfg(unix)]
    {
        if (clean_path.starts_with("Users/")
            || clean_path.starts_with("home/")
            || clean_path.starts_with("Volumes/")
            || clean_path.starts_with("private/"))
            && !clean_path.starts_with('/')
        {
            clean_path = format!("/{}", clean_path);
        }
    }
    let p = std::path::PathBuf::from(&clean_path);
    let resolved_p = if p.is_absolute() {
        p
    } else {
        resolve_desktop_directory(Some(&clean_path))
    };
    let target = if resolved_p.is_file() {
        resolved_p.parent().unwrap_or(&resolved_p).to_path_buf()
    } else {
        resolved_p
    };
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(&target)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(&target)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open")
            .arg(&target)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

mod engine_sec;

#[tauri::command]
fn tauri_get_engine_key(challenge: String) -> Result<String, String> {
    engine_sec::get_key(&challenge)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  tauri::Builder::default()
    .plugin(tauri_plugin_http::init())
    .invoke_handler(tauri::generate_handler![
        tauri_http_request,
        tauri_download_file,
        tauri_read_file_bytes,
        tauri_fetch_bytes,
        tauri_save_bytes_file,
        tauri_open_url,
        tauri_get_folder_size,
        tauri_pick_folder,
        tauri_open_folder,
        tauri_drive_action,
        tauri_get_engine_key
    ])
    .setup(|app| {
      if cfg!(debug_assertions) {
        app.handle().plugin(
          tauri_plugin_log::Builder::default()
            .level(log::LevelFilter::Info)
            .build(),
        )?;
      }
      Ok(())
    })
    .run(tauri::generate_context!())
    .expect("error while running tauri application");
}

fn run_curl_fallback(
    url: &str,
    method: &str,
    headers: Option<&HashMap<String, String>>,
    body: Option<&str>,
) -> Result<serde_json::Value, String> {
    let mut cmd = std::process::Command::new("curl");
    cmd.args(["--http1.1", "-s", "-i", "-X", method, url]);

    if let Some(hdrs) = headers {
        for (k, v) in hdrs {
            cmd.arg("-H").arg(format!("{}: {}", k, v));
        }
    }

    if let Some(b) = body {
        cmd.arg("--data-raw").arg(b);
    }

    let output = cmd.output().map_err(|e| format!("Curl execution failed: {}", e))?;
    let raw = String::from_utf8_lossy(&output.stdout).to_string();

    let mut status = 200u16;
    let mut res_headers = HashMap::new();
    let mut body_str = raw.clone();

    if let Some(idx) = raw.rfind("\r\n\r\n") {
        let (head_section, body_part) = raw.split_at(idx);
        body_str = body_part[4..].to_string();

        let head_block = if let Some(prev_idx) = head_section.rfind("\r\n\r\n") {
            &head_section[prev_idx + 4..]
        } else {
            head_section
        };

        for line in head_block.lines() {
            if line.starts_with("HTTP/") {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                    if let Ok(code) = parts[1].parse::<u16>() {
                        status = code;
                    }
                }
            } else if let Some((k, v)) = line.split_once(':') {
                res_headers.insert(k.trim().to_lowercase(), v.trim().to_string());
            }
        }
    } else if let Some(idx) = raw.rfind("\n\n") {
        let (head_section, body_part) = raw.split_at(idx);
        body_str = body_part[2..].to_string();
        for line in head_section.lines() {
            if line.starts_with("HTTP/") {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                    if let Ok(code) = parts[1].parse::<u16>() {
                        status = code;
                    }
                }
            } else if let Some((k, v)) = line.split_once(':') {
                res_headers.insert(k.trim().to_lowercase(), v.trim().to_string());
            }
        }
    }

    Ok(serde_json::json!({
        "status": status,
        "headers": res_headers,
        "data": body_str
    }))
}

