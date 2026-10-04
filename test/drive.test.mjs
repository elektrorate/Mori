import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

let sequence = 0;
const nativeStatus = (extra = {}) => ({ connected: true, enabled: true, folderId: "folder_1", folderName: "Albums", pending: [], ...extra });
const nativeResult = (extra = {}) => ({ driveFileId: "file_1", uri: "https://drive.google.com/file/d/file_1/view", fileName: "Album.pdf", localDeleted: true, ...extra });

async function setup(handler = async (action) => action === "status" ? nativeStatus() : nativeResult(), platform = "windows") {
  const store = new Map();
  globalThis.localStorage = { getItem: (key) => store.get(key) ?? null, setItem: (key, value) => store.set(key, String(value)), removeItem: (key) => store.delete(key) };
  globalThis.CustomEvent ||= class extends Event { constructor(type, options = {}) { super(type); this.detail = options.detail; } };
  const events = [];
  const calls = [];
  const request = async (action, options) => { calls.push({ action, options }); return handler(action, options); };
  const w = globalThis.window = new EventTarget();
  w.navigator = { platform: platform === "windows" ? "Win32" : platform === "mac" ? "MacIntel" : "Linux" };
  w.open = () => { throw new Error("Unexpected browser fallback"); };
  for (const name of ["mori_file_saved", "mori_drive_file_saved", "mori_drive_status", "mori_drive_receipts", "mori_drive_warning"]) w.addEventListener(name, (event) => events.push({ type: name, detail: event.detail }));
  if (platform === "windows" || platform === "mac") w.__TAURI__ = { core: { invoke: (command, args) => { assert.equal(command, "tauri_drive_action"); return request(args.action, args.options); } } };
  if (platform === "android") w.Capacitor = { getPlatform: () => "android", Plugins: { MoriDrive: { request: (args) => request(args.action, args.options) } } };
  globalThis.document = { getElementById: () => null };
  const drive = await import(`../public/js/modules/drive.js?test=${sequence++}`);
  return { drive, store, events, calls, w };
}

// Load the real flow with its UI/platform imports stubbed, without a DOM framework.
async function loadFlow(file, dependencies, suffix = "") {
  const source = await readFile(new URL(`../public/js/${file}`, import.meta.url), "utf8");
  const key = `__driveTestDeps${sequence++}`;
  dependencies = { console: { log() {}, warn() {}, error() {} }, ...dependencies };
  globalThis[key] = dependencies;
  const importsRemoved = source.replace(/^import\s[\s\S]*?from\s["'][^"']+["'];\r?$/gm, "");
  return import(`data:text/javascript;base64,${Buffer.from(`const { ${Object.keys(dependencies).join(", ")} } = globalThis.${key};\n${importsRemoved}\n${suffix}`).toString("base64")}`);
}

async function downloadFlow(drive) {
  const storageCalls = [];
  const bubbleCalls = [];
  const messages = [];
  const filesystem = Object.fromEntries(["checkPermissions", "requestPermissions", "mkdir", "addListener", "deleteFile"].map((method) => [method, async () => { storageCalls.push(method); throw new Error("Unexpected filesystem call"); }]));
  const bubble = Object.fromEntries(["addDownload", "updateProgress", "completeDownload", "failDownload", "cancelDownload"].map((method) => [method, (...args) => bubbleCalls.push({ method, args })]));
  const flow = await loadFlow("ui/nativeDownload.js", {
    translations: { en: {} }, t: (key) => key, currentLang: "en", showToast: (message) => messages.push(message), Filesystem: filesystem,
    requestWakeLock() {}, releaseWakeLock() {}, checkWifiOnlyGuard: async () => true, normalizePathInput: (v) => v,
    resolveExtension: () => "mp4", sanitizeTitle: (title) => title, generateFileName: () => "Media.mp4", detectPlatformFolder: () => "Other",
    resolveUniqueFileName: async () => { storageCalls.push("unique"); throw new Error("Unexpected filename scan"); },
    cleanDownloadUrl: (url) => url.replace("&amp;", "&"), buildDownloadHeaders: () => ({ Referer: "https://source.example/", "User-Agent": "Mori" }),
    needsAsyncResolving: (url) => url.startsWith("youtube_resolve:"), resolveDownloadUrl: async () => ({ url: "https://cdn.example/video?x=1&amp;y=2" }),
    saveToStorage: async () => { storageCalls.push("save"); throw new Error("Unexpected local fallback"); }, handlePostDownload: async () => { storageCalls.push("post"); },
    downloadBubble: bubble, shouldUseDrive: drive.shouldUseDrive, downloadToDrive: drive.downloadToDrive, driveText: drive.driveText,
  });
  return { ...flow, storageCalls, bubbleCalls, messages };
}

