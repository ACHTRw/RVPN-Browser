package com.rvpnbrowser.browser;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The browser window: address bar, tab strip, page container and the menu with
 * every browser command. It also drives the mode (normal / RVPN) from the UI
 * side: the engine is started in the {@code :rvpn} process and, once connected,
 * every WebView is pointed at the local proxy of the core.
 */
public class MainActivity extends AppCompatActivity
        implements TabManager.Observer, RvpnClient.Listener, ChallengeDialogs.Host {

    /** Open the downloads list (used by the download notifications). */
    public static final String EXTRA_SHOW_DOWNLOADS = "com.rvpnbrowser.browser.extra.SHOW_DOWNLOADS";
    /** Cancel one download by id (used by the progress notification). */
    public static final String EXTRA_CANCEL_DOWNLOAD =
            "com.rvpnbrowser.browser.extra.CANCEL_DOWNLOAD";
    /** Open this URL in a new tab. */
    public static final String EXTRA_OPEN_URL = "com.rvpnbrowser.browser.extra.OPEN_URL";

    private static final int REQUEST_NOTIFICATIONS = 2001;
    private static final long CONNECT_WATCHDOG_MS = 90_000L;

    private RvpnPrefs prefs;
    private RvpnClient client;
    private TabManager tabs;
    private SiteStore bookmarks;
    private SiteStore history;

    private EditText urlBar;
    private ImageView securityIcon;
    private ImageButton reloadButton;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private LinearLayout tabContainer;
    private HorizontalScrollView tabScroll;
    private TextView modeChip;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean proxyApplied;
    private boolean everConnected;
    private String appliedProxyKey = "";
    private final Runnable connectWatchdog = () -> {
        if (prefs.isRvpnMode() && !proxyApplied) {
            String reason = client.lastError().isEmpty()
                    ? getString(R.string.err_timeout, getString(R.string.mode_rvpn))
                    : client.lastError();
            fallbackToNormal(reason);
        }
    };

    // ------------------------------------------------------------- life cycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new RvpnPrefs(this);
        client = RvpnClient.get(this);
        bookmarks = new SiteStore(this, "bookmarks", 500);
        history = new SiteStore(this, "history", 2000);

        urlBar = findViewById(R.id.url_bar);
        securityIcon = findViewById(R.id.url_security);
        reloadButton = findViewById(R.id.btn_reload);
        backButton = findViewById(R.id.btn_back);
        forwardButton = findViewById(R.id.btn_forward);
        webContainer = findViewById(R.id.web_container);
        progressBar = findViewById(R.id.progress);
        tabContainer = findViewById(R.id.tab_container);
        tabScroll = findViewById(R.id.tab_scroll);
        modeChip = findViewById(R.id.mode_chip);

        tabs = new TabManager(this, webContainer, this, history);

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEND) {
                tabs.navigate(urlBar.getText().toString());
                urlBar.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });
        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                urlBar.selectAll();
            } else {
                updateAddressBar(tabs.current());
            }
        });

        reloadButton.setOnClickListener(v -> {
            BrowserTab tab = tabs.current();
            if (tab == null) {
                return;
            }
            if (tab.progress > 0 && tab.progress < 100) {
                tab.webView.stopLoading();
            } else {
                tab.webView.reload();
            }
        });
        backButton.setOnClickListener(v -> {
            BrowserTab tab = tabs.current();
            if (tab != null && tab.webView.canGoBack()) {
                tab.webView.goBack();
            }
        });
        forwardButton.setOnClickListener(v -> {
            BrowserTab tab = tabs.current();
            if (tab != null && tab.webView.canGoForward()) {
                tab.webView.goForward();
            }
        });
        findViewById(R.id.btn_home).setOnClickListener(v -> {
            BrowserTab tab = tabs.current();
            if (tab != null) {
                tabs.load(tab, TabManager.HOME_URL);
            }
        });
        findViewById(R.id.btn_new_tab).setOnClickListener(v -> tabs.newTab(null, true));
        findViewById(R.id.btn_tabs).setOnClickListener(v -> showTabList());
        findViewById(R.id.btn_menu).setOnClickListener(this::showPopupMenu);
        modeChip.setOnClickListener(v -> toggleMode());

        if (!tabs.restoreSession()) {
            tabs.newTab(null, true);
        }
        renderTabStrip();
        updateModeChip();
        LogStore.add(this, R.string.mode_restored, getString(prefs.isRvpnMode()
                ? R.string.mode_rvpn : R.string.mode_normal));

        requestNotificationPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.addListener(this);
        client.setForeground(true);
        client.start();
        updateModeChip();
        if (prefs.isRvpnMode() && prefs.autoConnect()) {
            // Mode and credentials are persistent: reconnect automatically. When
            // the saved settings are incomplete, say so instead of waiting for a
            // connection that can never happen.
            int problem = prefs.validate();
            if (problem != 0) {
                fallbackToNormal(getString(problem));
            } else {
                startEngine();
                maybeApplyProxy();
                if (!proxyApplied) {
                    handler.removeCallbacks(connectWatchdog);
                    handler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS);
                }
            }
        }
        updateHomePage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateModeChip();
        updateAddressBar(tabs.current());
        updateNavigationButtons();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Only the activity in front answers challenges, so the listener goes
        // away with the visibility (it is registered again in onStart).
        client.removeListener(this);
        ChallengeDialogs.dismissAll(this);
        client.setForeground(false);
        if (tabs != null) {
            tabs.saveSession();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(connectWatchdog);
        if (tabs != null) {
            tabs.destroyAll();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        BrowserTab tab = tabs.current();
        if (tab != null && tab.webView.canGoBack()) {
            tab.webView.goBack();
            return;
        }
        if (tabs.count() > 1) {
            tabs.closeCurrent();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (tabs.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ----------------------------------------------------------------- intents

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.hasExtra(EXTRA_CANCEL_DOWNLOAD)) {
            Downloads.cancel(this, intent.getLongExtra(EXTRA_CANCEL_DOWNLOAD, -1L));
            showDownloads();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false)) {
            showDownloads();
        }
        String url = intent.getStringExtra(EXTRA_OPEN_URL);
        if (url == null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            url = intent.getData().toString();
        }
        if (url != null && !url.isEmpty()) {
            tabs.newTab(url, true);
        }
    }

    // ------------------------------------------------------------- tab manager

    @Override
    public void onTabsChanged() {
        renderTabStrip();
        updateNavigationButtons();
    }

    @Override
    public void onTabUpdated(BrowserTab tab) {
        if (tab == tabs.current()) {
            updateAddressBar(tab);
        }
        updateTabView(tab);
        updateNavigationButtons();
    }

    @Override
    public void onProgressChanged(BrowserTab tab, int progress) {
        if (tab != tabs.current()) {
            return;
        }
        progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
        progressBar.setProgress(progress);
        boolean loading = progress > 0 && progress < 100;
        reloadButton.setImageResource(loading ? R.drawable.ic_stop : R.drawable.ic_reload);
        reloadButton.setContentDescription(getString(loading
                ? R.string.action_stop : R.string.action_reload));
        updateNavigationButtons();
    }

    @Override
    public void onPageStarted(BrowserTab tab) {
        if (tab != tabs.current()) {
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        reloadButton.setImageResource(R.drawable.ic_stop);
        reloadButton.setContentDescription(getString(R.string.action_stop));
        updateAddressBar(tab);
    }

    @Override
    public void onPageFinished(BrowserTab tab) {
        if (tab != tabs.current()) {
            return;
        }
        progressBar.setVisibility(View.GONE);
        reloadButton.setImageResource(R.drawable.ic_reload);
        reloadButton.setContentDescription(getString(R.string.action_reload));
        updateAddressBar(tab);
        updateNavigationButtons();
        updateHomePage();
    }

    @Override
    public void onPageError(BrowserTab tab, String message) {
        if (tab == tabs.current()) {
            Ui.toastLong(this, getString(R.string.page_load_error, message));
        }
    }

    @Override
    public void onRendererGone(BrowserTab tab) {
        Ui.toast(this, R.string.page_renderer_gone);
        renderTabStrip();
    }

    @Override
    public void onOpenRvpnSettings() {
        openSettings();
    }

    private void renderTabStrip() {
        tabContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        int minWidth = getResources().getDimensionPixelSize(R.dimen.tab_min_width);
        int maxWidth = getResources().getDimensionPixelSize(R.dimen.tab_max_width);
        int closeWidth = getResources().getDimensionPixelSize(R.dimen.tab_close_size)
                + getResources().getDimensionPixelSize(R.dimen.space_medium);
        View currentView = null;
        for (int i = 0; i < tabs.count(); i++) {
            final BrowserTab tab = tabs.get(i);
            View view = inflater.inflate(R.layout.item_tab, tabContainer, false);
            TextView title = view.findViewById(R.id.tab_title);
            String text = tab.displayTitle(getString(R.string.tab_title_new));
            title.setText(text);
            view.setSelected(i == tabs.currentIndex());
            float measured = title.getPaint().measureText(text) + closeWidth + 28;
            int width = (int) Math.max(minWidth, Math.min(maxWidth, measured));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    width, ViewGroup.LayoutParams.MATCH_PARENT);
            params.setMargins(getResources().getDimensionPixelSize(R.dimen.space_tiny), 0,
                    getResources().getDimensionPixelSize(R.dimen.space_tiny), 0);
            view.setLayoutParams(params);
            final int index = i;
            view.setOnClickListener(v -> tabs.selectTab(index));
            view.findViewById(R.id.tab_close).setOnClickListener(v -> tabs.closeTab(index));
            tabContainer.addView(view);
            if (i == tabs.currentIndex()) {
                currentView = view;
            }
        }
        if (currentView != null) {
            final View target = currentView;
            tabScroll.post(() -> tabScroll.smoothScrollTo(
                    Math.max(0, target.getLeft() - 100), 0));
        }
    }

    private void updateTabView(BrowserTab tab) {
        int index = tabs.tabs().indexOf(tab);
        if (index < 0 || index >= tabContainer.getChildCount()) {
            return;
        }
        View view = tabContainer.getChildAt(index);
        TextView title = view.findViewById(R.id.tab_title);
        title.setText(tab.displayTitle(getString(R.string.tab_title_new)));
    }

    private void updateAddressBar(BrowserTab tab) {
        if (tab == null) {
            return;
        }
        if (!urlBar.hasFocus()) {
            String url = tab.url == null ? "" : tab.url;
            if (TabManager.HOME_URL.equals(url) || TabManager.BLANK_URL.equals(url)) {
                urlBar.setText("");
            } else {
                urlBar.setText(url);
            }
        }
        updateSecurityIndicator(tab.url);
    }

    private void updateSecurityIndicator(String url) {
        int icon;
        int colorRes;
        int description;
        String value = url == null ? "" : url;
        if (value.startsWith("https://")) {
            icon = R.drawable.ic_lock;
            colorRes = R.color.secure;
            description = R.string.security_https;
        } else if (value.startsWith("http://")) {
            icon = R.drawable.ic_lock_open;
            colorRes = R.color.insecure;
            description = R.string.security_http;
        } else if (value.startsWith("file://") || value.startsWith("content://")) {
            icon = R.drawable.ic_globe;
            colorRes = R.color.text_secondary;
            description = R.string.security_local;
        } else {
            icon = R.drawable.ic_globe;
            colorRes = R.color.text_secondary;
            description = R.string.security_unknown;
        }
        securityIcon.setImageResource(icon);
        securityIcon.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, colorRes)));
        securityIcon.setContentDescription(getString(description));
    }

    private void updateNavigationButtons() {
        BrowserTab tab = tabs.current();
        backButton.setEnabled(tab != null && tab.webView.canGoBack());
        forwardButton.setEnabled(tab != null && tab.webView.canGoForward());
        backButton.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                backButton.isEnabled() ? R.color.icon_tint : R.color.icon_tint_disabled)));
        forwardButton.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                forwardButton.isEnabled() ? R.color.icon_tint : R.color.icon_tint_disabled)));
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    // -------------------------------------------------------------- mode logic

    private void toggleMode() {
        if (prefs.isRvpnMode()) {
            switchToNormalMode();
        } else {
            switchToRvpnMode();
        }
    }

    private void switchToRvpnMode() {
        int problem = prefs.validate();
        if (problem != 0) {
            Ui.toastLong(this, getString(R.string.mode_switch_failed, getString(problem)));
            openSettings();
            return;
        }
        if (!BrowserProxy.isSupported()) {
            Ui.toastLong(this, getString(R.string.proxy_unsupported));
            openSettings();
            return;
        }
        prefs.setMode(RvpnPrefs.MODE_RVPN);
        updateModeChip();
        Ui.toast(this, R.string.mode_applying);
        LogStore.add(this, R.string.log_mode_rvpn);
        handler.removeCallbacks(connectWatchdog);
        handler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS);
        startEngine();
        maybeApplyProxy();
    }

    private void switchToNormalMode() {
        prefs.setMode(RvpnPrefs.MODE_NORMAL);
        handler.removeCallbacks(connectWatchdog);
        LogStore.add(this, R.string.log_mode_normal);
        RvpnService.stop(this);
        BrowserProxy.clear(result -> runOnUiThread(() -> {
            proxyApplied = false;
            appliedProxyKey = "";
            updateModeChip();
            if (!result.ok) {
                Ui.toast(this, getString(R.string.proxy_clear_failed, result.error));
            } else {
                Ui.toast(this, R.string.proxy_cleared);
            }
            updateHomePage();
        }));
    }

    /** RVPN mode could not be established: back to normal mode, with a reason. */
    private void fallbackToNormal(String reason) {
        handler.removeCallbacks(connectWatchdog);
        prefs.setMode(RvpnPrefs.MODE_NORMAL);
        BrowserProxy.clear(result -> runOnUiThread(() -> {
            proxyApplied = false;
            appliedProxyKey = "";
            updateModeChip();
            updateHomePage();
        }));
        LogStore.add(this, R.string.mode_fallback, reason);
        Ui.toastLong(this, getString(R.string.mode_fallback, reason));
    }

    private void startEngine() {
        try {
            RvpnService.start(this, prefs.configJson());
        } catch (Throwable t) {
            Ui.toastLong(this, getString(R.string.mode_fallback, String.valueOf(t.getMessage())));
        }
    }

    /** Applies the proxy as soon as the core has published its listeners. */
    private void maybeApplyProxy() {
        if (!prefs.isRvpnMode() || proxyApplied) {
            return;
        }
        RvpnState state = client.lastState();
        if (state == null || !state.isConnected()) {
            return;
        }
        final String socks = state.socksAddr;
        final String http = state.httpAddr;
        if (socks.isEmpty() && http.isEmpty()) {
            return;
        }
        final String key = socks + '|' + http;
        if (key.equals(appliedProxyKey)) {
            return;
        }
        BrowserProxy.apply(socks, http, result -> runOnUiThread(() -> {
            if (result.ok) {
                proxyApplied = true;
                appliedProxyKey = key;
                handler.removeCallbacks(connectWatchdog);
                if (result.socksFallback) {
                    Ui.toastLong(this, getString(R.string.proxy_socks_fallback, result.endpoint));
                }
                Ui.toast(this, getString(R.string.mode_rvpn_applied, result.endpoint));
                LogStore.add(this, R.string.log_proxy_applied, result.endpoint);
                updateModeChip();
                updateHomePage();
            } else {
                LogStore.add(this, R.string.log_proxy_failed, result.error);
                fallbackToNormal(getString(R.string.proxy_apply_failed, result.error));
            }
        }));
    }

    private void updateModeChip() {
        boolean rvpn = prefs.isRvpnMode();
        modeChip.setText(rvpn ? R.string.mode_rvpn : R.string.mode_normal);
        modeChip.setSelected(rvpn);
        modeChip.setTextColor(ContextCompat.getColor(this,
                rvpn ? R.color.text_on_brand : R.color.text_primary));
        modeChip.setContentDescription(getString(
                rvpn ? R.string.mode_switch_to_normal : R.string.mode_switch_to_rvpn));
    }

    // ------------------------------------------------------------ core events

    @Override
    public void onCoreConnection(boolean connected, String error) {
        updateModeChip();
        if (connected) {
            if (everConnected) {
                LogStore.add(this, R.string.log_core_reconnected);
            }
            everConnected = true;
        } else if (!error.isEmpty()) {
            LogStore.add(this, R.string.log_core_disconnected, error);
        }
        if (prefs.isRvpnMode()) {
            if (connected) {
                maybeApplyProxy();
            } else if (!error.isEmpty()) {
                updateHomePage();
            }
        }
    }

    @Override
    public void onCoreHello() {
        updateHomePage();
    }

    @Override
    public void onCoreState(RvpnState state) {
        updateModeChip();
        updateHomePage();
        if (prefs.isRvpnMode() && state.isConnected()) {
            maybeApplyProxy();
        }
        if (prefs.isRvpnMode() && state.isError() && !proxyApplied) {
            // The core gave up: do not keep the user waiting for the watchdog.
            fallbackToNormal(state.lastError.isEmpty() ? state.message : state.lastError);
        }
    }

    @Override
    public void onCoreEvent(RvpnEvent event) {
        // The settings screen owns the log; here only hard errors are surfaced.
        if (event.isError() && prefs.isRvpnMode() && !proxyApplied) {
            Ui.toastLong(this, getString(R.string.mode_fallback, event.message));
        }
    }

    @Override
    public void onCoreChallenge(RvpnChallenge challenge) {
        ChallengeDialogs.show(this, challenge, client, this);
    }

    @Override
    public void onCoreChallengeDone(String challengeId) {
        ChallengeDialogs.dismiss(this, challengeId);
    }

    @Override
    public void openUrlInBrowser(String url) {
        tabs.newTab(url, true);
    }

    private void updateHomePage() {
        BrowserTab tab = tabs.current();
        if (tab == null || !TabManager.HOME_URL.equals(tab.url)) {
            return;
        }
        JSONObject payload = new JSONObject();
        RvpnState state = client.lastState();
        try {
            payload.put("mode", prefs.mode());
            payload.put("modeLabel", getString(prefs.isRvpnMode()
                    ? R.string.mode_rvpn : R.string.mode_normal));
            payload.put("phase", state == null ? RvpnState.PHASE_IDLE : state.phase);
            payload.put("phaseLabel", getString(phaseLabel(state == null ? "" : state.phase)));
            payload.put("message", state == null ? "" : state.message);
            payload.put("server", state == null ? "" : state.server);
            payload.put("clientIP", state == null ? "" : state.clientIP);
            payload.put("socksAddr", state == null ? "" : state.socksAddr);
            payload.put("httpAddr", state == null ? "" : state.httpAddr);
            payload.put("connected", state != null && state.isConnected());
            payload.put("proxyApplied", proxyApplied);
            payload.put("error", client.lastError());
            payload.put("uptime", state != null && state.connectedAt > 0
                    ? Math.max(0, (System.currentTimeMillis() - state.connectedAt) / 1000L) : 0);
            JSONObject hello = client.hello();
            payload.put("coreVersion", Json.str(hello, "coreVersion", ""));
        } catch (Exception ignored) {
            // put(String) only fails for NaN/Infinity, which cannot happen here.
        }
        tab.webView.evaluateJavascript("window.rvpnUpdate && window.rvpnUpdate(" + payload + ");",
                null);
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

    // -------------------------------------------------------------- menu/lists

    private void showPopupMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.inflate(R.menu.main_menu);
        MenuItem bookmarkItem = popup.getMenu().findItem(R.id.menu_bookmark);
        if (bookmarkItem != null) {
            bookmarkItem.setTitle(bookmarks.contains(currentUrl())
                    ? R.string.action_remove_bookmark : R.string.action_add_bookmark);
        }
        MenuItem modeItem = popup.getMenu().findItem(R.id.menu_switch_mode);
        if (modeItem != null) {
            modeItem.setTitle(prefs.isRvpnMode()
                    ? R.string.mode_switch_to_normal : R.string.mode_switch_to_rvpn);
        }
        popup.setOnMenuItemClickListener(item -> handleMenu(item.getItemId()));
        popup.show();
    }

    private boolean handleMenu(int itemId) {
        if (itemId == R.id.menu_new_tab) {
            tabs.newTab(null, true);
        } else if (itemId == R.id.menu_tab_list) {
            showTabList();
        } else if (itemId == R.id.menu_bookmark) {
            toggleBookmark();
        } else if (itemId == R.id.menu_bookmarks) {
            showBookmarks();
        } else if (itemId == R.id.menu_history) {
            showHistory();
        } else if (itemId == R.id.menu_downloads) {
            showDownloads();
        } else if (itemId == R.id.menu_find) {
            showFindDialog();
        } else if (itemId == R.id.menu_switch_mode) {
            toggleMode();
        } else if (itemId == R.id.menu_rvpn_settings) {
            openSettings();
        } else if (itemId == R.id.menu_share) {
            shareCurrent();
        } else if (itemId == R.id.menu_copy_link) {
            copyCurrentLink();
        } else {
            return false;
        }
        return true;
    }

    private String currentUrl() {
        BrowserTab tab = tabs.current();
        return tab == null || tab.url == null ? "" : tab.url;
    }

    private void openSettings() {
        startActivity(new Intent(this, RvpnSettingsActivity.class));
    }

    private void shareCurrent() {
        String url = currentUrl();
        if (url.isEmpty()) {
            Ui.toast(this, R.string.no_link_to_copy);
            return;
        }
        Ui.share(this, url);
    }

    private void copyCurrentLink() {
        String url = currentUrl();
        if (url.isEmpty()) {
            Ui.toast(this, R.string.no_link_to_copy);
            return;
        }
        if (Ui.copyToClipboard(this, getString(R.string.action_copy_link), url)) {
            Ui.toast(this, R.string.link_copied);
        } else {
            Ui.toast(this, getString(R.string.copy_failed, ""));
        }
    }

    private void toggleBookmark() {
        String url = currentUrl();
        if (url.isEmpty() || TabManager.HOME_URL.equals(url)) {
            Ui.toast(this, R.string.no_link_to_copy);
            return;
        }
        BrowserTab tab = tabs.current();
        String title = tab == null ? url : tab.displayTitle(url);
        if (bookmarks.contains(url)) {
            bookmarks.remove(url);
            Ui.toast(this, R.string.bookmark_removed);
        } else {
            bookmarks.add(title, url);
            Ui.toast(this, R.string.bookmark_added);
        }
    }

    private void showTabList() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < tabs.count(); i++) {
            BrowserTab tab = tabs.get(i);
            Row row = new Row();
            row.title = tab.displayTitle(getString(R.string.tab_title_new));
            row.subtitle = tab.url == null ? "" : tab.url;
            row.actionIcon = R.drawable.ic_close;
            row.actionVisible = tabs.count() > 1;
            rows.add(row);
        }
        showListDialog(R.string.dialog_tabs_title, R.string.tab_list_empty, rows, position -> {
            tabs.selectTab(position);
        }, position -> tabs.closeTab(position));
    }

    private void showBookmarks() {
        List<SiteStore.Entry> entries = bookmarks.list();
        List<Row> rows = new ArrayList<>();
        for (SiteStore.Entry entry : entries) {
            Row row = new Row();
            row.title = entry.title.isEmpty() ? entry.url : entry.title;
            row.subtitle = entry.url;
            row.actionIcon = R.drawable.ic_delete;
            row.actionVisible = true;
            rows.add(row);
        }
        showListDialog(R.string.dialog_bookmarks_title, R.string.bookmark_list_empty, rows,
                position -> tabs.newTab(entries.get(position).url, true),
                position -> {
                    bookmarks.remove(entries.get(position).url);
                    showBookmarks();
                });
    }

    private void showHistory() {
        final List<SiteStore.Entry> entries = history.list();
        List<Row> rows = new ArrayList<>();
        for (SiteStore.Entry entry : entries) {
            Row row = new Row();
            row.title = entry.title.isEmpty() ? entry.url : entry.title;
            row.subtitle = entry.url;
            row.actionIcon = R.drawable.ic_delete;
            row.actionVisible = true;
            rows.add(row);
        }
        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = buildListDialog(R.string.dialog_history_title,
                R.string.history_list_empty, rows,
                position -> tabs.newTab(entries.get(position).url, true),
                position -> {
                    history.remove(entries.get(position).url);
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    showHistory();
                });
        holder[0] = dialog;
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.action_clear), (d, w) -> {
            history.clear();
            Ui.toast(this, R.string.history_cleared);
        });
        dialog.show();
    }

    private void showDownloads() {
        final List<Downloads.Record> records = Downloads.list(this);
        List<Row> rows = new ArrayList<>();
        for (Downloads.Record record : records) {
            Row row = new Row();
            row.title = record.fileName.isEmpty()
                    ? getString(R.string.download_untitled) : record.fileName;
            String state;
            if (record.isActive()) {
                state = record.total > 0
                        ? getString(R.string.download_progress, Ui.formatBytes(record.done)
                                + " / " + Ui.formatBytes(record.total))
                        : getString(R.string.download_unknown_size);
            } else if (record.isDone()) {
                state = getString(R.string.download_complete,
                        record.path.isEmpty() ? record.fileName : record.path);
            } else {
                state = record.error;
            }
            row.subtitle = state;
            row.actionIcon = R.drawable.ic_delete;
            row.actionVisible = true;
            rows.add(row);
        }
        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = buildListDialog(R.string.dialog_downloads_title,
                R.string.download_list_empty, rows,
                position -> {
                    Downloads.Record record = records.get(position);
                    if (record.isActive()) {
                        Downloads.cancel(this, record.id);
                    } else if (record.isDone()) {
                        Downloads.open(this, record);
                    }
                },
                position -> {
                    Downloads.remove(this, records.get(position).id);
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    showDownloads();
                });
        holder[0] = dialog;
        dialog.show();
    }

    private void showFindDialog() {
        final BrowserTab tab = tabs.current();
        if (tab == null) {
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.action_find_in_page);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                String query = s.toString();
                if (query.isEmpty()) {
                    tab.webView.clearMatches();
                } else {
                    tab.webView.findAllAsync(query);
                }
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.action_find_in_page)
                .setView(input)
                .setPositiveButton(R.string.action_close, (d, w) -> tab.webView.clearMatches())
                .setNeutralButton(R.string.find_next, (d, w) -> {
                    if (input.getText().length() > 0) {
                        tab.webView.findNext(true);
                    }
                })
                .create();
        dialog.setOnDismissListener(d -> tab.webView.clearMatches());
        dialog.show();
    }

    private void requestNotificationPermission() {
        if (!Notifications.needsRuntimePermission()) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Ui.toastLong(this, getString(R.string.permission_notifications_denied));
        }
    }

    // ------------------------------------------------------------ list dialogs

    /** One row of a list dialog. */
    private static final class Row {
        String title = "";
        String subtitle = "";
        int actionIcon;
        boolean actionVisible;
    }

    private interface RowClick {
        void onClick(int position);
    }

    private AlertDialog buildListDialog(int titleRes, int emptyRes, List<Row> rows,
                                        RowClick onRow, RowClick onAction) {
        if (rows.isEmpty()) {
            return new AlertDialog.Builder(this)
                    .setTitle(titleRes)
                    .setMessage(emptyRes)
                    .setPositiveButton(R.string.action_close, null)
                    .create();
        }
        RowAdapter adapter = new RowAdapter(rows, onAction);
        return new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setAdapter(adapter, (dialog, which) -> onRow.onClick(which))
                .setPositiveButton(R.string.action_close, null)
                .create();
    }

    private void showListDialog(int titleRes, int emptyRes, List<Row> rows, RowClick onRow,
                                RowClick onAction) {
        buildListDialog(titleRes, emptyRes, rows, onRow, onAction).show();
    }

    private final class RowAdapter extends BaseAdapter {
        private final List<Row> rows;
        private final RowClick onAction;

        RowAdapter(List<Row> rows, RowClick onAction) {
            this.rows = rows;
            this.onAction = onAction;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_two_line, parent, false);
            }
            Row row = rows.get(position);
            ((TextView) view.findViewById(R.id.row_title)).setText(row.title);
            TextView subtitle = view.findViewById(R.id.row_subtitle);
            subtitle.setText(row.subtitle);
            subtitle.setVisibility(row.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            ImageButton action = view.findViewById(R.id.row_action);
            if (row.actionVisible) {
                action.setVisibility(View.VISIBLE);
                action.setImageResource(row.actionIcon);
                action.setOnClickListener(v -> onAction.onClick(position));
            } else {
                action.setVisibility(View.GONE);
                action.setOnClickListener(null);
            }
            return view;
        }
    }
}
