package com.rvpnbrowser.browser;

import android.util.Log;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Points <b>all</b> WebViews of the app at the local proxy of the RVPN core, or
 * removes that override again.
 *
 * <p>This is the Android counterpart of {@code chrome.proxy.settings} in the
 * desktop extension (docs/PROTOCOL.md section 4.3: the front-end owns the
 * proxy). It uses {@code androidx.webkit}, because the platform {@code WebView}
 * API has no proxy support at all.</p>
 *
 * <p>Rule preference: SOCKS5 first (the core's SOCKS5 listener understands the
 * same split-tunnel rules), then the documented {@code socks://} spelling, and
 * finally the HTTP proxy - the core always listens on both, so the fallback
 * never needs a second connection attempt by the user.</p>
 *
 * <p>The androidx API offers no way to read the override back; "applied" means
 * the call returned without throwing and the listener ran (see the
 * documentation of {@link ProxyController#setProxyOverride}). Every failure is
 * reported to the caller so the UI can say what happened instead of pretending
 * that RVPN mode is active.</p>
 */
public final class BrowserProxy {

    private static final String TAG = "BrowserProxy";

    /** Outcome of an apply/clear operation. */
    public static final class Result {
        public final boolean ok;
        /** Endpoint that is now in use ("" after a successful clear). */
        public final String endpoint;
        /** True when the SOCKS5 rule was rejected and the HTTP proxy is used. */
        public final boolean socksFallback;
        /** Technical error description when {@link #ok} is false. */
        public final String error;

        Result(boolean ok, String endpoint, boolean socksFallback, String error) {
            this.ok = ok;
            this.endpoint = endpoint == null ? "" : endpoint;
            this.socksFallback = socksFallback;
            this.error = error;
        }
    }

    /** Callback, invoked on the worker thread of this class. */
    public interface Callback {
        void onResult(Result result);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rvpn-proxy");
        t.setDaemon(true);
        return t;
    });

    /**
     * Separate executor for the WebView listeners. Passing {@link #WORKER} would
     * deadlock: the worker thread blocks until the listener ran, and the listener
     * could never be scheduled onto the blocked thread.
     */
    private static final ExecutorService CALLBACK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rvpn-proxy-callback");
        t.setDaemon(true);
        return t;
    });

    private BrowserProxy() {
    }

    /** True when this WebView build supports a per-app proxy override. */
    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        } catch (Throwable t) {
            Log.w(TAG, "PROXY_OVERRIDE support check failed", t);
            return false;
        }
    }

    /**
     * Installs the proxy override.
     *
     * @param socksAddr core SOCKS5 endpoint, may be empty
     * @param httpAddr core HTTP proxy endpoint, may be empty
     */
    public static void apply(final String socksAddr, final String httpAddr, final Callback callback) {
        WORKER.execute(() -> callback.onResult(applySync(socksAddr, httpAddr)));
    }

    private static Result applySync(String socksAddr, String httpAddr) {
        if (!isSupported()) {
            return new Result(false, "", false, "PROXY_OVERRIDE is not supported by this WebView");
        }
        String socks = normalize(socksAddr);
        String http = normalize(httpAddr);
        if (socks.isEmpty() && http.isEmpty()) {
            return new Result(false, "", false, "the core has not published a proxy address yet");
        }

        String[] candidates = socks.isEmpty()
                ? new String[]{"http://" + http}
                : (http.isEmpty()
                        ? new String[]{"socks5://" + socks, "socks://" + socks}
                        : new String[]{"socks5://" + socks, "socks://" + socks, "http://" + http});

        String lastError = "";
        for (int i = 0; i < candidates.length; i++) {
            String rule = candidates[i];
            boolean isSocks = rule.startsWith("socks");
            boolean fallback = !isSocks && !socks.isEmpty();
            try {
                ProxyConfig config = new ProxyConfig.Builder()
                        .addProxyRule(rule)
                        .addBypassRule("127.0.0.1")
                        .addBypassRule("localhost")
                        .build();
                final CountDownLatch applied = new CountDownLatch(1);
                ProxyController.getInstance().setProxyOverride(config, CALLBACK, applied::countDown);
                if (!applied.await(10, TimeUnit.SECONDS)) {
                    lastError = "the WebView did not confirm the proxy change";
                    Log.w(TAG, lastError + " (" + rule + ")");
                    continue;
                }
                Log.i(TAG, "proxy override installed: " + rule);
                return new Result(true, rule, fallback, "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, "", fallback, "interrupted");
            } catch (Throwable t) {
                // Rejected rule (IllegalArgumentException) or an unsupported
                // feature: try the next candidate.
                lastError = String.valueOf(t.getMessage() == null ? t : t.getMessage());
                Log.w(TAG, "proxy rule rejected (" + rule + "): " + lastError);
            }
        }
        return new Result(false, "", false, lastError);
    }

    /** Removes the proxy override - normal mode must never leave one behind. */
    public static void clear(final Callback callback) {
        WORKER.execute(() -> callback.onResult(clearSync()));
    }

    private static Result clearSync() {
        if (!isSupported()) {
            // Nothing was ever installed on such a device.
            return new Result(true, "", false, "");
        }
        try {
            final CountDownLatch cleared = new CountDownLatch(1);
            ProxyController.getInstance().clearProxyOverride(CALLBACK, cleared::countDown);
            if (!cleared.await(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "the WebView did not confirm clearing the proxy override");
                return new Result(false, "", false, "the WebView did not confirm the change");
            }
            Log.i(TAG, "proxy override cleared");
            return new Result(true, "", false, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "", false, "interrupted");
        } catch (Throwable t) {
            Log.w(TAG, "cannot clear the proxy override", t);
            return new Result(false, "", false,
                    String.valueOf(t.getMessage() == null ? t : t.getMessage()));
        }
    }

    private static String normalize(String address) {
        if (address == null) {
            return "";
        }
        String value = address.trim();
        if (value.isEmpty()) {
            return "";
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return "";
        }
        return value;
    }
}
