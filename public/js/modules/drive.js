// Native owns OAuth, folder selection, staging, verification and cleanup.
let status = null;
let enabledObserved = false;
let nativeQueue = Promise.resolve();

export function driveText(en, es) {
  return globalThis.localStorage?.getItem("mori_lang") === "es" ? es : en;
}

function cachedEnabled() {
  try { return globalThis.localStorage?.getItem("mori_drive_enabled") === "true"; }
  catch (_) { return false; }
}

function nativeBridge() {
  const w = globalThis.window;
  const invoke = w?.__TAURI__?.core?.invoke || w?.__TAURI_INTERNALS__?.invoke || w?.__TAURI__?.invoke;
  if (invoke && /win/i.test(w.navigator?.userAgentData?.platform || w.navigator?.platform || w.navigator?.userAgent || "")) {
    return (action, options) => invoke("tauri_drive_action", { action, options });
  }
  const cap = w?.Capacitor;
  if (cap?.getPlatform?.() === "android") {
    const plugin = cap.Plugins?.MoriDrive || cap.registerPlugin?.("MoriDrive");
    if (plugin?.request) return (action, options) => plugin.request({ action, options });
  }
  return null;
}

function requestDrive(action, options = {}) {
  const request = nativeQueue.then(() => {
    const bridge = nativeBridge();
    if (!bridge) throw new Error(driveText("Google Drive is available only on Windows and Android with native Drive support.", "Google Drive solo esta disponible en Windows y Android con soporte nativo."));
    return bridge(action, options);
  });
  // Queue only the native call, never a callback that awaits another queued call.
  nativeQueue = request.catch(() => {});
  return request;
}

export function validateDriveStatus(value) {
  if (!value || typeof value.connected !== "boolean" || typeof value.enabled !== "boolean" || !Array.isArray(value.pending) || (value.completed !== undefined && !Array.isArray(value.completed))) {
    throw new Error("Invalid native Drive status");
  }
  const text = (v) => typeof v === "string" ? v : "";
  return {
    connected: value.connected, enabled: value.enabled,
    folderId: text(value.folderId), folderName: text(value.folderName),
    pending: value.pending.map((job) => ({
      jobId: text(job.jobId), fileName: text(job.fileName), title: text(job.title),
      sourceUrl: text(job.sourceUrl), error: text(job.error),
    })),
    completed: (value.completed || []).map((receipt) => {
      const file = validateDriveResult(receipt);
      if (!file.receiptId) throw new Error("Invalid native Drive receipt");
      return { ...file, sourceUrl: text(receipt.sourceUrl) || file.uri, title: text(receipt.title) };
    }),
  };
}

function rememberStatus(value) {
  status = validateDriveStatus(value);
  enabledObserved = status.enabled;
  try { globalThis.localStorage?.setItem("mori_drive_enabled", String(status.enabled)); } catch (_) {}
  globalThis.window?.dispatchEvent(new CustomEvent("mori_drive_status", { detail: status }));
  if (status.completed.length) globalThis.window?.dispatchEvent(new CustomEvent("mori_drive_receipts", { detail: status.completed }));
  return status;
}

export async function getDriveStatus() {
  const value = await requestDrive("status");
  if (value?.enabled === true) enabledObserved = true;
  return rememberStatus(value);
}

export async function shouldUseDrive() {
  let latest;
  try { latest = await getDriveStatus(); }
  catch (error) {
    // Never interpret unavailable native state as permission to save locally.
    if (enabledObserved || status?.enabled || cachedEnabled()) throw error;
    return false;
  }
  if (!latest.enabled) return false;
  if (!latest.connected || !latest.folderId) throw new Error(driveText("Connect Google Drive and choose a folder before downloading.", "Conecta Google Drive y elige una carpeta antes de descargar."));
  return true;
}

export function safeDriveUrl(file) {
  if (!file || typeof file.driveFileId !== "string" || !/^[A-Za-z0-9_-]+$/.test(file.driveFileId)) return null;
  const uri = `https://drive.google.com/file/d/${file.driveFileId}/view`;
  return file.uri === uri ? uri : null;
}

