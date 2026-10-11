package com.rvpnbrowser.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Client of the RVPN core control protocol (docs/PROTOCOL.md).
 *
 * <p>The core runs in the {@code :rvpn} process and exposes the frozen JSON
 * protocol on a loopback TCP port. {@code RvpnService} publishes the endpoint in
 * {@code <filesDir>/rvpn/control.json}; this class reads it, performs the token
 * handshake ({"token":"..."} as the very first line) and then speaks one JSON
 * request per line.</p>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>one reader thread dispatching replies to the waiting caller by request id,</li>
 *   <li>one I/O thread that connects (with exponential backoff) and polls
 *       {@code status} - 500 ms while a UI is visible, 2 s otherwise,</li>
 *   <li>per-request timeouts (20 s by default, 120 s for {@code start}),</li>
 *   <li>event fan-out (log / state / challenge / challengeDone / error) to
 *       listeners, always on the main thread.</li>
 * </ul>
 *
 * <p>The class is a per-process singleton. Both the browser process and the
 * engine process have their own instance and their own socket; the core serves
 * any number of clients.</p>
 */
public final class RvpnClient {

    private static final String TAG = "RvpnClient";

    /** Default timeout of a command. */
    public static final long DEFAULT_TIMEOUT_MS = 20_000L;
    /** {@code start} performs DNS, TCP, TLS and the login handshake. */
    public static final long START_TIMEOUT_MS = 120_000L;

    private static final long CONNECT_TIMEOUT_MS = 4_000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 6_000L;
    private static final long POLL_VISIBLE_MS = 500L;
    private static final long POLL_HIDDEN_MS = 2_000L;
    private static final long BACKOFF_MIN_MS = 500L;
    private static final long BACKOFF_MAX_MS = 10_000L;
    private static final int MAX_DELIVERED_CHALLENGES = 200;

    // ------------------------------------------------------------------ types

    /** Error raised by a failed command or by a lost connection. */
    public static final class CoreException extends Exception {
        public final String code;

        public CoreException(String message, String code) {
            super(message == null ? "" : message);
            this.code = code == null ? "" : code;
        }
    }

    /** Result callback, always invoked on the main thread. */
    public interface Callback {
        void onResult(JSONObject data, CoreException error);
    }

    /** Observer of core activity. All callbacks run on the main thread. */
    public interface Listener {
        /** Connection to the core process changed. {@code error} is "" when connected. */
        void onCoreConnection(boolean connected, String error);

        /** {@code hello} was answered; {@link #hello()} now holds the core description. */
        void onCoreHello();

        /** The session state changed. */
        void onCoreState(RvpnState state);

        /** A log / state / error / challengeDone event arrived. */
        void onCoreEvent(RvpnEvent event);

        /** A new interactive challenge must be answered. */
        void onCoreChallenge(RvpnChallenge challenge);

        /** A challenge was answered or timed out. */
        void onCoreChallengeDone(String challengeId);
    }

    // ---------------------------------------------------------------- singleton

    private static volatile RvpnClient instance;

