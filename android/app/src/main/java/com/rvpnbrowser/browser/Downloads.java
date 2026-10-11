package com.rvpnbrowser.browser;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File downloads.
 *
 * <p>WebView cannot download by itself: when it detects a download it hands the
 * URL to the app. The transfer is done with {@link HttpURLConnection} and an
 * <b>explicit</b> {@link Proxy} pointing at the local HTTP proxy of the RVPN core
 * (falling back to its SOCKS5 listener), so downloads honour the current mode -
 * a ZJU intranet file is only reachable through RVPN.</p>
 *
 * <p>On API 29+ the finished file goes through {@code MediaStore} into the public
 * {@code Download/} collection (no storage permission needed for own inserts),
 * below that into the app specific external directory.</p>
 */
public final class Downloads {

    private static final String TAG = "Downloads";
    private static final String FILE = "rvpn_downloads";
    private static final String KEY = "items";
    private static final int CAPACITY = 200;

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** One download, also the persisted record. */
    public static final class Record {
        public long id;
        public String url = "";
        public String fileName = "";
        public String path = "";
        public String uri = "";
        public String mime = "";
        public String status = STATUS_RUNNING;
        public String error = "";
        public long total = -1;
        public long done;
        public long startedAt;
        public long finishedAt;

        public boolean isActive() {
            return STATUS_RUNNING.equals(status);
        }