async function historyFlow(drive, extra = {}) {
  return loadFlow("modules/history.js", {
    translations: { en: {} }, currentLang: "en", cleanUrl: (url) => url || "", Filesystem: null, getVideoThumbnail() {}, triggerHaptic() {},
    showModal() {}, renderHistory() {}, setUIState() {}, showConfirm() {}, downloadBtn: null, editHistoryBtn: null, doneEditBtn: null, clearAllBtn: null,
    setIsEditingHistory() {}, clearCacheSilently() {}, updateGreeting() {}, updateStorageInfo() {}, switchToSingleMode() {},
    validateDriveResult: drive.validateDriveResult, mergeDriveFiles: drive.mergeDriveFiles,
    getDriveStatus: drive.getDriveStatus, acknowledgeDriveReceipt: drive.acknowledgeDriveReceipt, ...extra,
  });
}

test("receipts without a public source recover using their verified Drive link", async () => {
  const { drive } = await setup();
  const receipt = { ...nativeResult(), receiptId: "job_1", sourceUrl: null };
  const validated = drive.validateDriveStatus(nativeStatus({ completed: [receipt] }));
  assert.equal(validated.completed[0].sourceUrl, receipt.uri);
});

test("manual upload test stages a small text file and reports the native cleanup result", async () => {
  const { drive, calls } = await setup(async (action, options) => action === "status" ? nativeStatus() : nativeResult({ fileName: options.fileName }));
  const result = await drive.testDriveUpload();
  const upload = calls.find((call) => call.action === "saveBytes");
  assert.match(upload.options.fileName, /^Mori_drive_test_.*\.txt$/);
  assert.match(atob(upload.options.data), /^Mori Google Drive upload test\.\nUTC: /);
  assert.equal(result.localDeleted, true);
  assert.equal(upload.options.sourceUrl, undefined);

  const pending = await setup(async (action) => action === "status" ? nativeStatus() : nativeResult({ localDeleted: false }));
  assert.equal((await pending.drive.testDriveUpload()).localDeleted, false);
  const disabled = await setup(async () => nativeStatus({ enabled: false }));
  await assert.rejects(disabled.drive.testDriveUpload(), /Enable Drive/);
  assert.equal(disabled.calls.some((call) => call.action === "saveBytes"), false);
});

test("default browser stays local, but cached enabled fails closed on browser, macOS and iOS", async () => {
  for (const platform of ["browser", "mac", "ios"]) {
    const { drive, store, calls } = await setup(undefined, platform);
    assert.equal(await drive.shouldUseDrive(), false);
    store.set("mori_drive_enabled", "true");
    await assert.rejects(drive.shouldUseDrive(), /Windows and Android/);
    assert.equal(calls.length, 0);
  }
});

test("native status overrides the cache and requires connection plus selected folder", async () => {
  let value = nativeStatus({ enabled: false });
  const { drive, store } = await setup(async () => value);
  store.set("mori_drive_enabled", "true");
  assert.equal(await drive.shouldUseDrive(), false);
  assert.equal(store.get("mori_drive_enabled"), "false");
  value = nativeStatus({ folderId: "" });
  await assert.rejects(drive.shouldUseDrive(), /choose a folder/);
  value = nativeStatus({ connected: false });
  await assert.rejects(drive.shouldUseDrive(), /Connect Google Drive/);
});

test("status outage or malformed enabled status never switches an enabled session to local", async () => {
  let outage = false;
  const { drive } = await setup(async () => { if (outage) throw new Error("offline"); return nativeStatus(); });
  assert.equal(await drive.shouldUseDrive(), true);
  outage = true;
  await assert.rejects(drive.shouldUseDrive(), /offline/);
  const malformed = await setup(async () => ({ enabled: true }));
  await assert.rejects(malformed.drive.shouldUseDrive(), /Invalid native Drive status/);
});

