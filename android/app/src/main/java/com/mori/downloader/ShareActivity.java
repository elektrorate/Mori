package com.mori.downloader;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ShareActivity extends AppCompatActivity {

    static {
        try {
            System.loadLibrary("morisec");
        } catch (Throwable ignored) {}
    }

    private static final String TAG = "MoriShare";
    private static final String CHANNEL_ID = "mori_download";
    private static final int NOTIF_ID_BASE = 4000;

    private WebView webView;
    private String sharedUrl = "";
    private ExecutorService executor = Executors.newCachedThreadPool();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private int notifCounter = 0;

    private static final CookieJar memoryCookieJar = new CookieJar() {
        private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            cookieStore.put(url.host(), cookies);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new ArrayList<>();
        }
    };

    private static final OkHttpClient sharedClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(memoryCookieJar)
            .build();

    private static final OkHttpClient resourceClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();

        // Extract shared URL from intent
        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                // Extract first URL from text
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https?://[^\\s]+")
                        .matcher(text);
                sharedUrl = m.find() ? m.group() : text.trim();
            }
        }

        if (sharedUrl.isEmpty()) {
            finish();
            return;
        }

        if (getWindow() != null) {
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        setupWebView();
        setContentView(webView);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        // Do NOT call executor.shutdownNow() here so background download tasks keep running!
        super.onDestroy();
    }

    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Expose JavascriptInterface
        MoriShareBridge shareBridge = new MoriShareBridge();
        webView.addJavascriptInterface(shareBridge, "MoriShareBridge");
        webView.addJavascriptInterface(shareBridge, "MoriMainBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (MoriSharePolicy.page(url) && MoriSharePolicy.page(view.getUrl())) injectConfig();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!MoriSharePolicy.page(url)) {
                    view.removeJavascriptInterface("MoriShareBridge");
                    view.removeJavascriptInterface("MoriMainBridge");
                    view.stopLoading();
                }
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (request.isForMainFrame() && MoriSharePolicy.page(url)) return false;
                if (request.isForMainFrame()) openExternal(url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (MoriSharePolicy.page(url)) return false;
                openExternal(url);
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return resource(request.getUrl().toString(), request.isForMainFrame(), request.getMethod(), request.getRequestHeaders());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                // No frame/request metadata: serve APK assets only, never an unknown network document.
                return resource(url, false, "GET", java.util.Collections.emptyMap());
            }
        });

        webView.loadUrl(MoriSharePolicy.PAGE);
    }

    private void openExternal(String url) {
        if (!MoriSharePolicy.external(url)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception ignored) {}
    }

    private WebResourceResponse blockedResource() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
            java.util.Collections.singletonMap("X-Content-Type-Options", "nosniff"),
            new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse resource(String url, boolean mainFrame, String method, java.util.Map<String, String> headers) {
        if (!"GET".equals(method) || mainFrame && !MoriSharePolicy.page(url)) return blockedResource();
        String asset = MoriSharePolicy.asset(url);
        if (asset != null) {
            try {
                String mime = asset.endsWith(".js") ? "application/javascript" : asset.endsWith(".css") ? "text/css"
                    : asset.endsWith(".html") ? "text/html" : android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(asset.substring(asset.lastIndexOf('.') + 1));
                if (mime == null) mime = "application/octet-stream";
                java.util.Map<String, String> responseHeaders = new HashMap<>();
                responseHeaders.put("Content-Security-Policy", MoriSharePolicy.CSP);
                responseHeaders.put("X-Content-Type-Options", "nosniff");
                responseHeaders.put("Cache-Control", "no-store");
                return new WebResourceResponse(mime, "UTF-8", 200, "OK", responseHeaders, getAssets().open("public/" + asset));
            } catch (Exception ignored) { return blockedResource(); }
        }
        if (mainFrame || !MoriSharePolicy.external(url) || !url.startsWith("https://")) return blockedResource();
        try {
            HttpUrl current = HttpUrl.parse(url);
            for (int redirects = 0; redirects <= 5; redirects++) {
                Request.Builder request = new Request.Builder().url(current).header("User-Agent", "Mozilla/5.0 (Linux; Android) Mori/Share");
                for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                    if ("Range".equalsIgnoreCase(header.getKey())) request.header("Range", header.getValue());
                }
                Response response = resourceClient.newCall(request.build()).execute();
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    HttpUrl next = location == null ? null : current.resolve(location);
                    response.close();
                    if (next == null || !next.isHttps() || !MoriSharePolicy.external(next.toString())) return blockedResource();
                    current = next;
                    continue;
                }
                String type = response.header("Content-Type");
                if (!response.isSuccessful() || response.body() == null || !MoriSharePolicy.remoteType(current.toString(), type)) {
                    response.close();
                    return blockedResource();
                }
                java.util.Map<String, String> responseHeaders = new HashMap<>();
                responseHeaders.put("X-Content-Type-Options", "nosniff");
                responseHeaders.put("Content-Security-Policy", "default-src 'none'; sandbox");
                responseHeaders.put("Access-Control-Allow-Origin", "https://localhost");
                for (String name : new String[]{"Content-Range", "Accept-Ranges", "Content-Length"}) {
                    if (response.header(name) != null) responseHeaders.put(name, response.header(name));
                }
                InputStream stream = new FilterInputStream(response.body().byteStream()) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { response.close(); }
                    }
                };
                return new WebResourceResponse(type.split(";", 2)[0], null, response.code(), "OK", responseHeaders, stream);
            }
        } catch (Exception ignored) {}
        return blockedResource();
    }

    private void injectConfig() {
        if (webView == null || !MoriSharePolicy.page(webView.getUrl())) return;
        SharedPreferences prefs = getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        String lang = prefs.getString("mori_lang", "en");
        String theme = prefs.getString("mori_theme", "dark");
        String font = prefs.getString("mori_font", "display");
        String preferServer = prefs.getString("mori_prefer_server", "ask");
        String downloadPath = prefs.getString("mori_download_path", "Mori");
        String autoFolder = prefs.getString("mori_auto_folder", "true");
        String filenameTemplate = prefs.getString("mori_filename", "title");

        String escapedUrl = sharedUrl
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", "");
        // The share WebView has no Capacitor Bridge. Expose only the token-free native Drive request API.
        String driveShim = "window.Capacitor=window.Capacitor||{};" +
                "window.Capacitor.getPlatform=function(){return 'android';};" +
                "window.Capacitor.Plugins=window.Capacitor.Plugins||{};" +
                "window.__moriNativeCallbacks=window.__moriNativeCallbacks||window.__moriShareCallbacks||{};" +
                "window.__moriShareCallbacks=window.__moriNativeCallbacks;" +
                "window.Capacitor.Plugins.MoriDrive={request:function(args){return new Promise(function(resolve,reject){" +
                "var id='drive_'+Date.now()+'_'+Math.random().toString(36).slice(2);" +
                "window.__moriShareCallbacks=window.__moriShareCallbacks||{};" +
                "window.__moriShareCallbacks[id]=function(raw){try{var value=JSON.parse(raw);" +
                "if(!value.uploaded&&(value.ok===false||value.error))reject(new Error(value.error||'Drive failed'));" +
                "else resolve(value);}catch(e){reject(new Error('Invalid native Drive response'));}};" +
                "try{window.MoriShareBridge.driveRequest(JSON.stringify(args),id);}catch(e){" +
                "delete window.__moriShareCallbacks[id];reject(new Error('Native Drive unavailable'));}" +
                "});}};";
        String js = driveShim + "window.__MORI_SHARE_URL = '" + escapedUrl + "';" +
                "try { " +
                "  localStorage.setItem('mori_lang', '" + lang + "');" +
                "  localStorage.setItem('mori_theme', '" + theme + "');" +
                "  localStorage.setItem('mori_font', '" + font + "');" +
                "  localStorage.setItem('mori_prefer_server', '" + preferServer + "');" +
                "  localStorage.setItem('mori_download_path', '" + downloadPath + "');" +
                "  localStorage.setItem('mori_auto_folder', '" + autoFolder + "');" +
                "  localStorage.setItem('mori_filename', '" + filenameTemplate + "');" +
                "} catch(e) {};" +
                "if (typeof window.onMoriConfigReady === 'function') window.onMoriConfigReady();";
        webView.evaluateJavascript(js, null);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Mori Downloads", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Mori download notifications");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void showDownloadCompleteNotification(String title) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download Complete ✓")
                .setContentText(title)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        nm.notify(NOTIF_ID_BASE + (notifCounter++), b.build());
    }

    private void showDownloadFailedNotification(String title, String reason) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download Failed")
                .setContentText(title + ": " + reason)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        nm.notify(NOTIF_ID_BASE + (notifCounter++), b.build());
    }

    public class MoriShareBridge {
        @JavascriptInterface
        public void driveRequest(String requestJson, String callbackId) {
            try {
                JSONObject request = new JSONObject(requestJson);
                JSONObject options = request.optJSONObject("options");
                if (options == null) options = new JSONObject();
                MoriDriveBackend backend = MoriDriveBackend.get(ShareActivity.this);
                String action = request.getString("action");
                if ("status".equals(action)) driveCallback(callbackId, backend.status());
                else if ("acknowledge".equals(action)) driveCallback(callbackId, backend.acknowledge(options.getString("receiptId")));
                else if ("download".equals(action) || "saveBytes".equals(action)) {
                    String jobId = backend.createJob(options, "saveBytes".equals(action), true);
                    backend.run(jobId, options.optString("data", null), result -> driveCallback(callbackId, result));
                } else if ("retry".equals(action)) {
                    backend.run(options.getString("jobId"), null, result -> driveCallback(callbackId, result));
                } else if ("setEnabled".equals(action) && options.opt("enabled") instanceof Boolean) {
                    backend.setEnabled(options.getBoolean("enabled"));
                    driveCallback(callbackId, backend.status());
                } else if ("disconnect".equals(action)) {
                    backend.disconnect();
                    driveCallback(callbackId, backend.status());
                } else {
                    driveCallback(callbackId, new JSONObject().put("ok", false).put("error", "Use Drive settings in the main app"));
                }
            } catch (Exception e) {
                try { driveCallback(callbackId, new JSONObject().put("ok", false).put("error", MoriDriveBackend.safeError(e))); }
                catch (Exception ignored) {}
            }
        }

        private void driveCallback(String id, JSONObject result) {
            final String json = result.toString();
            mainHandler.post(() -> {
                if (webView == null || isDestroyed() || !MoriSharePolicy.page(webView.getUrl())) return;
                String key = JSONObject.quote(id);
                webView.evaluateJavascript("if(window.__moriShareCallbacks&&window.__moriShareCallbacks[" + key + "]){" +
                    "var cb=window.__moriShareCallbacks[" + key + "];delete window.__moriShareCallbacks[" + key + "];" +
                    "cb(" + JSONObject.quote(json) + ");}", null);
            });
        }

        @JavascriptInterface
        public String getEngineSecurityKey(String challenge) {
            try {
                return MainActivity.getEngineSecurityKeyNative(ShareActivity.this, challenge);
            } catch (Throwable e) {
                Log.e(TAG, "getEngineSecurityKey error", e);
                return "UNAUTHORIZED_CLONE";
            }
        }

        @JavascriptInterface
        public String getScrapersBinaryBase64() {
            try (InputStream is = getAssets().open("public/js/scrapers.bin")) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int nRead;
                byte[] data = new byte[16384];
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                return android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                Log.e(TAG, "Failed to read scrapers.bin from assets", e);
                return "";
            }
        }

        /**
         * Asynchronous HTTP request bridge to keep WebView UI thread completely unblocked.
         * Runs OkHttp request on background executor and calls JS callback with result.
         */
        @JavascriptInterface
        public void httpRequestAsync(String optionsJson, String reqId) {
            executor.execute(() -> {
                String result = httpRequest(optionsJson);
                mainHandler.post(() -> {
                    if (webView == null || isDestroyed() || !MoriSharePolicy.page(webView.getUrl())) return;
                    String js = "if (window.__moriShareCallbacks && window.__moriShareCallbacks['" + reqId + "']) { " +
                                "  window.__moriShareCallbacks['" + reqId + "'](" + JSONObject.quote(result) + "); " +
                                "  delete window.__moriShareCallbacks['" + reqId + "']; " +
                                "}";
                    webView.evaluateJavascript(js, null);
                });
            });
        }

        /**
         * Synchronous HTTP request bridge (mirrors CapacitorHttp API shape).
         * Called from httpHelper.js via window.MoriShareBridge.
         * NOTE: Must be called off the main thread (Android enforces this).
         *
         * @param optionsJson JSON: { url, method, headers, data, params, responseType }
         * @return JSON: { status, headers, data }
         */
        @JavascriptInterface
        public String httpRequest(String optionsJson) {
            try {
                JSONObject opts = new JSONObject(optionsJson);
                String url        = opts.getString("url");
                String method     = opts.optString("method", "GET").toUpperCase();
                JSONObject hdrsIn = opts.optJSONObject("headers");
                String body       = opts.optString("data", null);
                JSONObject params = opts.optJSONObject("params");
                String respType   = opts.optString("responseType", "text");

                // Append query params
                if (params != null && params.length() > 0) {
                    StringBuilder sb = new StringBuilder(url.contains("?") ? url + "&" : url + "?");
                    Iterator<String> keys = params.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        sb.append(Uri.encode(k)).append("=").append(Uri.encode(params.getString(k)));
                        if (keys.hasNext()) sb.append("&");
                    }
                    url = sb.toString();
                }

                OkHttpClient client = sharedClient;

                Headers.Builder hb = new Headers.Builder();
                if (hdrsIn != null) {
                    Iterator<String> keys = hdrsIn.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        hb.add(k, hdrsIn.getString(k));
                    }
                }

                Request.Builder rb = new Request.Builder().url(url).headers(hb.build());
                if ("POST".equals(method) || "PUT".equals(method)) {
                    String ct = hdrsIn != null
                            ? hdrsIn.optString("Content-Type", "application/x-www-form-urlencoded")
                            : "application/x-www-form-urlencoded";
                    RequestBody rb2 = body != null
                            ? RequestBody.create(body, MediaType.parse(ct))
                            : RequestBody.create("", MediaType.parse(ct));
                    rb = "PUT".equals(method) ? rb.put(rb2) : rb.post(rb2);
                } else if ("DELETE".equals(method)) {
                    rb = rb.delete();
                }

                Response res = client.newCall(rb.build()).execute();

                JSONObject resHeaders = new JSONObject();
                for (String name : res.headers().names()) {
                    resHeaders.put(name.toLowerCase(), res.header(name));
                }

                String resData;
                if ("arraybuffer".equals(respType) || "blob".equals(respType)) {
                    byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
                    resData = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                } else {
                    resData = res.body() != null ? res.body().string() : "";
                }

                JSONObject result = new JSONObject();
                result.put("status", res.code());
                result.put("headers", resHeaders);
                result.put("data", resData);
                result.put("url", res.request().url().toString());
                return result.toString();

            } catch (Exception e) {
                Log.e(TAG, "httpRequest error: " + e.getMessage());
                return "{\"status\":0,\"data\":\"\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        }

        /**
         * Download a file natively via OkHttp.
         * Called from share.js after scraping succeeds.
         *
         * @param url         Direct media URL
         * @param filename    Target filename (will be sanitized)
         * @param folder      Subfolder under Downloads/ (e.g. "Mori/TikTok")
         * @param headersJson Extra headers JSON or empty string
         * @param title       Title shown in completion notification
         */
        @JavascriptInterface
        public void downloadFile(String url, String filename, String folder, String headersJson, String title) {
            // Capture native destination now, before queueing. Never fall through to local storage on a Drive error.
            try {
                MoriDriveBackend backend = MoriDriveBackend.get(ShareActivity.this);
                if (backend.isEnabled()) {
                    JSONObject options = new JSONObject().put("url", url).put("fileName", filename)
                        .put("sourceUrl", sharedUrl).put("title", title == null ? filename : title)
                        .put("headers", headersJson == null || headersJson.isEmpty() ? new JSONObject() : new JSONObject(headersJson));
                    String jobId = backend.createJob(options, false, true);
                    backend.run(jobId, null, result -> mainHandler.post(() -> {
                        if (webView == null || isDestroyed() || !MoriSharePolicy.page(webView.getUrl())) return;
                        if (result.optBoolean("uploaded")) {
                            webView.evaluateJavascript("window.onDownloadComplete&&window.onDownloadComplete(" +
                                JSONObject.quote(filename) + ",''," + result.toString() + ");", null);
                        } else {
                            String error = result.optString("error", "DRIVE_OPERATION_FAILED");
                            showDownloadFailedNotification(title, error);
                            webView.evaluateJavascript("window.onDownloadFailed&&window.onDownloadFailed(" +
                                JSONObject.quote(filename) + "," + JSONObject.quote(error) + ");", null);
                        }
                    }));
                    return;
                }
            } catch (Exception e) {
                final String error = MoriDriveBackend.safeError(e);
                mainHandler.post(() -> {
                    showDownloadFailedNotification(title, error);
                    if (webView != null && !isDestroyed()) webView.evaluateJavascript(
                        "window.onDownloadFailed&&window.onDownloadFailed(" + JSONObject.quote(filename) + "," +
                        JSONObject.quote(error) + ");", null);
                });
                return;
            }
            executor.execute(() -> {
                // Start Foreground Service for background protection
                try {
                    Intent sIntent = new Intent(ShareActivity.this, DownloadForegroundService.class);
                    sIntent.putExtra("title", title != null ? title : "Downloading Media...");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(sIntent);
                    } else {
                        startService(sIntent);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start DownloadForegroundService in ShareActivity: " + e.getMessage());
                }

                File tempFile = null;
                try {
                    OkHttpClient client = sharedClient;

                    Headers.Builder hb = new Headers.Builder();
                    hb.add("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36");
                    if (headersJson != null && !headersJson.isEmpty()) {
                        try {
                            JSONObject hdrs = new JSONObject(headersJson);
                            Iterator<String> keys = hdrs.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                hb.set(k, hdrs.getString(k));
                            }
                        } catch (Exception ignored) {}
                    }

                    Request req = new Request.Builder().url(url).headers(hb.build()).build();
                    Response res = client.newCall(req).execute();

                    if (!res.isSuccessful() || res.body() == null) {
                        final String errMsg = "HTTP " + res.code();
                        mainHandler.post(() -> {
                            showDownloadFailedNotification(title, errMsg);
                            webView.evaluateJavascript(
                                "window.onDownloadFailed && window.onDownloadFailed('" +
                                esc(filename) + "', '" + esc(errMsg) + "')", null);
                        });
                        return;
                    }

                    // Resolve custom target directory across storage (e.g. Movies/Mori, Music/Mori, Download/Mori, or custom)
                    boolean hasAllFiles = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        hasAllFiles = Environment.isExternalStorageManager();
                    }

                    File targetDir;
                    if (hasAllFiles && folder != null && !folder.trim().isEmpty()) {
                        String trimmed = folder.trim();
                        if (trimmed.startsWith("/")) {
                            targetDir = new File(trimmed);
                        } else {
                            targetDir = new File(Environment.getExternalStorageDirectory(), trimmed);
                        }
                    } else {
                        String sub = "Mori";
                        if (folder != null && folder.trim().toLowerCase().startsWith("download/")) {
                            sub = folder.trim().substring(9).trim();
                        }
                        targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub.isEmpty() ? "Mori" : sub);
                    }
                    if (!targetDir.exists()) {
                        if (!targetDir.mkdirs()) {
                            targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mori");
                            targetDir.mkdirs();
                        }
                    }

                    String sanitizedName = sanitize(filename);
                    int dotPos = sanitizedName.lastIndexOf('.');
                    if (dotPos > 0 && dotPos < sanitizedName.length() - 1) {
                        sanitizedName = sanitizedName.substring(0, dotPos) + "." + sanitizedName.substring(dotPos + 1).toLowerCase();
                    }
                    File targetFile = new File(targetDir, sanitizedName);
                    if (targetFile.exists()) {
                        int dot = sanitizedName.lastIndexOf('.');
                        String stem = dot > 0 ? sanitizedName.substring(0, dot) : sanitizedName;
                        String ext  = dot > 0 ? sanitizedName.substring(dot).toLowerCase() : "";
                        int c = 1;
                        while (targetFile.exists()) {
                            targetFile = new File(targetDir, stem + "_" + c + ext);
                            c++;
                        }
                    }

                    // Write to .tmp file first
                    tempFile = new File(targetDir, targetFile.getName() + ".tmp");

                    try (InputStream is = res.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    }

                    // Atomic rename from .tmp to final target file
                    if (!tempFile.renameTo(targetFile)) {
                        // Fallback copy if renameTo fails
                        try (InputStream in = new java.io.FileInputStream(tempFile);
                             FileOutputStream out = new FileOutputStream(targetFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                        tempFile.delete();
                    }

                    // Explicitly set modified timestamp to NOW (today's date)
                    targetFile.setLastModified(System.currentTimeMillis());

                    // MediaScanner scanFile trigger for Android Gallery indexing
                    final File saved = targetFile;
                    String savedName = saved.getName().toLowerCase();
                    String mimeType = null;
                    int dotIdx = savedName.lastIndexOf('.');
                    if (dotIdx >= 0 && dotIdx < savedName.length() - 1) {
                        String ext = savedName.substring(dotIdx + 1);
                        mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    }

                    if (mimeType == null) {
                        if (savedName.endsWith(".mp3") || savedName.endsWith(".m4a") || savedName.endsWith(".flac") || savedName.endsWith(".aac") || savedName.endsWith(".wav")) {
                            mimeType = "audio/*";
                        } else if (savedName.endsWith(".jpg") || savedName.endsWith(".jpeg") || savedName.endsWith(".png") || savedName.endsWith(".webp") || savedName.endsWith(".gif")) {
                            mimeType = "image/*";
                        } else if (savedName.endsWith(".mp4") || savedName.endsWith(".mov") || savedName.endsWith(".webm") || savedName.endsWith(".mkv")) {
                            mimeType = "video/*";
                        }
                    }

                    final String finalMime = mimeType;
                    android.media.MediaScannerConnection.scanFile(
                            getApplicationContext(),
                            new String[]{saved.getAbsolutePath()},
                            finalMime != null ? new String[]{finalMime} : null,
                            (path, uri) -> {
                                Log.d(TAG, "MediaScanner indexed: " + path + " -> " + uri);
                                if (uri != null) {
                                    try {
                                        ContentValues values = new ContentValues();
                                        long nowSec = System.currentTimeMillis() / 1000;
                                        long nowMs = System.currentTimeMillis();
                                        values.put(MediaStore.MediaColumns.DATE_MODIFIED, nowSec);
                                        values.put(MediaStore.MediaColumns.DATE_ADDED, nowSec);
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            values.put(MediaStore.MediaColumns.DATE_TAKEN, nowMs);
                                        }
                                        values.put(MediaStore.Video.VideoColumns.DATE_TAKEN, nowMs);
                                        values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, nowMs);
                                        getContentResolver().update(uri, values, null, null);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Failed to update MediaStore timestamp: " + e.getMessage());
                                    }
                                }
                            });

                    // Broadcast intent fallback for older gallery apps
                    try {
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(Uri.fromFile(saved));
                        sendBroadcast(mediaScanIntent);
                    } catch (Exception ignored) {}

                    // Native SharedPreferences update for background history persistence
                    try {
                        SharedPreferences prefs = getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
                        String existingListStr = prefs.getString("mori_pending_share_history_list", "[]");
                        org.json.JSONArray list;
                        try {
                            list = new org.json.JSONArray(existingListStr);
                        } catch (Exception e) {
                            list = new org.json.JSONArray();
                        }
                        
                        boolean updated = false;
                        String isVideo = saved.getAbsolutePath().toLowerCase().endsWith(".mp4") ? "VIDEO" : "IMAGE";
                        for (int i = 0; i < list.length(); i++) {
                            Object rawObj = list.opt(i);
                            JSONObject item = null;
                            if (rawObj instanceof JSONObject) {
                                item = (JSONObject) rawObj;
                            } else if (rawObj instanceof String) {
                                try { item = new JSONObject((String) rawObj); } catch (Exception ignored) {}
                            }

                            if (item != null) {
                                org.json.JSONArray localFiles = item.optJSONArray("localFiles");
                                if (localFiles == null) localFiles = new org.json.JSONArray();
                                
                                JSONObject fObj = new JSONObject();
                                fObj.put("path", saved.getAbsolutePath());
                                fObj.put("uri", saved.getAbsolutePath());
                                fObj.put("type", isVideo);
                                fObj.put("title", title != null ? title : filename);
                                localFiles.put(fObj);

                                item.put("localFiles", localFiles);
                                item.put("localUri", saved.getAbsolutePath());
                                list.put(i, item);
                                updated = true;
                                break;
                            }
                        }

                        if (!updated) {
                            JSONObject newItem = new JSONObject();
                            newItem.put("title", title != null ? title : "Media");
                            newItem.put("url", sharedUrl);
                            newItem.put("sourceUrl", sharedUrl);
                            newItem.put("timestamp", System.currentTimeMillis());
                            newItem.put("localUri", saved.getAbsolutePath());

                            org.json.JSONArray localFiles = new org.json.JSONArray();
                            JSONObject fObj = new JSONObject();
                            fObj.put("path", saved.getAbsolutePath());
                            fObj.put("uri", saved.getAbsolutePath());
                            fObj.put("type", isVideo);
                            fObj.put("title", title != null ? title : filename);
                            localFiles.put(fObj);
                            newItem.put("localFiles", localFiles);

                            list.put(newItem);
                        }

                        prefs.edit().putString("mori_pending_share_history_list", list.toString()).commit();
                    } catch (Exception e) {
                        Log.e(TAG, "Native update pending history error: " + e.getMessage());
                    }

                    mainHandler.post(() -> {
                        showDownloadCompleteNotification(title);
                        if (webView != null) {
                            webView.evaluateJavascript(
                                "window.onDownloadComplete && window.onDownloadComplete('" +
                                esc(filename) + "', '" + esc(saved.getAbsolutePath()) + "')", null);
                        }
                    });

                } catch (Exception e) {
                    if (tempFile != null && tempFile.exists()) {
                        tempFile.delete();
                    }
                    Log.e(TAG, "downloadFile error: " + e.getMessage());
                    mainHandler.post(() -> {
                        showDownloadFailedNotification(title, e.getMessage());
                        webView.evaluateJavascript(
                            "window.onDownloadFailed && window.onDownloadFailed('" +
                            esc(filename) + "', '" + esc(e.getMessage()) + "')", null);
                    });
                } finally {
                    try {
                        Intent sIntent = new Intent(ShareActivity.this, DownloadForegroundService.class);
                        stopService(sIntent);
                    } catch (Exception ignored) {}
                }
            });
        }

        /** Save pending share history entry to SharedPreferences for main app to merge */
        @JavascriptInterface
        public void savePendingHistory(String itemJson) {
            try {
                if (itemJson == null || itemJson.trim().isEmpty()) return;
                SharedPreferences prefs = getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
                String existingListStr = prefs.getString("mori_pending_share_history_list", "[]");
                org.json.JSONArray list;
                try {
                    list = new org.json.JSONArray(existingListStr);
                } catch (Exception e) {
                    list = new org.json.JSONArray();
                }
                boolean cloud = MoriDriveBackend.get(ShareActivity.this).isEnabled();
                try {
                    JSONObject obj = new JSONObject(itemJson);
                    if (cloud) {
                        obj.remove("localUri");
                        obj.remove("localFiles");
                        obj.remove("localThumbnail");
                    }
                    list.put(obj);
                } catch (Exception e) {
                    if (cloud) return;
                    list.put(itemJson);
                }
                prefs.edit().putString("mori_pending_share_history_list", list.toString()).commit();
            } catch (Exception e) {
                Log.e(TAG, "savePendingHistory error: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public String getPendingHistoryList() {
            try {
                return MoriDriveBackend.get(ShareActivity.this).pendingHistory();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void clearPendingHistoryList() {
            try {
                MoriDriveBackend.get(ShareActivity.this).clearPendingHistory();
            } catch (Exception ignored) {}
        }

        /** Dismiss the share overlay */
        @JavascriptInterface
        public void dismiss() {
            mainHandler.post(() -> {
                try {
                    android.webkit.CookieManager.getInstance().flush();
                } catch (Exception ignored) {}
                finish();
            });
        }

        /** Show a native Toast message */
        @JavascriptInterface
        public void showToast(String message) {
            mainHandler.post(() ->
                android.widget.Toast.makeText(ShareActivity.this, message, android.widget.Toast.LENGTH_SHORT).show()
            );
        }

        private String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
        }
    }
    
    private String sanitize(String name) {
        if (name == null) return "Mori_Media";
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        // Remove leading dots to prevent creating Android hidden files (.filename)
        while (clean.startsWith(".")) {
            clean = clean.substring(1).trim();
        }
        return clean.isEmpty() ? "Mori_Media" : clean;
    }
}
