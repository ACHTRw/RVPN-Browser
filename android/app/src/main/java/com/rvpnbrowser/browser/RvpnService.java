package com.rvpnbrowser.browser;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.system.Os;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.rvpnbrowser.core.rvpncore.Rvpncore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the RVPN engine in the dedicated {@code :rvpn} process.
 *
 * <p>Life cycle:</p>
 * <ol>
 *   <li>{@code onCreate} immediately posts the ongoing notification (a
 *       foreground service must do that within a few seconds),</li>
 *   <li>{@link Rvpncore#startControlServer()} opens the loopback control socket,
 *       whose address and token are published in
 *       {@code <filesDir>/rvpn/control.json} with mode 0600,</li>
 *   <li>the {@code start} command is sent through that socket - exactly the same
 *       protocol the desktop extension speaks,</li>
 *   <li>challenges are surfaced as notifications even when no UI is open,</li>
 *   <li>{@code onDestroy} calls {@link Rvpncore#shutdown()} and removes the
 *       control file so no stale endpoint is left behind.</li>
 * </ol>
 *
 * <p>If the engine cannot be loaded (for example when the AAR has no native
 * library for this ABI) the service degrades gracefully: the browser keeps
 * working, the failure is published in control.json and shown in the UI.</p>
 */
public class RvpnService extends Service implements RvpnClient.Listener {

    private static final String TAG = "RvpnService";

    public static final String ACTION_START = "com.rvpnbrowser.browser.action.RVPN_START";
    public static final String ACTION_STOP = "com.rvpnbrowser.browser.action.RVPN_STOP";
    public static final String EXTRA_CONFIG = "config";

    private static final String CONTROL_DIR = "rvpn";
    private static final String CONTROL_FILE = "control.json";

    private RvpnClient client;
    private ExecutorService engineExecutor;
    private volatile String runningConfig = "";
    private volatile String controlAddr = "";
    private volatile boolean engineStarted;
    private volatile String lastNotificationText = "";

    // ------------------------------------------------------------- entry points

    /** Starts (or re-configures) the engine. Must be called from the foreground. */
    public static void start(Context context, JSONObject config) {
        Intent intent = new Intent(context, RvpnService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config == null ? "" : config.toString());
        startServiceSafely(context, intent, "start");
    }

    /** Stops the engine and the service. */
    public static void stop(Context context) {
        Intent intent = new Intent(context, RvpnService.class).setAction(ACTION_STOP);
        startServiceSafely(context, intent, "stop");
    }

    private static void startServiceSafely(Context context, Intent intent, String what) {
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable t) {
            // Android 12+ refuses background foreground-service starts; the caller
            // is responsible for telling the user (see BootReceiver).
            Log.w(TAG, "cannot " + what + " the RVPN service: " + t);
            throw t;
        }
    }

    // ------------------------------------------------------------- life cycle

    @Override
    public void onCreate() {
        super.onCreate();
        Notifications.createChannels(this);
        client = RvpnClient.get(this);
        client.addListener(this);
        client.setForeground(false);
        engineExecutor = Executors.newSingleThreadExecutor();
        // Must happen before anything else: the platform kills a foreground
        // service that does not post its notification quickly enough.
        startForegroundCompat(getString(R.string.notif_status_preparing));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "stop requested");
            stopEngine();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        String config = intent == null ? null : intent.getStringExtra(EXTRA_CONFIG);
        if (config == null || config.isEmpty()) {
            // START_STICKY restart after the process was killed: rebuild the
            // configuration from the persisted settings.
            config = new RvpnPrefs(this).configJson().toString();
            Log.i(TAG, "restarting the engine from the persisted settings");
        }
        startEngine(config);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "destroying the engine service");
        if (client != null) {
            client.removeListener(this);
            client.stop();
        }
        if (engineExecutor != null) {
            engineExecutor.shutdownNow();
            engineExecutor = null;
        }
        try {
            Rvpncore.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "Shutdown() failed: " + t);
        }
        engineStarted = false;
        deleteControlFile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Started service only: the UI talks over the loopback control socket.
        return null;
    }

    // ---------------------------------------------------------------- engine

    private void startEngine(final String configJson) {
        if (configJson.equals(runningConfig) && engineStarted) {
            Log.i(TAG, "engine already running with this configuration");
            return;
        }
        final boolean restart = engineStarted;
        engineExecutor.execute(() -> {
            try {
                String addr = Rvpncore.startControlServer();
                if (addr == null || addr.isEmpty()) {
                    engineFailed(getString(R.string.engine_unavailable_body)
                            + " (StartControlServer returned no address)");
                    return;
                }
                String token = Rvpncore.controlServerToken();
                if (token == null || token.isEmpty()) {
                    engineFailed(getString(R.string.engine_unavailable_body)
                            + " (empty control token)");
                    return;
                }
                controlAddr = addr;
                writeControlFile(addr, token, null);
                engineStarted = true;
                client.start();
                if (restart) {
                    sendCommand("stop", null, () -> sendStart(configJson));
                } else {
                    sendStart(configJson);
                }
            } catch (Throwable t) {
                // UnsatisfiedLinkError (no native library for this ABI), an OOM in
                // the Go runtime, ... - never let it escape into the process.
                Log.e(TAG, "cannot start the engine", t);
                engineFailed(String.valueOf(t));
            }
        });
    }

    private void sendStart(final String configJson) {
        JSONObject params = new JSONObject();
        JSONObject config = Json.parse(configJson);
        if (config == null) {
            engineFailed("invalid configuration: " + configJson);
            return;
        }
        try {
            params.put("config", config);
        } catch (Exception e) {
            engineFailed("invalid configuration: " + e.getMessage());
            return;
        }
        client.call("start", params, RvpnClient.START_TIMEOUT_MS, (data, error) -> {
            if (error == null) {
                runningConfig = configJson;
                Log.i(TAG, "engine started");
                updateStatusNotification();
                return;
            }
            if ("ALREADY_RUNNING".equals(error.code)) {
                // The session survived a service restart: reconnect with the new
                // configuration.
                Log.i(TAG, "engine already running, restarting it");
                sendCommand("stop", null, () -> sendStart(configJson));
                return;
            }
            engineFailed(error.getMessage());
        });
    }

    private interface AfterCommand {
        void run();
    }

    private void sendCommand(final String cmd, JSONObject params, final AfterCommand after) {
        client.call(cmd, params, RvpnClient.DEFAULT_TIMEOUT_MS, (data, error) -> {
            if (error != null) {
                Log.w(TAG, cmd + " failed: " + error.getMessage());
            }
            if (after != null) {
                after.run();
            }
        });
    }

    private void stopEngine() {
        if (client != null && client.isConnected()) {
            sendCommand("stop", null, null);
        }
        engineStarted = false;
        runningConfig = "";
    }

    /** Publishes the failure so the UI can explain what is wrong. */
    private void engineFailed(String message) {
        engineStarted = false;
        deleteControlFile();
        writeControlFile(null, null, message == null ? "unknown engine error" : message);
        showNotification(getString(R.string.engine_unavailable_title), message, false);
    }

    // ------------------------------------------------------------ control file

    /**
     * Atomically publishes {@code {"addr":"127.0.0.1:PORT","token":"..."}} (or
     * {@code {"error":"..."}}) with mode 0600 inside the private app storage.
     */
    private void writeControlFile(String addr, String token, String error) {
        File dir = new File(getFilesDir(), CONTROL_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create " + dir);
            return;
        }
        File tmp = new File(dir, CONTROL_FILE + ".tmp");
        File dst = new File(dir, CONTROL_FILE);
        JSONObject payload = new JSONObject();
        try {
            if (error != null) {
                payload.put("error", error);
            } else {
                payload.put("addr", addr);
                payload.put("token", token);
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot build the control payload", e);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            Log.e(TAG, "cannot write " + tmp, e);
            return;
        }
        restrictToOwner(tmp);
        if (dst.exists() && !dst.delete()) {
            Log.w(TAG, "cannot replace " + dst);
        }
        if (!tmp.renameTo(dst)) {
            Log.e(TAG, "cannot rename " + tmp + " to " + dst);
            return;
        }
        restrictToOwner(dst);
        Log.i(TAG, "control endpoint published in " + dst + " (" + addr + ")");
    }

    private void restrictToOwner(File file) {
        try {
            Os.chmod(file.getAbsolutePath(), 0600);
        } catch (Throwable t) {
            // Fall back to the java.io API when the syscall is unavailable.
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            file.setWritable(false, false);
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, true);
            //noinspection ResultOfMethodCallIgnored
            file.setWritable(true, true);
        }
    }

    private void deleteControlFile() {
        File dir = new File(getFilesDir(), CONTROL_DIR);
        File dst = new File(dir, CONTROL_FILE);
        File tmp = new File(dir, CONTROL_FILE + ".tmp");
        if (dst.exists() && !dst.delete()) {
            Log.w(TAG, "cannot delete " + dst);
        }
        if (tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "cannot delete " + tmp);
        }
        controlAddr = "";
    }

    // ------------------------------------------------------------ notification

    private void startForegroundCompat(String text) {
        android.app.Notification notification = Notifications.statusNotification(this,
                getString(R.string.notif_status_title, getString(R.string.notif_status_idle)),
                text, true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifications.ID_STATUS, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notifications.ID_STATUS, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
        }
        lastNotificationText = text;
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground failed: " + t);
        }
    }

    private void showNotification(String title, String text, boolean disconnectAction) {
        String key = title + '\u0000' + text;
        if (key.equals(lastNotificationText)) {
            return;
        }
        lastNotificationText = key;
        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_STATUS,
                    Notifications.statusNotification(this, title, text == null ? "" : text,
                            disconnectAction));
        } catch (Throwable t) {
            Log.w(TAG, "cannot post the status notification: " + t);
        }
    }

    private void updateStatusNotification() {
        RvpnState state = client == null ? null : client.lastState();
        if (state == null) {
            return;
        }
        String label = getString(phaseLabel(state.phase));
        String text = state.message.isEmpty() ? label : state.message;
        if (state.isConnected() && !state.clientIP.isEmpty()) {
            text = text + " · " + state.clientIP;
        }
        showNotification(getString(R.string.notif_status_title, label), text, true);
    }

    private int phaseLabel(String phase) {
        switch (phase) {
            case RvpnState.PHASE_CONNECTING:
                return R.string.phase_connecting;
            case RvpnState.PHASE_AUTHENTICATING:
                return R.string.phase_authenticating;
            case RvpnState.PHASE_CONNECTED:
                return R.string.phase_connected;
            case RvpnState.PHASE_ERROR:
                return R.string.phase_error;
            case RvpnState.PHASE_STOPPING:
                return R.string.phase_stopping;
            default:
                return R.string.phase_idle;
        }
    }

    // -------------------------------------------------------- client listener

    @Override
    public void onCoreConnection(boolean connected, String error) {
        if (!connected) {
            updateStatusNotification();
        }
    }

    @Override
    public void onCoreHello() {
        // Nothing to do: the UI reads the core description itself.
    }

    @Override
    public void onCoreState(RvpnState state) {
        updateStatusNotification();
    }

    @Override
    public void onCoreEvent(RvpnEvent event) {
        if (event.isError()) {
            showNotification(getString(R.string.notif_status_title, getString(R.string.phase_error)),
                    event.message, true);
        }
    }

    @Override
    public void onCoreChallenge(RvpnChallenge challenge) {
        String type = getString(challenge.typeLabelRes());
        String text = challenge.message.isEmpty()
                ? getString(R.string.notif_challenge_body) : challenge.message;
        try {
            NotificationManagerCompat.from(this).notify(Notifications.challengeId(challenge.id),
                    Notifications.challengeNotification(this, challenge.id,
                            getString(R.string.notif_challenge_title, type), text));
        } catch (Throwable t) {
            Log.w(TAG, "cannot post the challenge notification: " + t);
        }
    }

    @Override
    public void onCoreChallengeDone(String challengeId) {
        if (challengeId == null || challengeId.isEmpty()) {
            return;
        }
        try {
            NotificationManagerCompat.from(this).cancel(Notifications.challengeId(challengeId));
        } catch (Throwable t) {
            Log.w(TAG, "cannot cancel the challenge notification: " + t);
        }
    }
}