        public boolean isDone() {
            return STATUS_DONE.equals(status);
        }
    }

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "rvpn-download");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong NEXT_ID = new AtomicLong(System.currentTimeMillis());
    private static final List<Record> RECORDS = new ArrayList<>();
    private static final List<Task> TASKS = new ArrayList<>();
    private static boolean loaded;

    private Downloads() {
    }

    /** Snapshot of every known download, newest first. */
    public static List<Record> list(Context context) {
        synchronized (RECORDS) {
            ensureLoaded(context);
            List<Record> copy = new ArrayList<>(RECORDS);
            Collections.reverse(copy);
            return copy;
        }
    }

    /**
     * Starts a download.
     *
     * @param url the URL WebView wanted to download
     * @param contentDisposition the Content-Disposition header, may be null
     * @param mimeType hint from WebView, may be null
     */
    public static void enqueue(Context context, String url, String userAgent,
                               String contentDisposition, String mimeType) {
        final Context app = context.getApplicationContext();
        if (url == null || url.isEmpty()) {
            return;
        }
        if (url.startsWith("blob:")) {
            // Blob URLs only exist inside the page; WebView has to resolve them.
            Ui.toast(app, app.getString(R.string.download_failed,
                    "blob: URLs must be resolved by the page itself"));
            return;
        }
        Record record = new Record();
        record.id = NEXT_ID.incrementAndGet();
        record.url = url;
        record.fileName = guessFileName(url, contentDisposition, mimeType);
        record.mime = mimeType == null || mimeType.isEmpty()
                ? Ui.guessMime(url, "application/octet-stream") : mimeType;
        record.status = STATUS_RUNNING;
        record.startedAt = System.currentTimeMillis();

        synchronized (RECORDS) {
            ensureLoaded(app);
            RECORDS.add(record);
            trim();
            persist(app);
        }

        Proxy proxy = coreProxy(app);
        if (proxy == null) {
            Ui.toast(app, R.string.download_no_proxy);
        }
        Ui.toast(app, app.getString(R.string.download_starting, record.fileName));
        Task task = new Task(app, record, userAgent, proxy);
        synchronized (TASKS) {
            TASKS.add(task);
        }
        EXECUTOR.execute(task);
    }

    /** Cancels an active download. */
    public static void cancel(Context context, long id) {
        synchronized (TASKS) {
            for (Task t : TASKS) {
                if (t.record.id == id) {
                    t.cancelled = true;
                }
            }
        }
        Ui.toast(context, R.string.download_cancelled);
    }

    /** Removes a finished record from the list (the file itself is kept). */
    public static void remove(Context context, long id) {
        synchronized (RECORDS) {
            for (Iterator<Record> it = RECORDS.iterator(); it.hasNext(); ) {
                if (it.next().id == id) {
                    it.remove();
                    break;
                }
            }
            persist(context.getApplicationContext());
        }
    }

    /** Opens a finished download with the viewer app of the user. */
    public static void open(Context context, Record record) {
        if (record == null) {
            return;
        }
        try {
            Intent intent;
            if (record.uri != null && record.uri.startsWith("content://")) {
                intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(record.uri), record.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (record.path != null && !record.path.isEmpty()) {
                File file = new File(record.path);
                Uri uri = FileProvider.getUriForFile(context,
                        BuildConfig.APPLICATION_ID + ".fileprovider", file);
                intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, record.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                Ui.toast(context, R.string.download_open_failed);
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "cannot open the download", t);
            Ui.toast(context, R.string.download_open_failed);
        }
    }

    // -------------------------------------------------------------- networking

    /**
     * The proxy to use: the HTTP listener of the core when RVPN mode is active
     * and connected, otherwise its SOCKS5 listener, otherwise none at all.
     */
    private static Proxy coreProxy(Context context) {
        RvpnPrefs prefs = new RvpnPrefs(context);
        if (!prefs.isRvpnMode()) {
            return null;
        }
        RvpnState state = RvpnClient.get(context).lastState();
        if (state == null) {
            return null;
        }
        if (!state.httpAddr.isEmpty()) {
            InetSocketAddress address = parseAddress(state.httpAddr);
            if (address != null) {
                return new Proxy(Proxy.Type.HTTP, address);
            }
        }
        if (!state.socksAddr.isEmpty()) {
            InetSocketAddress address = parseAddress(state.socksAddr);
            if (address != null) {
                return new Proxy(Proxy.Type.SOCKS, address);
            }
        }
        return null;
    }

    private static InetSocketAddress parseAddress(String addr) {
        int colon = addr.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            return new InetSocketAddress(addr.substring(0, colon),
                    Integer.parseInt(addr.substring(colon + 1).trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Task implements Runnable {
        private final Context app;
        private final Record record;
        private final String userAgent;
        private final Proxy proxy;
        private volatile boolean cancelled;
        private HttpURLConnection connection;
        private OutputStream sink;
        private boolean pendingMediaStore;

        Task(Context app, Record record, String userAgent, Proxy proxy) {
            this.app = app;
            this.record = record;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.proxy = proxy;
        }

        @Override
        public void run() {
            try {
                transfer();
                if (cancelled) {
                    finish(STATUS_CANCELLED, app.getString(R.string.download_cancelled), true);
                } else {
                    finish(STATUS_DONE, app.getString(R.string.download_complete,
                            record.path.isEmpty() ? record.fileName : record.path), false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "download failed: " + record.url, t);
                boolean wasCancelled = cancelled;
                finish(wasCancelled ? STATUS_CANCELLED : STATUS_FAILED,
                        app.getString(wasCancelled ? R.string.download_cancelled
                                : R.string.download_failed,
                                String.valueOf(t.getMessage() == null ? t : t.getMessage())),
                        true);
            } finally {
                closeQuietly();
            }
        }

        private void transfer() throws IOException {
            URL url = new URL(record.url);
            connection = (HttpURLConnection) (proxy == null
                    ? url.openConnection() : url.openConnection(proxy));
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (!userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            String cookie = CookieManager.getInstance().getCookie(record.url);
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " " + connection.getResponseMessage());
            }
            String disposition = connection.getHeaderField("Content-Disposition");
            if (disposition != null && !disposition.isEmpty()) {
                String fromHeader = parseFileName(disposition);
                if (fromHeader != null && !fromHeader.isEmpty()) {
                    record.fileName = sanitize(fromHeader);
                }
            }
            String type = connection.getHeaderField("Content-Type");
            if (type != null && !type.isEmpty()) {
                int semicolon = type.indexOf(';');
                record.mime = (semicolon > 0 ? type.substring(0, semicolon) : type).trim();
            }
            record.total = connection.getContentLengthLong();
            record.done = 0;

            sink = openSink();
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long lastNotify = 0L;
                while ((read = in.read(buffer)) > 0) {
                    if (cancelled) {
                        return;
                    }
                    sink.write(buffer, 0, read);
                    record.done += read;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 400L) {
                        lastNotify = now;
                        notifyProgress();
                    }
                }
                sink.flush();
                // Close before clearing IS_PENDING so the bytes are committed.
                sink.close();
                sink = null;
            }
            if (pendingMediaStore) {
                markMediaStoreReady();
            }
            notifyProgress();
        }

        /** Creates the destination: MediaStore on API 29+, app storage below. */
        private OutputStream openSink() throws IOException {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = app.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, record.fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, record.mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("MediaStore refused the new entry");
                }
                record.uri = uri.toString();
                record.path = Environment.DIRECTORY_DOWNLOADS + "/" + record.fileName;
                pendingMediaStore = true;
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) {
                    throw new IOException("MediaStore returned no stream");
                }
                return out;
            }
            File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                dir = new File(app.getFilesDir(), "downloads");
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            File file = new File(dir, record.fileName);
            record.path = file.getAbsolutePath();
            return new FileOutputStream(file);
        }

        private void markMediaStoreReady() {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                app.getContentResolver().update(Uri.parse(record.uri), values, null, null);
            } catch (Throwable t) {
                Log.w(TAG, "cannot publish the MediaStore entry", t);
            }
        }

        private void notifyProgress() {
            int percent = 0;
            boolean indeterminate = record.total <= 0;
            if (!indeterminate) {
                percent = (int) Math.min(100L, record.done * 100L / record.total);
            }
            String text = indeterminate
                    ? Ui.formatBytes(record.done)
                    : Ui.formatBytes(record.done) + " / " + Ui.formatBytes(record.total);
            try {
                Intent cancelIntent = new Intent(app, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_CANCEL_DOWNLOAD, record.id);
                NotificationManagerCompat.from(app).notify(Notifications.downloadId(record.id),
                        Notifications.downloadNotification(app,
                                app.getString(R.string.notif_download_title, record.fileName),
                                text, percent, indeterminate,
                                Notifications.activityIntent(app, cancelIntent,
                                        (int) (record.id % 100000))));
            } catch (Throwable t) {
                Log.w(TAG, "cannot post the download notification: " + t);
            }
        }

        private void finish(String status, String message, boolean failed) {
            record.status = status;
            record.finishedAt = System.currentTimeMillis();
            record.error = failed ? message : "";
            if (STATUS_DONE.equals(status)) {
                record.total = record.done;
            } else {
                // Do not leave a half written file behind.
                discardPartial();
            }
            synchronized (RECORDS) {
                persist(app);
            }
            synchronized (TASKS) {
                TASKS.remove(this);
            }
            try {
                NotificationManagerCompat.from(app).notify(Notifications.downloadId(record.id),
                        Notifications.downloadFinishedNotification(app,
                                app.getString(failed ? R.string.notif_download_failed
                                        : R.string.notif_download_done, record.fileName),
                                message, failed));
            } catch (Throwable t) {
                Log.w(TAG, "cannot post the download result: " + t);
            }
            }

        private void discardPartial() {
            try {
                if (pendingMediaStore && record.uri != null && !record.uri.isEmpty()) {
                    app.getContentResolver().delete(Uri.parse(record.uri), null, null);
                } else if (!record.path.isEmpty()) {
                    File file = new File(record.path);
                    if (file.isFile() && !file.delete()) {
                        Log.w(TAG, "cannot delete the partial file " + file);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "cannot clean up the partial download", t);
            }
        }

        private void closeQuietly() {
            if (sink != null) {
                try {
                    sink.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
                sink = null;
            }
            if (connection != null) {
                connection.disconnect();
                connection = null;
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private static void ensureLoaded(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Record r = new Record();
                    r.id = Json.lng(o, "id", 0);
                    r.url = Json.str(o, "url");
                    r.fileName = Json.str(o, "fileName");
                    r.path = Json.str(o, "path");
                    r.uri = Json.str(o, "uri");
                    r.mime = Json.str(o, "mime");
                    r.status = Json.str(o, "status", STATUS_FAILED);
                    r.error = Json.str(o, "error");
                    r.total = Json.lng(o, "total", -1);
                    r.done = Json.lng(o, "done", 0);
                    r.startedAt = Json.lng(o, "startedAt", 0);
                    r.finishedAt = Json.lng(o, "finishedAt", 0);
                    if (r.isActive()) {
                        // The process died while this download was running.
                        r.status = STATUS_FAILED;
                        r.error = "interrupted";
                    }
                    RECORDS.add(r);
                }
            } catch (Exception e) {
                Log.w(TAG, "cannot parse the download list", e);
            }
        }
    }

    private static void persist(Context context) {
        JSONArray array = new JSONArray();
        for (Record r : RECORDS) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", r.id);
                o.put("url", r.url);
                o.put("fileName", r.fileName);
                o.put("path", r.path);
                o.put("uri", r.uri);
                o.put("mime", r.mime);
                o.put("status", r.status);
                o.put("error", r.error);
                o.put("total", r.total);
                o.put("done", r.done);
                o.put("startedAt", r.startedAt);
                o.put("finishedAt", r.finishedAt);
            } catch (Exception ignored) {
                continue;
            }
            array.put(o);
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).apply();
    }

    private static void trim() {
        while (RECORDS.size() > CAPACITY) {
            RECORDS.remove(0);
        }
    }

    // ------------------------------------------------------------- file names

    private static String guessFileName(String url, String contentDisposition, String mimeType) {
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            String name = parseFileName(contentDisposition);
            if (name != null && !name.isEmpty()) {
                return sanitize(name);
            }
        }
        String path = url;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        name = sanitize(name);
        if (name.isEmpty()) {
            // Nothing usable in the URL: fall back to a name derived from the MIME type.
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            name = "download" + (extension == null ? "" : "." + extension);
        }
        return name;
    }

    /** Handles both {@code filename=} and RFC 5987 {@code filename*=}. */
    private static String parseFileName(String disposition) {
        String value = disposition;
        int star = value.toLowerCase(Locale.US).indexOf("filename*=");
        if (star >= 0) {
            String part = value.substring(star + "filename*=".length());
            int semicolon = part.indexOf(';');
            if (semicolon >= 0) {
                part = part.substring(0, semicolon);
            }
            part = stripQuotes(part.trim());
            int quote = part.indexOf("''");
            if (quote >= 0) {
                part = part.substring(quote + 2);
            }
            try {
                return URLDecoder.decode(part, "UTF-8");
            } catch (Exception ignored) {
                return part;
            }
        }
        int plain = value.toLowerCase(Locale.US).indexOf("filename=");
        if (plain >= 0) {
            String part = value.substring(plain + "filename=".length());
            int semicolon = part.indexOf(';');
            if (semicolon >= 0) {
                part = part.substring(0, semicolon);
            }
            return stripQuotes(part.trim());
        }
        return null;
    }

    private static String stripQuotes(String value) {
        String v = value.trim();
        while (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Keeps the name usable as a file name on every supported file system. */
    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120);
        }
        return cleaned;
    }
}