    public static RvpnClient get(Context context) {
        RvpnClient local = instance;
        if (local == null) {
            synchronized (RvpnClient.class) {
                local = instance;
                if (local == null) {
                    local = new RvpnClient(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------ fields

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, ArrayBlockingQueue<JSONObject>> pending =
            new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final Object stateLock = new Object();
    private final LinkedHashSet<String> deliveredChallenges = new LinkedHashSet<>();
    private final ExecutorService callExecutor = Executors.newFixedThreadPool(2);

    private volatile boolean running;
    private volatile boolean connected;
    private volatile boolean engineAvailable;
    private volatile long pollIntervalMs = POLL_HIDDEN_MS;
    private volatile String lastError = "";
    private volatile String controlError = "";
    private volatile String enginePath = "";
    private volatile int attempt;
    private volatile long since;
    private volatile long nextId;
    private volatile JSONObject hello;
    private volatile RvpnState lastState = RvpnState.idle();
    private volatile String lastStateKey = "";
    private volatile boolean notifiedConnected;
    private volatile String notifiedError;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private Thread ioThread;
    private Thread readerThread;

    private RvpnClient(Context context) {
        this.appContext = context;
    }

    // -------------------------------------------------------------- lifecycle

    /** Starts connecting and polling. Idempotent. */
    public void start() {
        synchronized (stateLock) {
            if (running) {
                return;
            }
            running = true;
            attempt = 0;
            ioThread = new Thread(this::ioLoop, "rvpn-client-io");
            ioThread.setDaemon(true);
            ioThread.start();
        }
    }

    /** Stops polling and closes the socket. Idempotent. */
    public void stop() {
        Thread io;
        synchronized (stateLock) {
            if (!running) {
                return;
            }
            running = false;
            io = ioThread;
            ioThread = null;
        }
        if (io != null) {
            io.interrupt();
        }
        dropConnection("client stopped", false);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connected;
    }

    /** True once a connection to the core was established at least once. */
    public boolean isEngineAvailable() {
        return engineAvailable;
    }

    /** Reason why the engine is not reachable ("" when it is). */
    public String lastError() {
        return lastError;
    }

    /** Error published by {@code RvpnService} inside control.json ("" when none). */
    public String engineError() {
        return controlError;
    }

    /** Path of the control file, for diagnostics. */
    public String controlFilePath() {
        return enginePath;
    }

    /** {@code hello} reply of the core, or {@code null} before the first answer. */
    public JSONObject hello() {
        return hello;
    }

    public RvpnState lastState() {
        return lastState;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 500 ms polling while a UI is visible, 2 s otherwise. */
    public void setForeground(boolean foreground) {
        pollIntervalMs = foreground ? POLL_VISIBLE_MS : POLL_HIDDEN_MS;
    }

    public long pollIntervalMs() {
        return pollIntervalMs;
    }

    // --------------------------------------------------------------- requests

    /**
     * Sends one command and waits for its reply.
     *
     * <p>Never call this from the main thread - use {@link #call} instead.</p>
     *
     * @param cmd command name (hello, start, stop, status, answer, authInfo, ...)
     * @param params extra fields merged into the request envelope, may be null
     * @param timeoutMs how long to wait for the reply
     */
    public JSONObject request(String cmd, JSONObject params, long timeoutMs) throws CoreException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("RvpnClient.request() must not be called on the main thread");
        }
        long start = SystemClock.elapsedRealtime();
        long connectBudget = Math.min(timeoutMs / 2, 10_000L);
        if (!waitForConnection(connectBudget)) {
            throw new CoreException(lastError.isEmpty()
                    ? appContext.getString(R.string.err_disconnected) : lastError,
                    "NOT_CONNECTED");
        }
        long remaining = timeoutMs - (SystemClock.elapsedRealtime() - start);
        if (remaining <= 0) {
            throw new CoreException(appContext.getString(R.string.err_timeout, cmd), "TIMEOUT");
        }

        String id = Long.toString(++nextId);
        JSONObject req = new JSONObject();
        ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(1);
        try {
            req.put("id", Long.parseLong(id));
            req.put("cmd", cmd);
            if (params != null) {
                Iterator<String> keys = params.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    req.put(key, params.get(key));
                }
            }
        } catch (Exception e) {
            throw new CoreException("cannot build the request: " + e.getMessage(), "INTERNAL");
        }

        pending.put(id, queue);
        try {
            writeLine(req.toString());
        } catch (IOException e) {
            pending.remove(id);
            dropConnection(e.getMessage() == null ? e.toString() : e.getMessage(), true);
            throw new CoreException(appContext.getString(R.string.err_disconnected), "DISCONNECTED");
        }

        JSONObject resp;
        try {
            resp = queue.poll(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new CoreException(appContext.getString(R.string.err_disconnected), "INTERRUPTED");
        } finally {
            pending.remove(id);
        }

        if (resp == null) {
            // A reply that never arrives means the core is stuck or gone.
            dropConnection("timeout waiting for \"" + cmd + "\"", true);
            throw new CoreException(appContext.getString(R.string.err_timeout, cmd), "TIMEOUT");
        }
        if (!resp.optBoolean("ok", false)) {
            throw new CoreException(
                    Json.str(resp, "error", appContext.getString(R.string.err_core_error, cmd)),
                    Json.str(resp, "code", "INTERNAL"));
        }
        return Json.obj(resp, "data");
    }

    /** Asynchronous {@link #request}: runs off the main thread, answers on it. */
    public void call(final String cmd, final JSONObject params, final long timeoutMs,
                     final Callback callback) {
        callExecutor.execute(() -> {
            JSONObject data = null;
            CoreException failure = null;
            try {
                data = request(cmd, params, timeoutMs);
            } catch (CoreException e) {
                failure = e;
            } catch (Throwable t) {
                failure = new CoreException(String.valueOf(t), "INTERNAL");
            }
            final JSONObject resultData = data;
            final CoreException resultError = failure;
            main.post(() -> {
                if (callback != null) {
                    callback.onResult(resultData, resultError);
                }
            });
        });
    }

    public void call(String cmd, JSONObject params, Callback callback) {
        call(cmd, params, DEFAULT_TIMEOUT_MS, callback);
    }

    // ------------------------------------------------------------ connect loop

    private void ioLoop() {
        boolean repollImmediately = false;
        while (running) {
            try {
                if (!connected) {
                    if (!connectOnce()) {
                        sleepQuietly(backoffDelay());
                        continue;
                    }
                }
                if (!repollImmediately) {
                    sleepQuietly(pollIntervalMs);
                    if (!running) {
                        break;
                    }
                }
                repollImmediately = false;
                if (!connected) {
                    continue;
                }
                JSONObject data = request("status", statusParams(), 8_000L);
                repollImmediately = applyStatus(data);
            } catch (CoreException e) {
                if (connected && !isTransient(e)) {
                    // The core answered with an error; keep polling.
                    Log.w(TAG, "status failed: " + e.getMessage());
                    sleepQuietly(pollIntervalMs);
                } else {
                    dropConnection(e.getMessage(), true);
                    sleepQuietly(backoffDelay());
                }
            } catch (Throwable t) {
                Log.w(TAG, "poll loop error", t);
                dropConnection(String.valueOf(t), true);
                sleepQuietly(backoffDelay());
            }
        }
        dropConnection("poll loop stopped", false);
    }

    private boolean isTransient(CoreException e) {
        return !"NOT_CONNECTED".equals(e.code) && !"DISCONNECTED".equals(e.code)
                && !"TIMEOUT".equals(e.code) && !"INTERRUPTED".equals(e.code);
    }

    private JSONObject statusParams() {
        JSONObject p = new JSONObject();
        try {
            p.put("since", since);
        } catch (Exception ignored) {
            // put(long) cannot fail.
        }
        return p;
    }

    private long backoffDelay() {
        int n = Math.min(attempt, 5);
        long delay = BACKOFF_MIN_MS * (1L << n);
        if (delay > BACKOFF_MAX_MS) {
            delay = BACKOFF_MAX_MS;
        }
        return delay;
    }

    /** One connection attempt. Returns true when the handshake succeeded. */
    private boolean connectOnce() {
        Control control = readControl();
        if (control == null) {
            setDisconnected(appContext.getString(R.string.engine_unavailable_body), false);
            return false;
        }
        if (control.error != null && !control.error.isEmpty()) {
            controlError = control.error;
            setDisconnected(appContext.getString(R.string.engine_error, control.error), false);
            return false;
        }
        controlError = "";
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(control.host, control.port), (int) CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setSoTimeout((int) HANDSHAKE_TIMEOUT_MS);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            // docs/PROTOCOL.md: the very first line must be the token.
            w.write("{\"token\":" + JSONObject.quote(control.token) + "}\n");
            w.flush();
            String line = r.readLine();
            if (line == null) {
                throw new IOException("core closed the connection during the handshake");
            }
            JSONObject resp = Json.parse(line);
            if (resp == null || !resp.optBoolean("ok", false)) {
                throw new IOException("handshake rejected: "
                        + (resp == null ? line : Json.str(resp, "error", "unknown")));
            }
            s.setSoTimeout(0);

            synchronized (stateLock) {
                socket = s;
                reader = r;
                writer = w;
            }
            sessionToken = control.token;
            startReaderThread(r);
            attempt = 0;
            engineAvailable = true;
            setConnected();
            fetchHello();
            return true;
        } catch (Throwable t) {
            closeQuietly(s);
            attempt++;
            setDisconnected(appContext.getString(R.string.core_disconnected,
                    String.valueOf(t.getMessage() == null ? t : t.getMessage())), false);
            return false;
        }
    }

    private void startReaderThread(final BufferedReader r) {
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    JSONObject response = Json.parse(line);
                    if (response == null) {
                        continue;
                    }
                    Object idValue = response.opt("id");
                    if (idValue == null || idValue == JSONObject.NULL) {
                        continue;
                    }
                    String id = String.valueOf(idValue);
                    ArrayBlockingQueue<JSONObject> queue = pending.get(id);
                    if (queue != null) {
                        queue.offer(response);
                    }
                }
            } catch (Throwable t) {
                // Connection closed or reset: handled below.
            } finally {
                // Only report a lost connection when the client is still supposed
                // to be running; stop() tears the socket down on purpose.
                dropConnection("core closed the connection", running);
            }
        }, "rvpn-client-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void fetchHello() {
        try {
            JSONObject data = request("hello", null, 8_000L);
            if (data != null) {
                hello = data;
                dispatchHello();
            }
        } catch (CoreException e) {
            Log.w(TAG, "hello failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- status

    /**
     * Applies one {@code status} reply.
     *
     * @return true when the poll should be repeated immediately (the core
     *     restarted and its event sequence went backwards).
     */
    private boolean applyStatus(JSONObject data) {
        if (data == null) {
            return false;
        }
        RvpnState state = RvpnState.from(Json.obj(data, "state"));
        if (state != null) {
            String key = state.key();
            if (!key.equals(lastStateKey)) {
                lastStateKey = key;
                lastState = state;
                dispatchState(state);
            }
        }

        long next = Json.lng(data, "nextSince", since);
        boolean restarted = next < since;
        since = restarted ? 0 : next;

        JSONArray events = Json.arr(data, "events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                RvpnEvent event = RvpnEvent.from(events.optJSONObject(i));
                if (event == null) {
                    continue;
                }
                dispatchEvent(event);
            }
        }

        // The Go core publishes the pending challenges in the status reply as
        // well (session.go); events remain the primary source, this is a safety
        // net so a challenge is never lost when the UI was closed.
        JSONArray pendingChallenges = Json.arr(data, "pending");
        if (pendingChallenges != null) {
            for (int i = 0; i < pendingChallenges.length(); i++) {
                RvpnChallenge challenge = RvpnChallenge.from(pendingChallenges.optJSONObject(i));
                if (challenge != null) {
                    dispatchChallenge(challenge);
                }
            }
        }
        return restarted;
    }

    private void dispatchEvent(RvpnEvent event) {
        if (RvpnEvent.KIND_CHALLENGE.equals(event.kind) && event.challenge != null) {
            dispatchChallenge(event.challenge);
            return;
        }
        if (RvpnEvent.KIND_CHALLENGE_DONE.equals(event.kind)) {
            String id = event.challenge != null ? event.challenge.id : event.message;
            forgetChallenge(id);
            dispatchChallengeDone(id);
            return;
        }
        for (Listener l : listeners) {
            main.post(() -> l.onCoreEvent(event));
        }
    }

    private void dispatchChallenge(RvpnChallenge challenge) {
        synchronized (deliveredChallenges) {
            if (!deliveredChallenges.add(challenge.id)) {
                return;
            }
            while (deliveredChallenges.size() > MAX_DELIVERED_CHALLENGES) {
                Iterator<String> it = deliveredChallenges.iterator();
                it.next();
                it.remove();
            }
        }
        for (Listener l : listeners) {
            main.post(() -> l.onCoreChallenge(challenge));
        }
    }

    private void forgetChallenge(String id) {
        if (id == null) {
            return;
        }
        synchronized (deliveredChallenges) {
            deliveredChallenges.remove(id);
        }
    }

    private void dispatchChallengeDone(String id) {
        for (Listener l : listeners) {
            main.post(() -> l.onCoreChallengeDone(id));
        }
    }

    private void dispatchState(RvpnState state) {
        for (Listener l : listeners) {
            main.post(() -> l.onCoreState(state));
        }
    }

    private void dispatchHello() {
        for (Listener l : listeners) {
            main.post(l::onCoreHello);
        }
    }

    // -------------------------------------------------------- connection state

    private void setConnected() {
        lastError = "";
        synchronized (stateLock) {
            connected = true;
        }
        notifyConnection(true, "");
    }

    private void setDisconnected(String error, boolean notify) {
        String message = error == null ? "" : error;
        lastError = message;
        synchronized (stateLock) {
            connected = false;
        }
        if (notify) {
            notifyConnection(false, message);
        }
    }

    /** Fans a connection state change out, but only when it actually changed. */
    private void notifyConnection(boolean nowConnected, String error) {
        String message = error == null ? "" : error;
        synchronized (stateLock) {
            if (notifiedError != null && notifiedConnected == nowConnected
                    && message.equals(notifiedError)) {
                return;
            }
            notifiedConnected = nowConnected;
            notifiedError = message;
        }
        for (Listener l : listeners) {
            main.post(() -> l.onCoreConnection(nowConnected, message));
        }
    }

    private void dropConnection(String reason, boolean notify) {
        synchronized (stateLock) {
            connected = false;
            closeLocked();
        }
        failPending(reason);
        if (notify) {
            String message = reason == null ? "" : reason;
            lastError = message;
            notifyConnection(false, message);
        }
    }

    private void failPending(String reason) {
        if (pending.isEmpty()) {
            return;
        }
        JSONObject failure = new JSONObject();
        try {
            failure.put("ok", false);
            failure.put("error", reason == null ? "disconnected" : reason);
            failure.put("code", "DISCONNECTED");
        } catch (Exception ignored) {
            // put(String) never fails.
        }
        for (Map.Entry<String, ArrayBlockingQueue<JSONObject>> entry : pending.entrySet()) {
            entry.getValue().offer(failure);
        }
    }

    private void closeLocked() {
        closeQuietly(socket);
        socket = null;
        reader = null;
        writer = null;
    }

    private void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    private void writeLine(String line) throws IOException {
        synchronized (writeLock) {
            BufferedWriter w = writer;
            if (w == null) {
                throw new IOException("not connected");
            }
            w.write(line);
            w.write('\n');
            w.flush();
        }
    }

    private boolean waitForConnection(long budgetMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0, budgetMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connected) {
                return true;
            }
            sleepQuietly(50L);
        }
        return connected;
    }

