import test from "node:test";
import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import vm from "node:vm";

const source = (path) => readFile(new URL(`../${path}`, import.meta.url), "utf8");
const locales = ["en", "id", "ja", "ko", "zh", "ar", "ru", "tl", "hi"];

test("native names and versions change without losing the app identity or MSI upgrade code", async () => {
  const pkg = JSON.parse(await source("package.json"));
  const tauri = JSON.parse(await source("src-tauri/tauri.conf.json"));
  const capacitor = JSON.parse(await source("capacitor.config.json"));
  assert.equal(pkg.name, "host-ia");
  assert.equal(pkg.version, "4.4.1");
  assert.equal(tauri.productName, "Host-ia");
  assert.equal(tauri.version, pkg.version);
  assert.equal(tauri.app.windows[0].title, "Host-ia");
  assert.equal(tauri.identifier, "com.mori.downloader");
  assert.equal(tauri.bundle.windows.wix.upgradeCode, "cdfcc127-0c2b-520e-a626-01f20344394e");
  assert.equal(capacitor.appName, "Host-ia");
  assert.equal(capacitor.appId, tauri.identifier);
  const android = await source("android/app/build.gradle");
  assert.match(android, /versionName "4\.4\.1"/);
  assert.match(android, /versionCode 21/);
  assert.match(android, /applicationId "com\.mori\.downloader"/);
});

test("app, quick save and landing expose Host-ia titles and local favicon assets", async () => {
  for (const path of ["public/index.html", "public/share.html", "landing/index.html"]) {
    const html = await source(path);
    assert.match(html, /<title>Host-ia[^<]*<\/title>/, path);
    for (const asset of ["logo.svg", "favicon.ico", "favicon-16x16.png", "favicon-32x32.png", "apple-touch-icon.png"]) {
      assert.ok(html.includes(`href="assets/${asset}"`), `${path}: ${asset}`);
      await access(new URL(`../${path.split("/")[0]}/assets/${asset}`, import.meta.url));
    }
  }
});