export function validateDriveResult(value) {
  if (!safeDriveUrl(value) || typeof value.fileName !== "string" || !value.fileName || typeof value.localDeleted !== "boolean" || (value.receiptId !== undefined && (typeof value.receiptId !== "string" || !/^[A-Za-z0-9_-]+$/.test(value.receiptId)))) {
    throw new Error("Drive upload was not confirmed by native verification");
  }
  return { driveFileId: value.driveFileId, uri: value.uri, fileName: value.fileName, localDeleted: value.localDeleted, ...(value.receiptId === undefined ? {} : { receiptId: value.receiptId }) };
}

export function mergeDriveFiles(existing = [], incoming = []) {
  const files = new Map();
  for (const file of [...existing, ...incoming]) {
    if (safeDriveUrl(file)) files.set(file.driveFileId, file);
  }
  return [...files.values()];
}

async function transfer(action, options, context = options) {
  try {
    const result = validateDriveResult(await requestDrive(action, options));
    globalThis.window?.dispatchEvent(new CustomEvent("mori_drive_file_saved", {
      detail: { url: context.sourceUrl || context.url || "", ...result, title: context.title || context.fileName || result.fileName },
    }));
    if (!result.localDeleted) {
      globalThis.window?.dispatchEvent(new CustomEvent("mori_drive_warning", {
        detail: driveText("Saved in Drive. Local temporary file cleanup is pending; retry it in Settings.", "Guardado en Drive. Falta eliminar el archivo temporal local; reintenta en Ajustes."),
      }));
    }
    return { success: true, ...result };
  } finally {
    // Pending staging files belong to native and are never removed by JS.
    await getDriveStatus().catch(() => {});
  }
}

export async function downloadToDrive(options) {
  if (!(await shouldUseDrive())) throw new Error("Drive is not enabled");
  const url = new URL(options.url);
  if (!["https:", "http:"].includes(url.protocol) || url.username || url.password) throw new Error("Invalid Drive download URL");
  return transfer("download", options);
}

export async function savePdfToDrive(bytes, options) {
  if (!(await shouldUseDrive())) throw new Error("Drive is not enabled");
  const data = await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1]);
    reader.onerror = () => reject(new Error("Could not encode PDF"));
    reader.readAsDataURL(new Blob([bytes], { type: "application/pdf" }));
  });
  return transfer("saveBytes", { ...options, data });
}

export async function testDriveUpload() {
  if (!(await shouldUseDrive())) throw new Error(driveText("Enable Drive before running the upload test.", "Activa Drive antes de probar la subida."));
  const timestamp = new Date().toISOString();
  return transfer("saveBytes", {
    data: btoa(`Mori Google Drive upload test.\nUTC: ${timestamp}\n`),
    fileName: `Mori_drive_test_${timestamp.replace(/[:.]/g, "-")}.txt`,
    title: "Mori Drive upload test",
  });
}

export async function retryDriveJob(job) {
  if (!job?.jobId) throw new Error("Missing Drive job ID");
  return transfer("retry", { jobId: job.jobId }, job);
}

export async function changeDriveSettings(action, options = {}) {
  if (action === "setEnabled" && options.enabled) enabledObserved = true;
  try {
    const result = await requestDrive(action, options);
    // configure may return an acknowledgement; this follow-up runs after its queue slot releases.
    if (action === "configure") return await getDriveStatus();
    return rememberStatus(result);
  } catch (error) {
    await getDriveStatus().catch(() => {});
    throw error;
  }
}

export async function acknowledgeDriveReceipt(receiptId) {
  if (typeof receiptId !== "string" || !/^[A-Za-z0-9_-]+$/.test(receiptId)) throw new Error("Invalid Drive receipt ID");
  // No status refresh here: acknowledgement must not recursively replay receipts.
  return requestDrive("acknowledge", { receiptId });
}

export async function openDriveFile(file) {
  const url = safeDriveUrl(file);
  if (!url) throw new Error("Invalid Google Drive URL");
  const { openExternalUrl } = await import("./core.js");
  openExternalUrl(url);
}