    private void sleepQuietly(long ms) {
        long deadline = SystemClock.elapsedRealtime() + ms;
        while (running && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(Math.min(100L, Math.max(1L, deadline - SystemClock.elapsedRealtime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // -------------------------------------------------------- control.json

    /** Endpoint published by RvpnService. */
    private static final class Control {
        String host;
        int port;
        String token;
        String error;
    }

    private Control readControl() {
        File file = new File(new File(appContext.getFilesDir(), "rvpn"), "control.json");
        enginePath = file.getAbsolutePath();
        if (!file.isFile()) {
            lastError = appContext.getString(R.string.engine_unavailable_body);
            return null;
        }
        String raw;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int read = in.read(buffer);
            raw = read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            lastError = "cannot read " + enginePath + ": " + e.getMessage();
            return null;
        }
        JSONObject o = Json.parse(raw);
        if (o == null) {
            lastError = "invalid " + enginePath;
            return null;
        }
        String error = Json.str(o, "error");
        if (!error.isEmpty()) {
            Control c = new Control();
            c.error = error;
            return c;
        }
        String addr = Json.str(o, "addr");
        String token = Json.str(o, "token");
        int sep = addr.lastIndexOf(':');
        if (addr.isEmpty() || sep <= 0 || sep == addr.length() - 1) {
            lastError = "invalid control address: " + addr;
            return null;
        }
        Control c = new Control();
        try {
            c.host = addr.substring(0, sep);
            c.port = Integer.parseInt(addr.substring(sep + 1).trim());
        } catch (NumberFormatException e) {
            lastError = "invalid control address: " + addr;
            return null;
        }
        c.token = token;
        if (c.token == null || c.token.isEmpty()) {
            lastError = "control file without a token: " + enginePath;
            return null;
        }
        return c;
    }
}