test("configure uses only the native file picker; enabling is verified and serialized", async () => {
  let enabled = false;
  let release;
  let started;
  const enabling = new Promise((resolve) => { started = resolve; });
  const { drive, calls, store } = await setup(async (action, options) => {
    if (action === "configure") return { configured: true };
    if (action === "setEnabled") {
      await new Promise((resolve) => { release = resolve; started(); });
      enabled = options.enabled;
    }
    return nativeStatus({ enabled });
  });
  await drive.changeDriveSettings("configure");
  assert.deepEqual(calls[0], { action: "configure", options: {} });
  const change = drive.changeDriveSettings("setEnabled", { enabled: true });
  assert.equal(store.get("mori_drive_enabled"), "false");
  const disconnect = drive.changeDriveSettings("disconnect");
  const route = drive.shouldUseDrive();
  await enabling;
  assert.equal(calls.some((call) => call.action === "disconnect"), false);
  release();
  await change;
  await disconnect;
  assert.equal(await route, true);
  assert.ok(calls.findIndex((call) => call.action === "setEnabled") < calls.findIndex((call) => call.action === "disconnect"));
  assert.deepEqual([...store.keys()], ["mori_drive_enabled"]);
});

test("Android registerPlugin dispatches the same action/options contract", async () => {
  const { drive, calls, w } = await setup(undefined, "android");
  delete w.Capacitor.Plugins.MoriDrive;
  w.Capacitor.registerPlugin = (name) => { assert.equal(name, "MoriDrive"); return { request: async (args) => { calls.push(args); return nativeStatus(); } }; };
  assert.equal(await drive.shouldUseDrive(), true);
  assert.deepEqual(calls, [{ action: "status", options: {} }]);
});

test("validates verified results and safe URLs; merging retains distinct cloud files", async () => {
  const { drive } = await setup();
  for (const bad of [{}, nativeResult({ uri: "javascript:alert(1)" }), nativeResult({ uri: "https://drive.google.com.evil/file/d/file_1/view" }), nativeResult({ localDeleted: undefined }), nativeResult({ driveFileId: "../file" })]) {
    assert.throws(() => drive.validateDriveResult(bad));
    if (bad.uri?.startsWith("javascript:")) await assert.rejects(drive.openDriveFile(bad));
  }
  const second = nativeResult({ driveFileId: "file_2", uri: "https://drive.google.com/file/d/file_2/view" });
  const merged = drive.mergeDriveFiles([nativeResult({ localDeleted: false })], [nativeResult(), second]);
  assert.equal(merged.length, 2);
  assert.equal(merged[0].localDeleted, true);
  assert.equal(drive.safeDriveUrl(merged[1]), second.uri);
});

test("Drive flow resolves URL and cleaned headers before native upload, never touching public storage", async () => {
  const { drive, calls, events } = await setup();
  const flow = await downloadFlow(drive);
  const result = await flow.startNativeDownload("youtube_resolve:id", "VIDEO", "Media", null, "https://source.example/");
  assert.equal(result.success, true);
  const download = calls.find((call) => call.action === "download");
  assert.deepEqual(download.options, { url: "https://cdn.example/video?x=1&y=2", fileName: "Media.mp4", headers: { Referer: "https://source.example/", "User-Agent": "Mori" }, sourceUrl: "https://source.example/", title: "Media" });
  assert.deepEqual(flow.storageCalls, []);
  assert.equal(flow.bubbleCalls.find((call) => call.method === "addDownload").args[0].onCancel, null);
  assert.equal(events.filter((event) => event.type === "mori_drive_file_saved").length, 1);
  assert.equal(events.filter((event) => event.type === "mori_file_saved").length, 0);
  assert.equal("path" in result, false);
});

