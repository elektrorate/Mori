import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { test } from "node:test";
import assert from "node:assert/strict";

const java = readFileSync(new URL("./app/src/main/java/com/mori/downloader/ShareActivity.java", import.meta.url), "utf8");
const declaration = java.slice(java.indexOf("String driveShim ="), java.indexOf("String js = driveShim"));
const shim = [...declaration.matchAll(/"(?:\\.|[^"\\])*"/g)].map(([literal]) => JSON.parse(literal)).join("");

function bridge(response, { raw = false, failure = false } = {}) {
  const requests = [];
  const window = { Capacitor: { getPlatform: () => "web" }, __moriShareCallbacks: { http: () => {} } };
  window.MoriShareBridge = {
    driveRequest(json, id) {
      if (failure) throw new Error("private native exception details");
      requests.push(JSON.parse(json));
      const callback = window.__moriShareCallbacks[id];
      delete window.__moriShareCallbacks[id];
      callback(raw ? response : JSON.stringify(response));
    },
  };
  runInNewContext(shim, { window });
  return { request: window.Capacitor.Plugins.MoriDrive.request, requests, window };
}

test("share shim presents Android and round-trips the native status contract", async () => {
  const status = { connected: true, enabled: false, folderId: "folder", folderName: "Chosen", pending: [],
    completed: [{ receiptId: "job", sourceUrl: "https://www.facebook.com/watch?v=987", title: "Video",
      driveFileId: "file", uri: "https://drive.google.com/file/d/file/view", fileName: "video.mp4", localDeleted: true }] };
  const { request, requests, window } = bridge(status);
  assert.equal(window.Capacitor.getPlatform(), "android");
  assert.deepEqual(JSON.parse(JSON.stringify(await request({ action: "status", options: {} }))), status);
  assert.deepEqual(requests, [{ action: "status", options: {} }]);
  assert.deepEqual(Object.keys(window.__moriShareCallbacks), ["http"]);
});

test("verified uploads resolve and preserve cleanup state without a local path", async () => {
  const result = { ok: true, uploaded: true, driveFileId: "file", fileName: "report.pdf",
    uri: "https://drive.google.com/file/d/file/view", localDeleted: true, receiptId: "job", sourceUrl: "https://source.example/document" };
  const { request } = bridge(result);
  const received = await request({ action: "saveBytes", options: { fileName: "report.pdf", data: "cGRm" } });
  assert.deepEqual(JSON.parse(JSON.stringify(received)), result);
  assert.equal("localUri" in received, false);
});

test("Drive requests preserve the scraper's native/share HTTP callback alias", async () => {
  const { request, window } = bridge({ connected: false, enabled: false, pending: [] });
  assert.equal(window.__moriShareCallbacks, window.__moriNativeCallbacks);
  let received = false;
  window.__moriNativeCallbacks.req_scraper = () => { received = true; };
  await request({ action: "status", options: {} });
  window.__moriShareCallbacks.req_scraper();
  assert.equal(received, true);
});

test("confirmed cloud with failed cleanup still resolves for cleanup-only retry", async () => {
  const { request } = bridge({ ok: false, uploaded: true, localDeleted: false,
    driveFileId: "file", error: "CLOUD_HISTORY_WRITE_FAILED" });
  const result = await request({ action: "retry", options: { jobId: "job" } });
  assert.equal(result.uploaded, true);
  assert.equal(result.localDeleted, false);
});

test("unconfirmed uploads reject instead of allowing local fallback", async () => {
  const { request } = bridge({ ok: false, uploaded: false, localDeleted: false, error: "DRIVE_HTTP_403" });
  await assert.rejects(request({ action: "download", options: {} }), /DRIVE_HTTP_403/);
});

test("malformed native results and bridge exceptions expose only safe errors", async () => {
  await assert.rejects(bridge("not JSON", { raw: true }).request({ action: "status" }), /Invalid native Drive response/);
  const { request, window } = bridge(null, { failure: true });
  await assert.rejects(request({ action: "status" }), /Native Drive unavailable/);
  assert.deepEqual(Object.keys(window.__moriShareCallbacks), ["http"]);
});

test("receipt acknowledgement is explicit and does not trigger another transfer", async () => {
  const status = { connected: true, enabled: true, pending: [], completed: [] };
  const { request, requests } = bridge(status);
  await request({ action: "acknowledge", options: { receiptId: "job" } });
  assert.deepEqual(requests, [{ action: "acknowledge", options: { receiptId: "job" } }]);
});

test("failed fresh verification rejects even after a historical upload confirmation", async () => {
  const { request } = bridge({ ok: false, uploaded: false, localDeleted: false, jobId: "job", error: "LOCAL_CONTENT_CHANGED" });
  await assert.rejects(request({ action: "retry", options: { jobId: "job" } }), /LOCAL_CONTENT_CHANGED/);
});