export function initDriveSettings(showToast) {
  const panel = document.getElementById("driveSettings");
  if (!panel || panel.dataset.initialized) return;
  panel.dataset.initialized = "true";
  let busy = false;
  let verified = false;
  let errorText = "";
  const toggle = panel.querySelector("input");
  const pending = panel.querySelector(".drive-pending");
  const render = () => {
    panel.querySelector(".drive-description").textContent = driveText("Upload to your selected folder, verify, then delete the local temporary file. Off by default. Failed uploads stay pending for manual retry.", "Sube a la carpeta elegida, verifica y elimina el archivo temporal local. Desactivado por defecto. Las subidas fallidas quedan pendientes para reintentar.");
    panel.querySelector(".drive-toggle-label").textContent = driveText("Save only in Drive", "Guardar solo en Drive");
    panel.querySelector(".drive-status").textContent = errorText || (verified
      ? `${status.connected ? driveText("Connected", "Conectado") : driveText("Not connected", "Sin conectar")} / ${status.enabled ? driveText("Enabled", "Activado") : driveText("Local downloads", "Descargas locales")}${status.folderName || status.folderId ? ` / ${status.folderName || status.folderId}` : ""}`
      : driveText("Checking native Drive support...", "Comprobando soporte nativo de Drive..."));
    toggle.checked = Boolean(status?.enabled || (!verified && cachedEnabled()));
    toggle.disabled = busy || !verified || (!status.enabled && (!status.connected || !status.folderId));
    const labels = { configure: ["Import Windows credentials", "Importar credenciales de Windows"], connect: [status?.connected ? "Choose Drive folder" : "Connect / choose folder", status?.connected ? "Elegir carpeta de Drive" : "Conectar / elegir carpeta"], disconnect: ["Disconnect", "Desconectar"], status: ["Refresh status", "Actualizar estado"], test: ["Test upload (.txt)", "Probar subida (.txt)"] };
    panel.querySelectorAll("[data-drive-action]").forEach((button) => {
      const action = button.dataset.driveAction;
      button.textContent = driveText(...labels[action]);
      button.hidden = action === "configure" && !/win/i.test(window.navigator?.platform || window.navigator?.userAgent || "");
      button.disabled = busy || (action !== "status" && !nativeBridge()) || (action === "disconnect" && !status?.connected) || (action === "test" && (!verified || !status?.enabled || !status?.connected));
    });
    pending.replaceChildren();
    for (const job of status?.pending || []) {
      const row = document.createElement("div");
      row.className = "drive-pending-row";
      const label = document.createElement("span");
      label.textContent = `${job.title || job.fileName} ${job.error ? `: ${job.error}` : driveText("(pending)", "(pendiente)")}`;
      const retry = document.createElement("button");
      retry.className = "path-preset-chip";
      retry.textContent = driveText("Retry", "Reintentar");
      retry.disabled = busy || !verified || !status.connected || !status.folderId;
      retry.onclick = () => run(() => retryDriveJob(job));
      row.append(label, retry);
      pending.append(row);
    }
  };
  const run = async (operation, notifyError = true) => {
    if (busy) return;
    busy = true;
    errorText = "";
    render();
    try { await operation(); verified = true; }
    catch (error) { errorText = error?.message || String(error); if (notifyError) showToast(errorText); }
    finally { busy = false; render(); }
  };
  panel.querySelectorAll("[data-drive-action]").forEach((button) => {
    button.onclick = () => run(async () => {
      const action = button.dataset.driveAction;
      if (action === "status") return getDriveStatus();
      if (action !== "test") return changeDriveSettings(action);
      const result = await testDriveUpload();
      showToast(result.localDeleted
        ? driveText("Upload verified and local temporary file deleted. Check the test file in Drive.", "Subida verificada y temporal local eliminado. Comprueba el archivo de prueba en Drive.")
        : driveText("Uploaded to Drive, but local cleanup is pending. Retry in Settings.", "Subido a Drive, pero falta eliminar el temporal. Reintenta en Ajustes."));
    });
  });
  toggle.onchange = () => {
    const enabled = toggle.checked;
    toggle.checked = Boolean(status?.enabled);
    run(() => changeDriveSettings("setEnabled", { enabled }));
  };
  window.addEventListener("mori_drive_status", () => { verified = true; errorText = ""; render(); });
  window.addEventListener("mori_drive_warning", (event) => showToast(event.detail));
  window.addEventListener("mori_language_changed", render);
  window.addEventListener("focus", () => run(getDriveStatus, false));
  window.addEventListener("mori_app_resumed", () => run(getDriveStatus, false));
  run(getDriveStatus, false);
}