test("failed upload retains pending metadata, emits no save event, and never falls back locally", async () => {
  const pending = [{ jobId: "job_1", fileName: "Media.mp4", title: "Media", sourceUrl: "https://source.example/", error: "Upload failed" }];
  const { drive, events } = await setup(async (action) => { if (action === "download") throw new Error("Upload failed"); return nativeStatus({ pending }); }, "android");
  const flow = await downloadFlow(drive);
  const result = await flow.startNativeDownload("https://cdn.example/video", "VIDEO", "Media");
  assert.equal(result.success, false);
  assert.deepEqual((await drive.getDriveStatus()).pending, pending);
  assert.deepEqual(flow.storageCalls, []);
  assert.equal(events.some((event) => event.type.endsWith("file_saved")), false);
  assert.equal(flow.bubbleCalls.some((call) => call.method === "completeDownload"), false);
});

test("confirmed cloud result survives cancellation while the native request is in flight", async () => {
  const { drive, events, w } = await setup(async (action) => { if (action === "download") { w._moriDownloadCancelled = true; return nativeResult(); } return nativeStatus(); });
  const flow = await downloadFlow(drive);
  const result = await flow.startNativeDownload("https://cdn.example/video", "VIDEO", "Media");
  assert.equal(result.success, true);
  assert.equal(events.filter((event) => event.type === "mori_drive_file_saved").length, 1);
  assert.equal(flow.bubbleCalls.some((call) => call.method === "cancelDownload"), false);
  assert.deepEqual(flow.storageCalls, []);
});

test("unsupported enabled downloads fail before browser fallback or storage access", async () => {
  const { drive, store, events } = await setup(undefined, "browser");
  store.set("mori_drive_enabled", "true");
  const flow = await downloadFlow(drive);
  const result = await flow.startNativeDownload("https://cdn.example/video", "VIDEO", "Media");
  assert.equal(result.success, false);
  assert.deepEqual(flow.storageCalls, []);
  assert.deepEqual(flow.bubbleCalls, []);
  assert.equal(events.some((event) => event.type.endsWith("file_saved")), false);
});

test("cleanup pending warns and retry uses the job ID without removing native metadata", async () => {
  const job = { jobId: "job_1", fileName: "Album.pdf", title: "Album", sourceUrl: "https://source.example/", error: "cleanup" };
  let retried = false;
  const { drive, calls, events } = await setup(async (action) => {
    if (action === "status") return nativeStatus({ pending: retried ? [] : [job] });
    if (action === "retry") { retried = true; return nativeResult(); }
    return nativeResult({ localDeleted: false });
  });
  await drive.downloadToDrive({ url: "https://cdn.example/video", sourceUrl: job.sourceUrl, title: job.title });
  assert.equal(events.filter((event) => event.type === "mori_drive_warning").length, 1);
  assert.deepEqual((await drive.getDriveStatus()).pending, [job]);
  await drive.retryDriveJob(job);
  assert.deepEqual(calls.find((call) => call.action === "retry"), { action: "retry", options: { jobId: "job_1" } });
  assert.deepEqual((await drive.getDriveStatus()).pending, []);
  assert.equal(events.filter((event) => event.type === "mori_drive_file_saved").at(-1).detail.url, job.sourceUrl);
});

test("generated PDF uses base64 saveBytes; a failed upload emits no success", async () => {
  let fail = false;
  const { drive, calls, events } = await setup(async (action) => { if (action === "status") return nativeStatus(); if (fail) throw new Error("PDF upload failed"); return nativeResult(); });
  globalThis.FileReader = class {
    readAsDataURL(blob) { blob.arrayBuffer().then((bytes) => { this.result = `data:application/pdf;base64,${Buffer.from(bytes).toString("base64")}`; this.onload(); }); }
  };
  const options = { fileName: "Album.pdf", sourceUrl: "https://source.example/", title: "Album" };
  await drive.savePdfToDrive(new Uint8Array([37, 80, 68, 70]), options);
  assert.deepEqual(calls.find((call) => call.action === "saveBytes").options, { ...options, data: "JVBERg==" });
  fail = true;
  await assert.rejects(drive.savePdfToDrive(new Uint8Array([1]), options), /PDF upload failed/);
  assert.equal(events.filter((event) => event.type === "mori_drive_file_saved").length, 1);
});

