package com.rvpnbrowser.browser;

import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;

/**
 * One browser tab. The {@link WebView} instance belongs to the tab and is kept
 * alive while the tab exists, so back/forward history and page state survive
 * switching tabs.
 */
public final class BrowserTab {

    /** The WebView of this tab; never null. */
    public final WebView webView;

    public String title = "";
    public String url = "";
    public Bitmap favicon;
    public int progress;

    /** Pending {@code <input type="file">} request of this tab (one at a time). */
    public ValueCallback<Uri[]> fileChooserCallback;

    public BrowserTab(WebView webView) {
        this.webView = webView;
    }

    /** Title shown in the tab strip, falling back to the URL and then to a placeholder. */
    public String displayTitle(String fallback) {
        if (title != null && !title.trim().isEmpty()) {
            return title;
        }
        if (url != null && !url.isEmpty() && !TabManager.HOME_URL.equals(url)) {
            return url;
        }
        return fallback;
    }

    @Override
    public String toString() {
        return "BrowserTab{" + title + ", " + url + "}";
    }
}