test("header, About and landing use accessible logos with white backplates", async () => {
  const app = await source("public/index.html");
  const update = await source("public/js/modules/update.js");
  const landing = await source("landing/index.html");
  assert.match(app, /<h1><img[^>]+src="assets\/logo\.svg"[^>]+alt="Host-ia"/);
  assert.match(update, /<img[^>]+about-brand-logo[^>]+src="assets\/logo\.svg"[^>]+alt="Host-ia"/);
  assert.equal((update.match(/url: REPO_URL/g) || []).length, 2);
  assert.match(landing, /class="nav-logo" aria-label="Host-ia home"/);
  assert.match(landing, /class="footer-logo" aria-label="Host-ia home"/);
  assert.equal((landing.match(/<img src="assets\/logo\.svg"/g) || []).length, 2);
  assert.match(await source("public/css/base.css"), /\.brand-logo\s*\{[^}]*background: #fff;/);
  const landingCss = await source("landing/css/style.css");
  for (const selector of ["nav-logo", "footer-logo"]) {
    assert.match(landingCss, new RegExp(`\\.${selector} img\\s*\\{[^}]*background: #fff;`));
  }
  for (const path of ["public/css/home.css", "public/css/modals.css"]) {
    const css = await source(path);
    assert.match(css, /content: "Host-ia"/);
    assert.doesNotMatch(css, /content: "MORI"/);
  }
});

test("all nine locales share the Host-ia fork while preserving original authorship", async () => {
  for (const locale of locales) {
    const text = await source(`public/js/i18n/locales/${locale}.js`);
    const { default: strings } = await import(`data:text/javascript;base64,${Buffer.from(text).toString("base64")}`);
    for (const key of ["about-text", "label-about", "label-shareapp", "share-msg", "share-panel-title"]) {
      assert.ok(strings[key].includes("Host-ia"), `${locale}: ${key}`);
    }
    assert.ok(strings["about-text"].includes("coflyn"), locale);
    assert.ok(strings["share-msg"].includes("https://github.com/elektrorate/Mori"), locale);
    // Actual save paths and the Japanese folder deletion warning are not branding.
    const display = JSON.stringify(strings)
      .replaceAll("https://github.com/coflyn/Mori", "")
      .replaceAll("https://github.com/elektrorate/Mori", "")
      .replaceAll("Download/Mori", "")
      .replaceAll("ダウンロード/Mori", "")
      .replaceAll("Moriフォルダ", "");
    assert.doesNotMatch(display, /Mori|MORI/, locale);
  }
  const indonesian = await source("public/js/i18n/locales/id.js");
  assert.equal((indonesian.match(/memori/g) || []).length, 3);
});

test("privacy salt, legacy folders, preference keys and native bridge names stay internal", async () => {
  const auth = await source("public/js/modules/authManager.js");
  assert.match(auth, /new TextEncoder\(\)\.encode\("mori:" \+ pin\)/);
  assert.match(auth, /const s = "mori:" \+ pin/);
  assert.match(auth, /localStorage\.getItem\("mori_pin"\)/);
  const storage = await source("public/js/modules/settings/storage.js");
  assert.match(storage, /localStorage\.setItem\("mori_download_path", "Download\/Mori"\)/);
  assert.match(storage, /localStorage\.setItem\("mori_music_path", "Download\/Mori\/Music"\)/);
  assert.match(storage, /data-path="\$\{p\}"/);
  assert.match(storage, /"Host-ia: "/);
  const share = await source("public/js/share.js");
  assert.match(share, /window\.MoriShareBridge/);
  assert.match(share, /window\.onMoriConfigReady/);
  assert.match(share, /window\.__MORI_SHARE_URL/);
  const drive = await source("public/js/modules/drive.js");
  assert.match(drive, /MoriDrive/);
  assert.match(drive, /Host-ia Google Drive upload test/);
  assert.match(drive, /Host-ia_drive_test_/);
});

test("binary releases use the fork while OTA, canonical URLs and historical screenshots remain upstream", async () => {
  const core = await source("public/js/modules/core.js");
  assert.match(core, /GITHUB_REPO = "coflyn\/Mori"/);
  assert.match(core, /REPO_URL = "https:\/\/github\.com\/elektrorate\/Mori"/);
  assert.match(core, /UPDATE_CHECK_URL = "https:\/\/api\.github\.com\/repos\/elektrorate\/Mori\/releases\/latest"/);
  const ota = await source("public/js/modules/updateScrapers.js");
  assert.match(ota, /raw\.githubusercontent\.com\/\$\{GITHUB_REPO\}\/main\/public\/scrapers-version\.json/);
  assert.match(ota, /raw\.githubusercontent\.com\/\$\{GITHUB_REPO\}\/main\/public\/js\/scrapers\/bundle\.js/);
  const html = await source("landing/index.html");
  assert.match(html, /rel="canonical" href="https:\/\/getmori\.vercel\.app\/"/);
  assert.match(html, /Historical upstream Mori screenshots/);
  assert.match(html, /not a\s+guarantee that a release is available yet/);
  assert.doesNotMatch(html, /github\.com\/coflyn\/Mori\/releases|assets\/logo\.png|gethost-ia/);
  const schema = JSON.parse(html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)[1]);
  assert.equal(schema["@graph"].find((item) => item["@type"] === "SoftwareApplication").name, "Host-ia");
});

test("landing rejects old cached upstream binaries and safely handles an unpublished fork", async () => {
  const text = await source("landing/js/main.js");
  const releaseFunction = text.slice(text.indexOf("  function syncDownloadRelease("), text.indexOf("  // Initial check for cached download meta"));
  const applied = [];
  const requested = [];
  const store = new Map([
    ["mori_latest_release_data", JSON.stringify({ version: "v99.0.0", assets: { android: "https://github.com/coflyn/Mori/releases/download/v99/upstream.apk" } })],
    ["mori_latest_release_time", String(Date.now())],
  ]);
  const context = vm.createContext({
    GITHUB_RELEASE_KEY: "mori_latest_release_data", GITHUB_RELEASE_TIME: "mori_latest_release_time", ONE_HOUR: 3600000,
    sessionStorage: { getItem: (key) => store.get(key), setItem: (key, value) => store.set(key, value) },
    applyDownloadMeta: (...args) => applied.push(args),
    fetch: async (url) => { requested.push(url); return { ok: false, status: 404 }; },
  });
  vm.runInContext(`${releaseFunction}\nsyncDownloadRelease("v99.0.0");`, context);
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(requested, ["https://api.github.com/repos/elektrorate/Mori/releases/latest"]);
  assert.deepEqual(applied, []);

  context.fetch = async () => ({ ok: true, json: async () => ({ tag_name: "v4.4.1", assets: [{ name: "Host-ia.apk", browser_download_url: "https://github.com/elektrorate/Mori/releases/download/v4.4.1/Host-ia.apk" }] }) });
  vm.runInContext("syncDownloadRelease();", context);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(applied[0][0], "v4.4.1");
  assert.match(applied[0][1].android, /github\.com\/elektrorate\/Mori/);
  assert.equal(JSON.parse(store.get("mori_latest_release_data")).repo, "elektrorate/Mori");
});