test("history keeps Drive separate from local files and survives rescrape merging", async () => {
  const { drive, store } = await setup();
  let thumbnailCalls = 0;
  const history = await historyFlow(drive, { getVideoThumbnail: () => { thumbnailCalls++; } });
  history.saveToHistory({ title: "Album", downloads: [] }, "https://source.example/");
  await drive.downloadToDrive({ url: "https://cdn.example/video", sourceUrl: "https://source.example/", title: "Album" });
  history.saveToHistory({ title: "Rescraped", downloads: [] }, "https://source.example/");
  const item = JSON.parse(store.get("mori_history"))[0];
  assert.equal(item.driveFiles.length, 1);
  assert.deepEqual(item.localFiles, []);
  assert.equal(item.localUri, null);
  assert.equal(thumbnailCalls, 0);
});

test("gallery PDF flow uploads without local writes and reports upload failure without saved UI", async () => {
  let fail = false;
  const { drive, events } = await setup(async (action) => { if (action === "status") return nativeStatus(); if (fail) throw new Error("Upload failed"); return nativeResult(); }, "android");
  const bubbleCalls = [];
  let localWrites = 0;
  globalThis.Image = class { set src(value) { queueMicrotask(() => this.onerror()); } };
  globalThis.FileReader = class {
    readAsDataURL(blob) { blob.arrayBuffer().then((bytes) => { this.result = `data:application/pdf;base64,${Buffer.from(bytes).toString("base64")}`; this.onload(); }); }
  };
  window.PDFLib = { PDFDocument: { create: async () => ({ embedJpg: async () => ({ scale: () => ({ width: 1, height: 1 }) }), addPage: () => ({ drawImage() {} }), save: async () => new Uint8Array([37, 80, 68, 70]) }) } };
  const resultUI = await loadFlow("ui/result.js", {
    translations: { en: {} }, currentLang: "en", requestWakeLock() {}, releaseWakeLock() {}, showToast() {}, CHROME_UA: "Mori",
    Filesystem: { writeFile: async () => { localWrites++; } }, CapacitorHttp: { get: async () => ({ status: 200, data: new Uint8Array([1]).buffer }) },
    downloadBubble: Object.fromEntries(["addDownload", "updateProgress", "disableCancel", "completeDownload", "failDownload", "cancelDownload"].map((method) => [method, (...args) => bubbleCalls.push({ method, args })])),
    shouldUseDrive: drive.shouldUseDrive, savePdfToDrive: drive.savePdfToDrive, driveText: drive.driveText,
  });
  const items = [{ url: "https://cdn.example/photo.jpg" }];
  const result = await resultUI.exportGalleryToPdf("Album", items, "https://source.example/");
  assert.equal(result.success, true);
  assert.equal(localWrites, 0);
  assert.equal(bubbleCalls.some((call) => call.method === "disableCancel"), true);
  assert.equal(bubbleCalls.filter((call) => call.method === "completeDownload").length, 1);
  fail = true;
  await resultUI.exportGalleryToPdf("Album", items, "https://source.example/");
  assert.equal(localWrites, 0);
  assert.equal(bubbleCalls.filter((call) => call.method === "completeDownload").length, 1);
  assert.equal(bubbleCalls.filter((call) => call.method === "failDownload").length, 1);
  assert.equal(events.filter((event) => event.type === "mori_drive_file_saved").length, 1);
});

test("Spanish Drive strings have a complete English fallback for legacy screens", async () => {
  const { drive, store } = await setup();
  store.set("mori_lang", "es");
  assert.equal(drive.driveText("Open in Drive", "Abrir en Drive"), "Abrir en Drive");
  const { translations } = await import("../public/js/i18n/index.js");
  assert.deepEqual(Object.keys(translations.es), Object.keys(translations.en));
  store.set("mori_lang", "ja");
  assert.equal(drive.driveText("Open in Drive", "Abrir en Drive"), "Open in Drive");
});

