package com.mori.downloader;

import android.accounts.Account;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** One process-wide queue, independent of either WebView. Process death requires manual retry. */
final class MoriDriveBackend {
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String API = "https://www.googleapis.com/drive/v3/";
    private static final String KEY = "mori-drive-state-v1";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static MoriDriveBackend instance;
    private final Context context;
    private final AtomicFile stateFile;
    private final SecretKey key;
    private JSONObject state;
    private String durableState;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> active = new HashSet<>();
    // Deliberately isolated from scraper clients, cookies, interceptors and TLS overrides.
    private final OkHttpClient drive = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build();
    private final OkHttpClient source = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    interface Completion { void complete(JSONObject result); }

    static synchronized MoriDriveBackend get(Context context) throws Exception {
        if (instance == null) instance = new MoriDriveBackend(context.getApplicationContext());
        return instance;
    }

    private MoriDriveBackend(Context context) throws Exception {
        this.context = context;
        stateFile = new AtomicFile(new File(context.getNoBackupFilesDir(), "mori-drive-state.enc"));
        boolean exists = stateFile.getBaseFile().exists()
            || new File(stateFile.getBaseFile().getPath() + ".bak").exists();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            if (exists) throw new IOException("STATE_KEY_UNAVAILABLE");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        key = (SecretKey) store.getKey(KEY, null);
        if (exists) {
            byte[] bytes = stateFile.readFully();
            if (bytes.length < 29) throw new IOException("STATE_UNAVAILABLE");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            state = new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)),
                StandardCharsets.UTF_8));
            durableState = state.toString();
        } else {
            state = new JSONObject().put("jobs", new JSONObject()).put("generation", 0L).put("enabled", false);
            persist();
        }
    }

    private synchronized void persist() throws Exception {
        String serialized = state.toString();
        FileOutputStream out = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(serialized.getBytes(StandardCharsets.UTF_8));
            out = stateFile.startWrite();
            out.write(cipher.getIV());
            out.write(encrypted);
            stateFile.finishWrite(out);
            durableState = serialized;
        } catch (Exception e) {
            if (out != null) stateFile.failWrite(out);
            if (durableState != null) state = new JSONObject(durableState);
            throw new IOException("STATE_WRITE_FAILED");
        }
    }

    synchronized boolean isEnabled() { return state.optBoolean("enabled"); }
    synchronized long generation() { return state.optLong("generation"); }

    synchronized JSONObject status() throws Exception {
        JSONArray pending = new JSONArray();
        JSONObject jobs = state.getJSONObject("jobs");
        Iterator<String> ids = jobs.keys();
        while (ids.hasNext()) {
            JSONObject job = jobs.getJSONObject(ids.next());
            JSONObject item = new JSONObject();
            for (String field : new String[]{"jobId", "fileName", "title", "sourceUrl", "error"}) {
                item.put(field, job.optString(field, ""));
            }
            item.put("uploaded", job.optBoolean("confirmed"));
            item.put("localDeleted", false);
            pending.put(item);
        }
        return new JSONObject().put("connected", state.has("accountId") && state.has("folderId"))
            .put("enabled", isEnabled()).put("folderId", state.optString("folderId", ""))
            .put("folderName", state.optString("folderName", "")).put("pending", pending)
            .put("completed", completed());
    }

    private JSONArray completed() throws Exception {
        JSONArray completed = new JSONArray();
        JSONObject receipts = state.optJSONObject("receipts");
        if (receipts == null) return completed;
        Iterator<String> ids = receipts.keys();
        while (ids.hasNext()) {
            JSONObject stored = receipts.getJSONObject(ids.next());
            JSONObject receipt = new JSONObject();
            for (String field : new String[]{"receiptId", "sourceUrl", "title", "driveFileId", "uri", "fileName"}) {
                receipt.put(field, stored.getString(field));
            }
            completed.put(receipt.put("localDeleted", stored.getBoolean("localDeleted")));
        }
        return completed;
    }

    synchronized JSONObject acknowledge(String id) throws Exception {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IOException("INVALID_RECEIPT");
        JSONObject receipts = state.optJSONObject("receipts");
        if (receipts == null || !receipts.has(id)) return status();
        receipts.remove(id);
        JSONObject pending = state.getJSONObject("jobs").optJSONObject(id);
        if (pending != null) pending.put("receiptAcknowledged", true);
        persist();
        return status();
    }

    synchronized void setEnabled(boolean enabled) throws Exception {
        if (enabled && (!state.has("accountId") || !state.has("folderId"))) throw new IOException("CONNECT_FIRST");
        state.put("enabled", enabled);
        state.put("generation", generation() + 1);
        persist();
    }

    synchronized void disconnect() throws Exception {
        state.put("enabled", false).put("generation", generation() + 1);
        for (String field : new String[]{"accountId", "accountName", "accountType", "folderId", "folderName"}) {
            state.remove(field);
        }
        persist();
    }

    static AuthorizationRequest.Builder authorizationRequest(Account account) {
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
            .setOptOutIncludingGrantedScopes(true);
        if (account != null) builder.setAccount(account);
        return builder;
    }

    @SuppressWarnings("deprecation")
    private static GoogleSignInAccount identity(AuthorizationResult result) throws IOException {
        GoogleSignInAccount identity = result.toGoogleSignInAccount();
        if (result.hasResolution() || result.getAccessToken() == null || identity == null
                || identity.getId() == null || identity.getId().isEmpty() || identity.getAccount() == null
                || !result.getGrantedScopes().contains(SCOPE)) throw new IOException("AUTHORIZE_AGAIN");
        return identity;
    }

    void connect(AuthorizationResult result, long expectedGeneration,
            java.util.function.BooleanSupplier stillActive) throws Exception {
        GoogleSignInAccount identity = identity(result);
        String picked = result.getTokenResponseParams() == null ? null
            : result.getTokenResponseParams().getString("picked_file_ids");
        if (picked == null || !picked.matches("[A-Za-z0-9_-]+")) throw new IOException("SELECT_ONE_FOLDER");
        JSONObject folder = getJson(API + "files/" + picked
            + "?supportsAllDrives=true&fields=id,name,mimeType,trashed,capabilities(canAddChildren)",
            result.getAccessToken(), false);
        if (!picked.equals(folder.optString("id")) || folder.optBoolean("trashed")
                || !"application/vnd.google-apps.folder".equals(folder.optString("mimeType"))
                || folder.optJSONObject("capabilities") == null
                || !folder.getJSONObject("capabilities").optBoolean("canAddChildren")) {
            throw new IOException("FOLDER_NOT_WRITABLE");
        }
        synchronized (this) {
            if (!stillActive.getAsBoolean() || generation() != expectedGeneration) throw new IOException("DESTINATION_CHANGED");
            state.put("accountId", identity.getId()).put("accountName", identity.getAccount().name)
                .put("accountType", identity.getAccount().type).put("folderId", picked)
                .put("folderName", folder.getString("name")).put("generation", generation() + 1);
            persist();
        }
    }

    synchronized String createJob(JSONObject options, boolean bytes, boolean share) throws Exception {
        if (!isEnabled() || !state.has("accountId") || !state.has("folderId")) throw new IOException("DRIVE_DISABLED");
        String name = MoriDriveValidation.fileName(options.getString("fileName"));
        if (!bytes) https(options.getString("url"));
        else if (!(options.opt("data") instanceof String) || options.getString("data").isEmpty()) {
            throw new IOException("INVALID_BASE64_DATA");
        }
        String id = UUID.randomUUID().toString();
        JSONObject job = new JSONObject().put("jobId", id).put("fileName", name)
            .put("title", options.optString("title", name)).put("sourceUrl", publicSource(options.optString("sourceUrl", "")))
            .put("folderId", state.getString("folderId")).put("accountId", state.getString("accountId"))
            .put("accountName", state.getString("accountName")).put("accountType", state.getString("accountType"))
            .put("bytes", bytes).put("share", share).put("error", "");
        if (!bytes) {
            job.put("url", options.getString("url"));
            job.put("headers", options.optJSONObject("headers") == null ? new JSONObject() : options.getJSONObject("headers"));
        }
        state.getJSONObject("jobs").put(id, job);
        persist(); // Capture account + destination durably before creating any temporary file.
        return id;
    }

    private synchronized JSONObject job(String id) throws Exception {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IOException("INVALID_JOB");
        JSONObject job = state.getJSONObject("jobs").optJSONObject(id);
        if (job == null) throw new IOException("JOB_NOT_FOUND");
        return new JSONObject(job.toString());
    }

    private synchronized void save(JSONObject job) throws Exception {
        JSONObject existing = state.getJSONObject("jobs").optJSONObject(job.getString("jobId"));
        if (existing != null && existing.optBoolean("receiptAcknowledged")) job.put("receiptAcknowledged", true);
        state.getJSONObject("jobs").put(job.getString("jobId"), new JSONObject(job.toString()));
        if (job.optBoolean("confirmed")) receipt(job, false);
        persist();
    }

    // Called under the state lock and committed together with the corresponding job mutation.
    private void receipt(JSONObject job, boolean deleted) throws Exception {
        JSONObject pending = state.getJSONObject("jobs").optJSONObject(job.getString("jobId"));
        if (pending != null && pending.optBoolean("receiptAcknowledged")) return;
        JSONObject receipts = state.optJSONObject("receipts");
        if (receipts == null) {
            receipts = new JSONObject();
            state.put("receipts", receipts);
        }
        String id = job.getString("jobId");
        JSONObject existing = receipts.optJSONObject(id);
        receipts.put(id, new JSONObject().put("receiptId", id).put("sourceUrl", job.getString("sourceUrl"))
            .put("title", job.getString("title")).put("driveFileId", job.getString("fileId"))
            .put("uri", "https://drive.google.com/file/d/" + job.getString("fileId") + "/view")
            .put("fileName", job.optString("cloudName", job.getString("fileName")))
            .put("localDeleted", deleted || existing != null && existing.optBoolean("localDeleted")));
    }

    private synchronized void accountMatches(JSONObject job) throws Exception {
        if (!job.getString("accountId").equals(state.optString("accountId"))) throw new IOException("RECONNECT_ORIGINAL_ACCOUNT");
    }

    void run(String id, String data, Completion callback) throws Exception {
        JSONObject captured = job(id);
        synchronized (this) {
            if (!active.add(id)) throw new IOException("JOB_BUSY");
        }
        worker.execute(() -> {
            JSONObject result;
            try {
                if (!captured.optBoolean("confirmed")) {
                    accountMatches(captured);
                    if (!captured.has("payload")) download(captured, data);
                    upload(captured);
                }
                result = cleanup(captured);
            } catch (Exception e) {
                try {
                    captured.put("error", safeError(e));
                    save(captured);
                    // Historical confirmation is not a successful result for a failed current verification.
                    result = new JSONObject().put("jobId", id).put("ok", false).put("uploaded", false)
                        .put("localDeleted", false).put("error", safeError(e));
                } catch (Exception ignored) {
                    result = new JSONObject();
                    try { result.put("ok", false).put("jobId", id).put("localDeleted", false).put("error", "STATE_WRITE_FAILED"); }
                    catch (Exception impossible) {}
                }
            }
            synchronized (this) { active.remove(id); }
            try { callback.complete(result); } catch (Exception ignored) {}
        });
    }

    private File directory(JSONObject job) throws Exception {
        File dir = new File(new File(context.getFilesDir().getCanonicalFile(), "drive-pending"), job.getString("jobId"));
        if (!dir.getCanonicalFile().equals(dir.getAbsoluteFile())) throw new IOException("UNKNOWN_PENDING_FILE");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("PRIVATE_STORAGE_UNAVAILABLE");
        return dir;
    }

    private void download(JSONObject job, String data) throws Exception {
        File dir = directory(job);
        File attempt = new File(dir, "attempt-" + UUID.randomUUID());
        JSONArray attempts = job.optJSONArray("attempts");
        if (attempts == null) attempts = new JSONArray();
        attempts.put(attempt.getName());
        job.put("attempts", attempts);
        save(job); // Record the exact owned filename before creating it, including interrupted attempts.
        MessageDigest digest = MessageDigest.getInstance("MD5");
        long count;
        if (job.getBoolean("bytes")) {
            if (data == null) throw new IOException("BYTES_NOT_STAGED_RESUBMIT");
            // Decode as a stream rather than creating a second full PDF-sized byte array.
            try (InputStream in = new android.util.Base64InputStream(
                    new ByteArrayInputStream(data.getBytes(StandardCharsets.US_ASCII)), Base64.DEFAULT)) {
                count = stage(in, attempt, digest);
            }
        } else {
            Request.Builder request = new Request.Builder().url(https(job.getString("url")))
                .header("User-Agent", "Host-ia/Android");
            JSONObject headers = job.getJSONObject("headers");
            Iterator<String> names = headers.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (name.equalsIgnoreCase("Range") || name.equalsIgnoreCase("Accept-Encoding")) continue;
                request.header(name, headers.getString(name));
            }
            request.header("Accept-Encoding", "identity");
            // Follow HTTPS redirects ourselves; do not forward source secrets to another origin.
            Request current = request.build();
            for (int redirects = 0;; redirects++) {
                try (Response response = source.newCall(current).execute()) {
                    if (response.isRedirect()) {
                        if (redirects >= 10) throw new IOException("SOURCE_REDIRECT_LIMIT");
                        String location = response.header("Location");
                        HttpUrl next = location == null ? null : current.url().resolve(location);
                        if (next == null) throw new IOException("INVALID_SOURCE_REDIRECT");
                        https(next.toString());
                        Request.Builder redirect = current.newBuilder().url(next);
                        if (!next.host().equals(current.url().host()) || next.port() != current.url().port()) {
                            Iterator<String> sensitive = headers.keys();
                            while (sensitive.hasNext()) redirect.removeHeader(sensitive.next());
                            redirect.header("Accept-Encoding", "identity");
                        }
                        current = redirect.build();
                        continue;
                    }
                    if (response.code() != 200 || response.body() == null) throw new IOException("SOURCE_HTTP_" + response.code());
                    count = stage(response.body().byteStream(), attempt, digest);
                    long expected = response.body().contentLength();
                    if (expected >= 0 && count != expected) throw new IOException("SOURCE_SIZE_MISMATCH");
                    break;
                }
            }
        }
        if (count <= 0) throw new IOException("EMPTY_CONTENT");
        job.put("payload", attempt.getName()).put("size", count).put("md5", hex(digest.digest()));
        save(job); // Incomplete attempts are retained until this job's upload is verified.
    }

    private long stage(InputStream in, File path, MessageDigest md5) throws Exception {
        long count = 0;
        try (FileOutputStream out = new FileOutputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                md5.update(buffer, 0, n);
                count += n;
            }
            out.getFD().sync();
        }
        return count;
    }

    private String token(JSONObject job) throws Exception {
        accountMatches(job);
        AuthorizationResult result = Tasks.await(Identity.getAuthorizationClient(context).authorize(
            authorizationRequest(new Account(job.getString("accountName"), job.getString("accountType"))).build()),
            120, TimeUnit.SECONDS);
        GoogleSignInAccount account = identity(result);
        if (!job.getString("accountId").equals(account.getId())
                || !job.getString("accountName").equals(account.getAccount().name)
                || !job.getString("accountType").equals(account.getAccount().type)) throw new IOException("ACCOUNT_MISMATCH");
        accountMatches(job);
        return result.getAccessToken();
    }

    private void upload(JSONObject job) throws Exception {
        File payload = new File(directory(job), job.getString("payload"));
        verifyPayload(job, payload);
        String access = token(job);
        if (!job.has("fileId")) {
            JSONObject ids = getJson(API + "files/generateIds?count=1&space=drive&type=files", access, false);
            job.put("fileId", ids.getJSONArray("ids").getString(0));
            save(job); // Pre-generated ID makes all create retries idempotent, including initiation timeouts.
        }
        if (confirm(job, access, true)) return;
        uploadSession(job, payload, access, 0);
    }

    private void verifyPayload(JSONObject job, File payload) throws Exception {
        if (!payload.isFile() || payload.length() != job.getLong("size")) throw new IOException("LOCAL_CONTENT_CHANGED");
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(payload)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        if (!hex(digest.digest()).equals(job.getString("md5"))) throw new IOException("LOCAL_CONTENT_CHANGED");
    }

    private void uploadSession(JSONObject job, File payload, String access, long offset) throws Exception {
        long size = job.getLong("size");
        if (job.has("session")) {
            try (Response response = drive.newCall(authorized(session(job), access)
                    .header("Content-Range", "bytes */" + size)
                    .put(RequestBody.create(new byte[0], null)).build()).execute()) {
                if (response.code() == 200 || response.code() == 201) {
                    if (!confirm(job, access, false)) throw new IOException("UPLOAD_NOT_VERIFIED");
                    return;
                }
                if (response.code() == 308) offset = MoriDriveValidation.offset(response.header("Range"), size);
                else if (response.code() == 404 || response.code() == 410) {
                    if (confirm(job, access, true)) return;
                    job.remove("session");
                    save(job);
                } else throw httpError(response, access);
            }
        }
        String mime = mime(job.getString("fileName"));
        if (!job.has("session")) {
            JSONObject metadata = new JSONObject().put("id", job.getString("fileId"))
                .put("name", job.getString("fileName")).put("mimeType", mime)
                .put("parents", new JSONArray().put(job.getString("folderId")));
            try (Response response = drive.newCall(authorized("https://www.googleapis.com/upload/drive/v3/files"
                    + "?uploadType=resumable&supportsAllDrives=true&fields=id", access)
                    .header("X-Upload-Content-Type", mime).header("X-Upload-Content-Length", Long.toString(size))
                    .post(RequestBody.create(metadata.toString(), JSON)).build()).execute()) {
                if (!response.isSuccessful()) throw httpError(response, access);
                String location = response.header("Location");
                validSession(location);
                job.put("session", location).put("offset", 0);
                save(job);
            }
        }
        while (offset < size) {
            accountMatches(job);
            final long start = offset;
            final long length = Math.min(8L * 1024 * 1024, size - start);
            RequestBody chunk = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.parse(mime); }
                @Override public long contentLength() { return length; }
                @Override public void writeTo(BufferedSink sink) throws IOException {
                    try (RandomAccessFile in = new RandomAccessFile(payload, "r")) {
                        in.seek(start);
                        byte[] buffer = new byte[64 * 1024];
                        long remaining = length;
                        while (remaining > 0) {
                            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (n < 0) throw new IOException("LOCAL_CONTENT_CHANGED");
                            sink.write(buffer, 0, n);
                            remaining -= n;
                        }
                    }
                }
            };
            try (Response response = drive.newCall(authorized(session(job), access)
                    .header("Content-Range", "bytes " + start + "-" + (start + length - 1) + "/" + size)
                    .put(chunk).build()).execute()) {
                if (response.code() == 200 || response.code() == 201) {
                    if (!confirm(job, access, false)) throw new IOException("UPLOAD_NOT_VERIFIED");
                    return;
                }
                if (response.code() != 308) throw httpError(response, access);
                long next = MoriDriveValidation.offset(response.header("Range"), size);
                if (next <= offset || next > start + length) throw new IOException("INVALID_UPLOAD_RANGE");
                offset = next;
                job.put("offset", offset);
                save(job);
            }
        }
        throw new IOException("UPLOAD_NOT_VERIFIED");
    }

    private boolean confirm(JSONObject job, String access, boolean allowMissing) throws Exception {
        JSONObject file = getJson(API + "files/" + job.getString("fileId")
            + "?supportsAllDrives=true&fields=id,name,size,md5Checksum,parents,trashed", access, allowMissing);
        if (file == null) return false;
        JSONArray parents = file.optJSONArray("parents");
        boolean verified = MoriDriveValidation.verified(job.getString("fileId"), file.optString("id"),
            job.getLong("size"), file.optLong("size", -1), job.getString("md5"), file.optString("md5Checksum"),
            job.getString("folderId"), parents == null ? "" : parents.optString(0), parents == null ? 0 : parents.length(),
            file.opt("trashed"));
        if (!verified) throw new IOException("UPLOAD_NOT_VERIFIED");
        job.put("confirmed", true).put("cloudName", file.optString("name", job.getString("fileName")))
            .put("error", "");
        save(job); // Durable confirmation is the only gate to local deletion, including cleanup-only retries.
        return true;
    }

    private JSONObject cleanup(JSONObject job) throws Exception {
        if (!job.optBoolean("confirmed")) throw new IOException("UPLOAD_NOT_VERIFIED");
        File dir = directory(job);
        File payload = new File(dir, job.getString("payload"));
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("PRIVATE_STORAGE_UNAVAILABLE");
        Set<String> owned = new HashSet<>();
        owned.add(job.getString("payload"));
        JSONArray attempts = job.optJSONArray("attempts");
        if (attempts != null) {
            for (int i = 0; i < attempts.length(); i++) owned.add(attempts.getString(i));
        }
        for (File file : files) {
            if (!owned.contains(file.getName()) || !file.isFile()
                    || !file.getCanonicalFile().equals(file.getAbsoluteFile())) {
                throw new IOException("UNKNOWN_PENDING_FILE");
            }
        }
        // No-content recovery after a crash performs no filesystem deletion. Never delete other
        // remnants if the payload needed to recheck this job's checksum is missing.
        boolean alreadyDeleted = !payload.exists() && files.length == 0;
        if (!alreadyDeleted) verifyPayload(job, payload);
        String access = token(job);
        if (!confirm(job, access, false)) throw new IOException("UPLOAD_NOT_VERIFIED");
        accountMatches(job);
        if (!alreadyDeleted) verifyPayload(job, payload);
        // Persist cloud history before deletion, even if the originating WebView has gone away.
        if (job.optBoolean("share")) recordCloudHistory(job, false);
        boolean deleted = true;
        if (!alreadyDeleted) {
            for (File file : files) {
                if (file.equals(payload)) continue;
                if (!file.isFile() || !file.delete()) deleted = false;
            }
            // Keep the verified payload until all other attempts are gone, so failed cleanup is retryable.
            if (deleted && !payload.delete()) deleted = false;
            if (deleted) dir.delete(); // An empty directory is not downloaded content; failure retains no bytes.
        }
        synchronized (this) {
            JSONObject pending = state.getJSONObject("jobs").optJSONObject(job.getString("jobId"));
            if (pending != null && pending.optBoolean("receiptAcknowledged")) job.put("receiptAcknowledged", true);
            receipt(job, deleted);
            if (deleted) state.getJSONObject("jobs").remove(job.getString("jobId"));
            else state.getJSONObject("jobs").put(job.getString("jobId"), new JSONObject(job.toString())
                .put("error", "CLOUD_CONFIRMED_CLEANUP_PENDING"));
            persist(); // Receipt is durable before the job can disappear, for main uploads as well as shares.
        }
        if (!deleted) {
            job.put("error", "CLOUD_CONFIRMED_CLEANUP_PENDING");
        }
        JSONObject result = result(job, deleted).put("ok", true);
        if (!deleted) result.put("error", "CLOUD_CONFIRMED_CLEANUP_PENDING");
        if (job.optBoolean("share")) {
            try { recordCloudHistory(job, deleted); } catch (Exception ignored) {}
        }
        notifyCloud(job);
        return result;
    }

    private JSONObject result(JSONObject job, boolean deleted) throws Exception {
        JSONObject result = new JSONObject().put("jobId", job.getString("jobId"))
            .put("uploaded", job.optBoolean("confirmed")).put("localDeleted", deleted);
        if (job.optBoolean("confirmed")) {
            JSONObject file = new JSONObject().put("id", job.getString("fileId"))
                .put("driveFileId", job.getString("fileId")).put("localDeleted", deleted)
                .put("uri", "https://drive.google.com/file/d/" + job.getString("fileId") + "/view")
                .put("name", job.optString("cloudName", job.getString("fileName")))
                .put("fileName", job.optString("cloudName", job.getString("fileName"))).put("title", job.getString("title"));
            result.put("driveFiles", new JSONArray().put(file));
            result.put("receiptId", job.getString("jobId")).put("sourceUrl", job.getString("sourceUrl"));
            result.put("driveFileId", file.getString("id")).put("fileName", file.getString("fileName"));
            result.put("id", file.getString("id")).put("uri", file.getString("uri"))
                .put("name", file.getString("name")).put("title", file.getString("title"));
        }
        return result;
    }

    private synchronized void recordCloudHistory(JSONObject job, boolean deleted) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        if ("true".equals(prefs.getString("mori_incognito", "false"))) return;
        JSONArray history = new JSONArray(prefs.getString("mori_pending_drive_history_list", "[]"));
        JSONObject item = new JSONObject().put("jobId", job.getString("jobId"))
            .put("url", job.getString("sourceUrl")).put("sourceUrl", job.getString("sourceUrl"))
            .put("title", job.getString("title")).put("timestamp", System.currentTimeMillis())
            .put("driveFiles", result(job, deleted).getJSONArray("driveFiles"));
        int index = history.length();
        for (int i = 0; i < history.length(); i++) {
            if (job.getString("jobId").equals(history.getJSONObject(i).optString("jobId"))) { index = i; break; }
        }
        history.put(index, item);
        if (!prefs.edit().putString("mori_pending_drive_history_list", history.toString()).commit()) {
            throw new IOException("CLOUD_HISTORY_WRITE_FAILED");
        }
    }

    private void notifyCloud(JSONObject job) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(
                "mori_drive", "Host-ia Drive uploads", NotificationManager.IMPORTANCE_DEFAULT));
            Intent view = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://drive.google.com/file/d/" + job.getString("fileId") + "/view"));
            PendingIntent open = PendingIntent.getActivity(context, job.getString("jobId").hashCode(), view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(job.getString("jobId").hashCode(), new NotificationCompat.Builder(context, "mori_drive")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Saved to Google Drive")
                .setContentText(job.getString("title")).setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {} // Notification denial must not affect data retention.
    }

    synchronized String pendingHistory() throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        JSONArray local = new JSONArray(prefs.getString("mori_pending_share_history_list", "[]"));
        JSONArray cloud = new JSONArray(prefs.getString("mori_pending_drive_history_list", "[]"));
        for (int i = 0; i < cloud.length(); i++) local.put(cloud.getJSONObject(i));
        return local.toString();
    }

    synchronized void clearPendingHistory() {
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit()
            .remove("mori_pending_share_history_list").remove("mori_pending_drive_history_list").commit();
    }

    private Request.Builder authorized(String url, String access) throws Exception {
        HttpUrl parsed = https(url);
        if (!"www.googleapis.com".equals(parsed.host()) || parsed.port() != 443) throw new IOException("INVALID_DRIVE_ENDPOINT");
        return new Request.Builder().url(parsed).header("Authorization", "Bearer " + access);
    }

    private JSONObject getJson(String url, String access, boolean allowMissing) throws Exception {
        try (Response response = drive.newCall(authorized(url, access).build()).execute()) {
            if (allowMissing && response.code() == 404) return null;
            if (!response.isSuccessful()) throw httpError(response, access);
            if (response.body() == null) throw new IOException("INVALID_DRIVE_RESPONSE");
            return new JSONObject(response.body().string());
        }
    }

    private IOException httpError(Response response, String access) {
        if (response.code() == 401) {
            try { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(access).build()); }
            catch (Exception ignored) {}
        }
        return new IOException("DRIVE_HTTP_" + response.code());
    }

    private static HttpUrl https(String url) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url == null ? "" : url);
        if (parsed == null || !parsed.isHttps() || !parsed.username().isEmpty() || !parsed.password().isEmpty()) {
            throw new IOException("HTTPS_REQUIRED");
        }
        return parsed;
    }

    private static void validSession(String url) throws Exception {
        HttpUrl parsed = https(url);
        if (!"www.googleapis.com".equals(parsed.host()) || parsed.port() != 443
                || !parsed.encodedPath().startsWith("/upload/drive/v3/files")) throw new IOException("INVALID_UPLOAD_SESSION");
    }

    private static String session(JSONObject job) throws Exception {
        String url = job.getString("session");
        validSession(url);
        return url;
    }

    static String publicSource(String raw) {
        HttpUrl url = HttpUrl.parse(raw);
        if (url == null) return "";
        HttpUrl.Builder publicUrl = url.newBuilder().username("").password("").query(null).fragment(null);
        for (String name : MoriDriveValidation.sourceKeys(url.host())) {
            String id = url.queryParameter(name);
            if (id != null && id.matches("[A-Za-z0-9_-]{1,256}")) publicUrl.addQueryParameter(name, id);
        }
        return publicUrl.build().toString();
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    static String safeError(Exception error) {
        // Never forward SDK/HTTP exception messages, response bodies, URLs, headers or tokens.
        String message = error.getMessage();
        if (error instanceof IOException && message != null && (message.matches("(?:DRIVE|SOURCE)_HTTP_[0-9]{3}")
                || Arrays.asList("INVALID_FILENAME", "INVALID_UPLOAD_RANGE", "STATE_KEY_UNAVAILABLE", "STATE_UNAVAILABLE",
                    "STATE_WRITE_FAILED", "CONNECT_FIRST", "AUTHORIZE_AGAIN", "SELECT_ONE_FOLDER", "FOLDER_NOT_WRITABLE",
                    "DESTINATION_CHANGED", "DRIVE_DISABLED", "INVALID_JOB", "JOB_NOT_FOUND", "RECONNECT_ORIGINAL_ACCOUNT",
                    "JOB_BUSY", "PRIVATE_STORAGE_UNAVAILABLE", "BYTES_NOT_STAGED_RESUBMIT", "SOURCE_REDIRECT_LIMIT",
                    "INVALID_SOURCE_REDIRECT", "SOURCE_SIZE_MISMATCH", "EMPTY_CONTENT", "ACCOUNT_MISMATCH",
                    "LOCAL_CONTENT_CHANGED", "UPLOAD_NOT_VERIFIED", "CLOUD_CONFIRMED_CLEANUP_PENDING",
                    "CLOUD_HISTORY_WRITE_FAILED", "INVALID_DRIVE_ENDPOINT", "INVALID_DRIVE_RESPONSE", "HTTPS_REQUIRED",
                    "INVALID_UPLOAD_SESSION", "INVALID_BASE64_DATA", "INVALID_RECEIPT", "UNKNOWN_PENDING_FILE").contains(message))) return message;
        if (error instanceof java.util.concurrent.TimeoutException) return "AUTHORIZATION_TIMEOUT";
        return "DRIVE_OPERATION_FAILED";
    }
}
