package com.rvpnbrowser.browser;

import android.app.Application;
import android.webkit.WebView;

/**
 * Application entry point.
 *
 * <p>It runs in every process of the app (the browser process and the
 * {@code :rvpn} engine process), therefore everything it does must be
 * idempotent and cheap: creating the notification channels twice is harmless,
 * which is exactly why it happens here instead of in the service.</p>
 */
public class RvpnApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Notifications.createChannels(this);
        if (BuildConfig.DEBUG) {
            // Lets the pages of a debug build be inspected with chrome://inspect.
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }
}