test("shared-link callbacks never mistake Drive URLs for local files and unsupported enabled shares are blocked", async () => {
  const { drive, store, w, events } = await setup(undefined, "browser");
  const btn = { disabled: false, classList: { add() {}, remove() {} }, querySelector: () => ({ textContent: "" }) };
  globalThis.document = { getElementById: (id) => id === "dl_btn_0" ? btn : null, querySelectorAll: () => [] };
  let nativeDownloads = 0;
  w.MoriShareBridge = { downloadFile: () => { nativeDownloads++; }, showToast() {} };
  store.set("mori_history", JSON.stringify([{ url: "https://source.example/", title: "Album" }]));
  const share = await loadFlow("share.js", {
    translations: { en: {} }, cleanUrl: (v) => v, getUserAgent: () => "Mori", safeSetHistory: (value) => store.set("mori_history", JSON.stringify(value)),
    shouldUseDrive: drive.shouldUseDrive, downloadToDrive: drive.downloadToDrive, validateDriveResult: drive.validateDriveResult,
    mergeDriveFiles: drive.mergeDriveFiles, driveText: drive.driveText, cleanDownloadUrl: (v) => v, buildDownloadHeaders: () => ({}),
  }, 'export { triggerDownload }; export function setSourceForTest(url) { targetUrl = url; activeResult = { title: "Album", downloads: [{}, {}] }; }');
  share.setSourceForTest("https://source.example/");
  w.onDownloadComplete("Album.pdf", nativeResult());
  const cloudEvents = events.filter((event) => event.type === "mori_drive_file_saved");
  assert.equal(cloudEvents.length, 1);
  assert.equal(cloudEvents[0].detail.url, "https://source.example/");
  assert.equal(JSON.parse(store.get("mori_history"))[0].localUri, undefined);
  w.onDownloadComplete("Album.pdf", nativeResult().uri);
  assert.equal(JSON.parse(store.get("mori_history"))[0].localUri, undefined);
  store.set("mori_drive_enabled", "true");
  await share.triggerDownload({ url: "https://cdn.example/video", type: "VIDEO" }, "Album", 0);
  assert.equal(nativeDownloads, 0);
  assert.equal(btn.disabled, false);
  assert.equal(events.filter((event) => event.type === "mori_file_saved").length, 0);
});

test("concurrent bulk transfers and status reads share one native queue", { timeout: 3000 }, async () => {
  let active = 0;
  let maximum = 0;
  let release;
  let started;
  const downloading = new Promise((resolve) => { started = resolve; });
  const { drive, calls, store } = await setup(async (action, options) => {
    active++;
    maximum = Math.max(maximum, active);
    assert.equal(active, 1, "native lock must never be contested");
    try {
      if (action === "download" && options.fileName === "first.mp4") {
        await new Promise((resolve) => { release = resolve; started(); });
      } else {
        await new Promise((resolve) => setImmediate(resolve));
      }
      return action === "status" ? nativeStatus() : nativeResult({ fileName: options.fileName });
    } finally { active--; }
  });
  const first = drive.downloadToDrive({ url: "https://cdn.example/first", fileName: "first.mp4" });
  const second = drive.downloadToDrive({ url: "https://cdn.example/second", fileName: "second.mp4" });
  await downloading;
  const beforeStatus = calls.length;
  let statusFinished = false;
  const read = drive.getDriveStatus().then((result) => { statusFinished = true; return result; });
  const route = drive.shouldUseDrive();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(calls.length, beforeStatus);
  assert.equal(statusFinished, false);
  assert.equal(store.get("mori_drive_enabled"), "true");
  release();
  const results = await Promise.all([first, second, read, route]);
  assert.equal(results[0].success, true);
  assert.equal(results[1].success, true);
  assert.equal(results[2].enabled, true);
  assert.equal(results[3], true);
  assert.equal(maximum, 1);
});

test("a rejected transfer or status read does not poison the native queue", { timeout: 3000 }, async () => {
  let firstStatus = true;
  let active = 0;
  const { drive, calls, store } = await setup(async (action, options) => {
    assert.equal(++active, 1);
    try {
      await new Promise((resolve) => setImmediate(resolve));
      if (action === "status" && firstStatus) { firstStatus = false; throw new Error("Status unavailable"); }
      if (action === "download" && options.fileName === "first.mp4") throw new Error("First upload failed");
      return action === "status" ? nativeStatus() : nativeResult();
    } finally { active--; }
  });
  store.set("mori_drive_enabled", "true");
  const initial = await Promise.allSettled([drive.getDriveStatus(), drive.getDriveStatus()]);
  assert.equal(initial[0].status, "rejected");
  assert.equal(initial[1].status, "fulfilled");
  const uploads = await Promise.allSettled([
    drive.downloadToDrive({ url: "https://cdn.example/first", fileName: "first.mp4" }),
    drive.downloadToDrive({ url: "https://cdn.example/second", fileName: "second.mp4" }),
  ]);
  assert.equal(uploads[0].status, "rejected");
  assert.match(uploads[0].reason.message, /First upload failed/);
  assert.equal(uploads[1].status, "fulfilled");
  assert.equal(uploads[1].value.success, true);
  assert.deepEqual(calls.filter((call) => call.action === "download").map((call) => call.options.fileName), ["first.mp4", "second.mp4"]);
});

