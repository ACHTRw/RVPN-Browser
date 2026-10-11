package com.rvpnbrowser.browser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.Parcel;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Owns the tabs of the browser: their {@link WebView}s, the tab strip model, the
 * session that survives a restart and every {@code WebViewClient} /
 * {@code WebChromeClient} callback that needs app level behaviour.
 *
 * <p>At most {@link #MAX_TABS} tabs are kept in memory; adding one more closes
 * the oldest tab that is not the current one.</p>
 */
public final class TabManager {

    private static final String TAG = "TabManager";
    private static final String SESSION_FILE = "rvpn_session";
    private static final String SESSION_KEY = "tabs";
    private static final String SESSION_INDEX_KEY = "index";

    public static final int MAX_TABS = 12;
    public static final String HOME_URL = "file:///android_asset/home.html";
    public static final String BLANK_URL = "about:blank";
    public static final int REQUEST_FILE_CHOOSER = 1001;

    /** Notifications towards the activity. */
    public interface Observer {
        void onTabsChanged();

        void onTabUpdated(BrowserTab tab);

        void onProgressChanged(BrowserTab tab, int progress);

        void onPageStarted(BrowserTab tab);

        void onPageFinished(BrowserTab tab);

        void onPageError(BrowserTab tab, String message);

        void onRendererGone(BrowserTab tab);

        /** The home page (or any page) asked to open the RVPN settings. */
        void onOpenRvpnSettings();
    }

    private final Activity activity;
    private final FrameLayout container;
    private final Observer observer;
    private final SiteStore history;
    private final List<BrowserTab> tabs = new ArrayList<>();
    private int currentIndex = -1;

    public TabManager(Activity activity, FrameLayout container, Observer observer,
                      SiteStore history) {
        this.activity = activity;
        this.container = container;
        this.observer = observer;
        this.history = history;
    }

    // ------------------------------------------------------------------ model

    public List<BrowserTab> tabs() {
        return Collections.unmodifiableList(tabs);
    }

    public int count() {
        return tabs.size();
    }

    public int currentIndex() {
        return currentIndex;
    }

    public BrowserTab current() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) {
            return null;
        }
        return tabs.get(currentIndex);
    }

    public BrowserTab get(int index) {
        if (index < 0 || index >= tabs.size()) {
            return null;
        }
        return tabs.get(index);
    }

    /** Creates a tab and selects it. {@code url == null} loads the start page. */
    public BrowserTab newTab(String url, boolean foreground) {
        return newTab(url, foreground, true);
    }

    private BrowserTab newTab(String url, boolean foreground, boolean load) {
        if (tabs.size() >= MAX_TABS) {
            discardOldest();
        }
        BrowserTab tab = createTab();
        tabs.add(tab);
        if (foreground) {
            selectTab(tabs.size() - 1);
        } else {
            tab.webView.setVisibility(View.GONE);
            container.addView(tab.webView);
        }
        if (load) {
            load(tab, url == null || url.isEmpty() ? HOME_URL : url);
        }
        observer.onTabsChanged();
        return tab;
    }

    public void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        BrowserTab tab = tabs.remove(index);
        container.removeView(tab.webView);
        destroyWebView(tab.webView);
        if (tabs.isEmpty()) {
            currentIndex = -1;
            newTab(null, true);
            return;
        }
        if (currentIndex >= tabs.size()) {
            currentIndex = tabs.size() - 1;
        } else if (index < currentIndex) {
            currentIndex--;
        }
        selectTab(currentIndex);
    }

    public void closeCurrent() {
        closeTab(currentIndex);
    }

    public void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        currentIndex = index;
        for (int i = 0; i < tabs.size(); i++) {
            BrowserTab tab = tabs.get(i);
            if (i == index) {
                if (tab.webView.getParent() == null) {
                    container.addView(tab.webView, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                }
                tab.webView.setVisibility(View.VISIBLE);
                tab.webView.requestFocus();
            } else if (tab.webView.getParent() == container) {
                container.removeView(tab.webView);
            }
        }
        observer.onTabsChanged();
        observer.onTabUpdated(tabs.get(index));
    }

    /** Navigates the current tab (adding a scheme when the user typed a search term). */
    public void navigate(String input) {
        BrowserTab tab = current();
        if (tab == null || input == null) {
            return;
        }
        String url = normalizeInput(input);
        if (url.isEmpty()) {
            return;
        }
        load(tab, url);
    }

    public void load(BrowserTab tab, String url) {
        if (tab == null || url == null) {
            return;
        }
        tab.url = url;
        tab.webView.loadUrl(url);
        observer.onTabUpdated(tab);
    }

    public void loadHome() {
        load(current(), HOME_URL);
    }

    /** Turns "zju.edu.cn" into a URL and "hello world" into a search query. */
    public static String normalizeInput(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file://") || lower.startsWith("about:")
                || lower.startsWith("data:") || lower.startsWith("javascript:")) {
            return value;
        }
        if (lower.startsWith("rvpn://")) {
            return value;
        }
        boolean looksLikeHost = !value.contains(" ") && (value.contains(".") || value.contains(":"));
        if (looksLikeHost) {
            return "https://" + value;
        }
        return "https://www.bing.com/search?q=" + Uri.encode(value);
    }

    // ------------------------------------------------------------- web views

    private BrowserTab createTab() {
        WebView webView = new WebView(activity);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(ContextCompat.getColor(activity, R.color.window_background));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setGeolocationEnabled(false);
        // A browser must be able to show a page that mixes http images into an
        // https document (passive content); scripts and frames stay blocked.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " RVPNBrowser/1.0.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        webView.setWebViewClient(webViewClient);
        webView.setWebChromeClient(webChromeClient);
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                Downloads.enqueue(activity, url, userAgent, contentDisposition, mimeType));
        webView.setHorizontalScrollBarEnabled(true);
        webView.setVerticalScrollBarEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        return new BrowserTab(webView);
    }

    /** Destroys a WebView: no tab may leave one behind. */
    private void destroyWebView(WebView webView) {
        if (webView == null) {
            return;
        }
        try {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.removeAllViews();
            webView.clearHistory();
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
        } catch (Throwable t) {
            Log.w(TAG, "error while tearing a WebView down", t);
        }
        try {
            webView.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "WebView.destroy() failed", t);
        }
    }

    /** Closes the oldest tab that is not the current one to stay within the limit. */
    private void discardOldest() {
        for (int i = 0; i < tabs.size(); i++) {
            if (i != currentIndex) {
                BrowserTab tab = tabs.remove(i);
                container.removeView(tab.webView);
                destroyWebView(tab.webView);
                if (i < currentIndex) {
                    currentIndex--;
                }
                Ui.toast(activity, R.string.tab_discarded);
                return;
            }
        }
    }

    /** Everything must be gone when the activity dies. */
    public void destroyAll() {
        for (BrowserTab tab : new ArrayList<>(tabs)) {
            container.removeView(tab.webView);
            destroyWebView(tab.webView);
        }
        tabs.clear();
        currentIndex = -1;
    }

    private BrowserTab findTab(WebView webView) {
        for (BrowserTab tab : tabs) {
            if (tab.webView == webView) {
                return tab;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ session i/o

    /** Persists URL, title and the back/forward state of every tab. */
    public void saveSession() {
        try {
            JSONArray array = new JSONArray();
            for (BrowserTab tab : tabs) {
                JSONObject item = new JSONObject();
                item.put("url", tab.url == null ? "" : tab.url);
                item.put("title", tab.title == null ? "" : tab.title);
                Bundle state = new Bundle();
                tab.webView.saveState(state);
                item.put("state", encodeBundle(state));
                array.put(item);
            }
            activity.getSharedPreferences(SESSION_FILE, Activity.MODE_PRIVATE)
                    .edit()
                    .putString(SESSION_KEY, array.toString())
                    .putInt(SESSION_INDEX_KEY, Math.max(0, currentIndex))
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "cannot save the session", t);
        }
    }

    /** Restores the tabs of the previous run. Returns false when there were none. */
    public boolean restoreSession() {
        android.content.SharedPreferences prefs =
                activity.getSharedPreferences(SESSION_FILE, Activity.MODE_PRIVATE);
        String raw = prefs.getString(SESSION_KEY, "");
        if (raw.isEmpty()) {
            return false;
        }
        int index = prefs.getInt(SESSION_INDEX_KEY, 0);
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && i < MAX_TABS; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String url = Json.str(item, "url");
                BrowserTab tab = createTab();
                tab.title = Json.str(item, "title");
                tab.url = url;
                Bundle state = decodeBundle(Json.str(item, "state"));
                boolean restored = false;
                if (state != null) {
                    restored = tab.webView.restoreState(state) != null;
                }
                if (!restored && !url.isEmpty()) {
                    tab.webView.loadUrl(url);
                }
                tabs.add(tab);
                if (i == index) {
                    currentIndex = i;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot restore the session", t);
        }
        if (tabs.isEmpty()) {
            return false;
        }
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        selectTab(currentIndex);
        observer.onTabsChanged();
        return true;
    }

    private static String encodeBundle(Bundle bundle) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle);
            return Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        } finally {
            parcel.recycle();
        }
    }

    private static Bundle decodeBundle(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        Parcel parcel = Parcel.obtain();
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            return parcel.readBundle(TabManager.class.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "cannot decode a saved WebView state", t);
            return null;
        } finally {
            parcel.recycle();
        }
    }

    // ------------------------------------------------------------ file chooser

    /** Called by the activity with the result of the file chooser. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_FILE_CHOOSER) {
            return false;
        }
        BrowserTab tab = pendingFileChooser;
        pendingFileChooser = null;
        if (tab == null || tab.fileChooserCallback == null) {
            return true;
        }
        Uri[] uris = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                uris = new Uri[count];
                for (int i = 0; i < count; i++) {
                    uris[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        tab.fileChooserCallback.onReceiveValue(uris);
        tab.fileChooserCallback = null;
        return true;
    }

    private BrowserTab pendingFileChooser;

    // -------------------------------------------------------------- callbacks

    private final WebViewClient webViewClient = new WebViewClient() {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Redirects are followed normally: returning false lets the WebView
            // handle every http/https navigation inside this tab.
            return handleUrl(view, request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            BrowserTab tab = findTab(view);
            if (tab == null) {
                return;
            }
            tab.url = url;
            tab.progress = 0;
            observer.onPageStarted(tab);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            BrowserTab tab = findTab(view);
            if (tab == null) {
                return;
            }
            tab.url = url;
            String title = view.getTitle();
            if (title != null && !title.isEmpty()) {
                tab.title = title;
            }
            if (history != null && url != null && !url.isEmpty()
                    && !url.startsWith("file:///android_asset/") && !BLANK_URL.equals(url)
                    && !url.startsWith("data:")) {
                history.add(tab.title, url);
            }
            observer.onPageFinished(tab);
            observer.onTabUpdated(tab);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request == null || !request.isForMainFrame()) {
                return;
            }
            BrowserTab tab = findTab(view);
            if (tab == null) {
                return;
            }
            CharSequence description = error == null ? "" : error.getDescription();
            observer.onPageError(tab, description == null ? "" : description.toString());
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       android.net.http.SslError error) {
            // Never proceed silently: the user has to decide, and the default is
            // to cancel.
            BrowserTab tab = findTab(view);
            String detail = error == null ? "" : String.valueOf(error.getPrimaryError())
                    + " " + (error.getUrl() == null ? "" : error.getUrl());
            if (tab == null || activity.isFinishing()) {
                handler.cancel();
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.dialog_ssl_title)
                    .setMessage(activity.getString(R.string.dialog_ssl_message, detail))
                    .setPositiveButton(R.string.action_continue, (d, w) -> handler.proceed())
                    .setNegativeButton(R.string.action_cancel, (d, w) -> handler.cancel())
                    .setOnCancelListener(d -> handler.cancel())
                    .show();
        }

        @Override
        public void onReceivedClientCertRequest(WebView view,
                                                android.webkit.ClientCertRequest request) {
            request.cancel();
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view,
                                              android.webkit.HttpAuthHandler handler,
                                              String host, String realm) {
            handler.cancel();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            BrowserTab tab = findTab(view);
            if (tab == null) {
                return true;
            }
            String lastUrl = tab.url;
            try {
                if (view.getUrl() != null) {
                    lastUrl = view.getUrl();
                }
            } catch (Throwable ignored) {
                // A dead WebView may refuse to answer.
            }
            Log.w(TAG, "the renderer of a tab is gone, recreating it: " + lastUrl);
            recreateTab(tab, lastUrl);
            observer.onRendererGone(tab);
            return true;
        }
    };

    /** Handles a navigation request. Returns true when the app took it over. */
    private boolean handleUrl(WebView view, String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String scheme = Uri.parse(url).getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.US);
        switch (scheme) {
            case "http":
            case "https":
            case "file":
            case "data":
            case "about":
            case "javascript":
            case "blob":
            case "content":
                return false;
            case "rvpn":
                handleRvpnScheme(url);
                return true;
            default:
                openExternally(url);
                return true;
        }
    }

    /** Handles the links of the built-in start page. */
    private void handleRvpnScheme(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if ("settings".equals(host)) {
            observer.onOpenRvpnSettings();
            return;
        }
        if ("open".equals(host)) {
            String target = uri.getQueryParameter("url");
            if (target != null && !target.isEmpty()) {
                newTab(target, true);
            }
        }
    }

    private void openExternally(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Ui.openExternal(activity, url);
        }
    }

    /** Replaces a dead WebView with a fresh one loading the same URL. */
    private void recreateTab(BrowserTab tab, String url) {
        int index = tabs.indexOf(tab);
        if (index < 0) {
            return;
        }
        boolean wasCurrent = index == currentIndex;
        container.removeView(tab.webView);
        destroyWebView(tab.webView);
        BrowserTab replacement = createTab();
        replacement.title = tab.title;
        replacement.url = url;
        tabs.set(index, replacement);
        if (wasCurrent) {
            currentIndex = index;
            selectTab(index);
        }
        load(replacement, url == null || url.isEmpty() ? HOME_URL : url);
        observer.onTabsChanged();
    }

    private final WebChromeClient webChromeClient = new WebChromeClient() {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            BrowserTab tab = findTab(view);
            if (tab == null) {
                return;
            }
            tab.progress = newProgress;
            observer.onProgressChanged(tab, newProgress);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            BrowserTab tab = findTab(view);
            if (tab == null) {
                return;
            }
            tab.title = title == null ? "" : title;
            observer.onTabUpdated(tab);
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            BrowserTab tab = findTab(view);
            if (tab == null) {
                return;
            }
            tab.favicon = icon;
            observer.onTabUpdated(tab);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                return false;
            }
            // A new tab owns the new WebView; the transport then loads the popup
            // URL into it.
            BrowserTab tab = newTab(null, true, false);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(tab.webView);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            BrowserTab tab = findTab(window);
            if (tab != null) {
                closeTab(tabs.indexOf(tab));
            }
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message,
                                 final JsResult result) {
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.dialog_js_message_title, hostOf(url)))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                                   final JsResult result) {
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.dialog_js_message_title, hostOf(url)))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  final JsPromptResult result) {
            final EditText input = new EditText(activity);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setSingleLine(true);
            int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
            input.setPadding(padding, padding, padding, padding);
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.dialog_js_message_title, hostOf(url)))
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         android.webkit.ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            BrowserTab tab = findTab(webView);
            if (tab == null) {
                return false;
            }
            if (tab.fileChooserCallback != null) {
                tab.fileChooserCallback.onReceiveValue(null);
            }
            tab.fileChooserCallback = filePathCallback;
            pendingFileChooser = tab;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            if (fileChooserParams != null) {
                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                String[] types = fileChooserParams.getAcceptTypes();
                if (types != null && types.length > 0 && !types[0].isEmpty()) {
                    intent.setType(types[0]);
                }
            }
            try {
                activity.startActivityForResult(
                        Intent.createChooser(intent, activity.getString(R.string.action_open)),
                        REQUEST_FILE_CHOOSER);
            } catch (Throwable t) {
                Log.w(TAG, "no file chooser available", t);
                tab.fileChooserCallback = null;
                pendingFileChooser = null;
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            // The app declares no camera/microphone permission: deny politely.
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
    };

    private static String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? url : host;
        } catch (Throwable t) {
            return url;
        }
    }
}