test("configuration follow-up, connect, retry, saveBytes and acknowledge use the same queue", { timeout: 3000 }, async () => {
  let active = 0;
  const { drive, calls } = await setup(async (action) => {
    assert.equal(++active, 1);
    try {
      await new Promise((resolve) => setImmediate(resolve));
      if (["status", "connect", "disconnect", "setEnabled"].includes(action)) return nativeStatus();
      if (["configure", "acknowledge"].includes(action)) return {};
      return nativeResult();
    } finally { active--; }
  });
  globalThis.FileReader = class {
    readAsDataURL(blob) { blob.arrayBuffer().then((bytes) => { this.result = `data:application/pdf;base64,${Buffer.from(bytes).toString("base64")}`; this.onload(); }); }
  };
  await Promise.all([
    drive.changeDriveSettings("configure"), drive.changeDriveSettings("connect"),
    drive.changeDriveSettings("setEnabled", { enabled: true }), drive.changeDriveSettings("disconnect"),
    drive.retryDriveJob({ jobId: "job_1" }), drive.savePdfToDrive(new Uint8Array([1]), { fileName: "Album.pdf" }),
    drive.acknowledgeDriveReceipt("receipt_1"), drive.getDriveStatus(),
  ]);
  for (const action of ["configure", "connect", "setEnabled", "disconnect", "retry", "saveBytes", "acknowledge", "status"]) assert.ok(calls.some((call) => call.action === action));
  assert.equal(active, 0);
});

test("completed receipt validation preserves only safe metadata and permits missing completed in older mocks", async () => {
  const { drive } = await setup();
  assert.deepEqual(drive.validateDriveStatus(nativeStatus()).completed, []);
  const receipt = { ...nativeResult({ receiptId: "receipt_1" }), sourceUrl: "https://source.example/", title: "Album", path: "deleted-temp.mp4" };
  const result = drive.validateDriveStatus(nativeStatus({ completed: [receipt] })).completed[0];
  assert.equal(result.receiptId, "receipt_1");
  assert.equal(result.sourceUrl, receipt.sourceUrl);
  assert.equal("path" in result, false);
  assert.throws(() => drive.validateDriveStatus(nativeStatus({ completed: [{ ...receipt, receiptId: "../bad" }] })));
  assert.throws(() => drive.validateDriveStatus(nativeStatus({ completed: [nativeResult()] })));
  await assert.rejects(drive.acknowledgeDriveReceipt("../bad"));
});

test("history startup recovers receipts idempotently before acknowledging, without local file paths", async () => {
  const receipt = { ...nativeResult({ receiptId: "receipt_1", localDeleted: false }), sourceUrl: "https://source.example/", title: "Album", localUri: "file:///deleted-temp.mp4" };
  let completed = [receipt];
  const { drive, store, calls, events } = await setup(async (action, options) => {
    if (action === "acknowledge") {
      const saved = JSON.parse(store.get("mori_history"))[0];
      assert.equal(saved.driveFiles[0].receiptId, options.receiptId);
      completed = [];
      return {};
    }
    return nativeStatus({ completed });
  });
  await drive.getDriveStatus(); // A completion arrives before history's listener exists.
  assert.equal(calls.some((call) => call.action === "acknowledge"), false);
  store.set("mori_history", JSON.stringify([{ url: receipt.sourceUrl, title: "Existing album", favorite: true, driveFiles: [nativeResult()] }]));
  const history = await historyFlow(drive);
  await drive.getDriveStatus();
  await drive.getDriveStatus();
  history.syncDriveHistory([receipt, receipt]);
  await drive.getDriveStatus();
  const saved = JSON.parse(store.get("mori_history"))[0];
  assert.equal(saved.title, "Existing album");
  assert.equal(saved.favorite, true);
  assert.equal(saved.driveFiles.length, 1);
  assert.equal(saved.driveFiles[0].receiptId, receipt.receiptId);
  assert.equal(saved.driveFiles[0].localDeleted, false);
  assert.equal("localUri" in saved, false);
  assert.equal("localUri" in saved.driveFiles[0], false);
  assert.equal(events.some((event) => event.type === "mori_file_saved"), false);
  assert.ok(calls.some((call) => call.action === "acknowledge"));
  assert.ok(calls.length < 10, "acknowledgement must not recursively refresh status");
});

test("failed history writes leave receipts unacknowledged until persistence recovers", async () => {
  const receipt = { ...nativeResult({ receiptId: "receipt_1" }), sourceUrl: "https://source.example/", title: "Album" };
  let completed = [receipt];
  const { drive, store, calls } = await setup(async (action) => {
    if (action === "acknowledge") { completed = []; return {}; }
    return nativeStatus({ completed });
  });
  const write = localStorage.setItem;
  localStorage.setItem = (key, value) => { if (key === "mori_history") throw new Error("Quota exceeded"); write(key, value); };
  await historyFlow(drive);
  await drive.getDriveStatus();
  assert.equal(store.has("mori_history"), false);
  assert.equal(calls.some((call) => call.action === "acknowledge"), false);
  localStorage.setItem = write;
  await drive.getDriveStatus();
  await drive.getDriveStatus();
  assert.equal(JSON.parse(store.get("mori_history"))[0].driveFiles[0].receiptId, receipt.receiptId);
  assert.equal(calls.filter((call) => call.action === "acknowledge").length, 1);
});

test("successful quota fallback that drops a receipt is not grounds for acknowledgement", async () => {
  const receipt = { ...nativeResult({ receiptId: "receipt_1" }), sourceUrl: "https://source.example/", title: "Album" };
  const { drive, store, calls } = await setup(async () => nativeStatus({ completed: [receipt] }));
  const write = localStorage.setItem;
  localStorage.setItem = (key, value) => {
    if (key === "mori_history" && JSON.parse(value).some((item) => !item.favorite)) throw new Error("Quota exceeded");
    write(key, value);
  };
  const history = await historyFlow(drive);
  await drive.getDriveStatus();
  assert.equal(history.safeSetHistory([{ url: receipt.sourceUrl, title: "Album", driveFiles: [receipt] }]), true);
  assert.deepEqual(JSON.parse(store.get("mori_history")), []);
  assert.equal(calls.some((call) => call.action === "acknowledge"), false);
});

test("incognito receipt recovery neither writes history nor acknowledges", async () => {
  const receipt = { ...nativeResult({ receiptId: "receipt_1" }), sourceUrl: "https://source.example/", title: "Album" };
  const { drive, store, calls, w } = await setup(async () => nativeStatus({ completed: [receipt] }));
  store.set("mori_incognito", "true");
  const history = await historyFlow(drive);
  await drive.getDriveStatus();
  history.syncDriveHistory([receipt]);
  w.dispatchEvent(new CustomEvent("mori_drive_file_saved", { detail: { ...receipt, url: receipt.sourceUrl } }));
  await drive.getDriveStatus();
  assert.equal(store.has("mori_history"), false);
  assert.equal(calls.some((call) => call.action === "acknowledge"), false);
});

test("a direct transfer receipt is acknowledged only after the save event persists it", async () => {
  const receipt = { ...nativeResult({ receiptId: "receipt_1" }), sourceUrl: "https://source.example/", title: "Album" };
  const { drive, store, calls, events } = await setup(async (action) => {
    if (action === "download") return receipt;
    if (action === "acknowledge") { assert.equal(JSON.parse(store.get("mori_history"))[0].driveFiles[0].receiptId, receipt.receiptId); return {}; }
    return nativeStatus();
  });
  await historyFlow(drive);
  const result = await drive.downloadToDrive({ url: "https://cdn.example/video", sourceUrl: receipt.sourceUrl, title: receipt.title });
  assert.equal(result.receiptId, receipt.receiptId);
  assert.equal(events.filter((event) => event.type === "mori_drive_file_saved")[0].detail.receiptId, receipt.receiptId);
  assert.equal(calls.filter((call) => call.action === "acknowledge").length, 1);
});
