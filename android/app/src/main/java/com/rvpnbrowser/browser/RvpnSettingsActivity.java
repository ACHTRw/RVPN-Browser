package com.rvpnbrowser.browser;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RVPN login and connection settings - the Android counterpart of
 * {@code browser/extension/options.html}: mode switch, account, advanced
 * options, core diagnostics, the event log and the challenge dialogs.
 */
public class RvpnSettingsActivity extends AppCompatActivity
        implements RvpnClient.Listener, ChallengeDialogs.Host {

    /** Opens the screen for one specific challenge (notification action). */
    public static final String EXTRA_CHALLENGE_ID = "com.rvpnbrowser.browser.extra.CHALLENGE_ID";

    private static final String TAG = "RvpnSettings";

    private RvpnPrefs prefs;
    private RvpnClient client;

    private TextView modeBadge;
    private TextView phaseBadge;
    private TextView alertBox;
    private TextView statusLine;
    private TextView modeHint;
    private TextView saveResult;
    private TextView selftestResult;
    private TextView probeStatus;
    private TextView hintProtocol;
    private TextView hintLoginDomain;
    private TextView hintPhone;
    private TextView passwordHint;
    private TextView logView;

    private Button modeNormalButton;
    private Button modeRvpnButton;
    private Button connectButton;
    private Button disconnectButton;

    private Spinner userTypeSpinner;
    private Spinner protocolSpinner;
    private Spinner authTypeSpinner;

    private EditText serverField;
    private EditText portField;
    private EditText usernameField;
    private EditText passwordField;
    private EditText loginDomainField;
    private EditText phoneField;
    private EditText totpField;
    private EditText socksPortField;
    private EditText httpPortField;

    private CheckBox savePasswordBox;
    private CheckBox debugBox;
    private CheckBox proxyAllBox;

    private View fieldPort;
    private View fieldLoginDomain;
    private View fieldAuthType;
    private View fieldPhone;
    private LinearLayout probeResults;
    private LinearLayout coreGrid;
    private ScrollView logScroll;

    private final Map<Integer, TextView> coreValues = new LinkedHashMap<>();
    private final List<String> userTypeValues = new ArrayList<>();
    private final List<String> protocolValues = new ArrayList<>();
    private final List<String> authTypeValues = new ArrayList<>();

    private boolean loading;
    private boolean loggedInstalled;

    // ------------------------------------------------------------- life cycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rvpn_settings);
        prefs = new RvpnPrefs(this);
        client = RvpnClient.get(this);

        bindViews();
        setupSpinners();
        setupCoreGrid();
        loadSettings();
        syncVisibility();
        wireActions();
        renderLog();
        updateModeUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.addListener(this);
        client.setForeground(true);
        client.start();
        updateModeUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        client.removeListener(this);
        ChallengeDialogs.dismissAll(this);
        client.setForeground(false);
    }

    private void bindViews() {
        modeBadge = findViewById(R.id.mode_badge);
        phaseBadge = findViewById(R.id.phase_badge);
        alertBox = findViewById(R.id.alert_box);
        statusLine = findViewById(R.id.status_line);
        modeHint = findViewById(R.id.mode_hint);
        saveResult = findViewById(R.id.save_result);
        selftestResult = findViewById(R.id.selftest_result);
        probeStatus = findViewById(R.id.probe_status);
        hintProtocol = findViewById(R.id.hint_protocol);
        hintLoginDomain = findViewById(R.id.hint_login_domain);
        hintPhone = findViewById(R.id.hint_phone);
        passwordHint = findViewById(R.id.password_hint);
        logView = findViewById(R.id.log_view);
        logScroll = findViewById(R.id.log_scroll);

        modeNormalButton = findViewById(R.id.mode_normal_btn);
        modeRvpnButton = findViewById(R.id.mode_rvpn_btn);
        connectButton = findViewById(R.id.btn_connect);
        disconnectButton = findViewById(R.id.btn_disconnect);

        userTypeSpinner = findViewById(R.id.user_type);
        protocolSpinner = findViewById(R.id.protocol);
        authTypeSpinner = findViewById(R.id.auth_type);

        serverField = findViewById(R.id.server);
        portField = findViewById(R.id.port);
        usernameField = findViewById(R.id.username);
        passwordField = findViewById(R.id.password);
        loginDomainField = findViewById(R.id.login_domain);
        phoneField = findViewById(R.id.phone);
        totpField = findViewById(R.id.totp);
        socksPortField = findViewById(R.id.socks_port);
        httpPortField = findViewById(R.id.http_port);

        savePasswordBox = findViewById(R.id.save_password);
        debugBox = findViewById(R.id.debug);
        proxyAllBox = findViewById(R.id.proxy_all);

        fieldPort = findViewById(R.id.field_port);
        fieldLoginDomain = findViewById(R.id.field_login_domain);
        fieldAuthType = findViewById(R.id.field_auth_type);
        fieldPhone = findViewById(R.id.field_phone);
        probeResults = findViewById(R.id.probe_results);
        coreGrid = findViewById(R.id.core_grid);
    }

    private void setupSpinners() {
        fillValues(userTypeValues, R.array.user_type_values);
        fillValues(protocolValues, R.array.protocol_values);
        fillValues(authTypeValues, R.array.auth_type_values);
        userTypeSpinner.setAdapter(labels(R.array.user_type_labels));
        protocolSpinner.setAdapter(labels(R.array.protocol_labels));
        authTypeSpinner.setAdapter(labels(R.array.auth_type_labels));
    }

    private void fillValues(List<String> target, int arrayRes) {
        for (String value : getResources().getStringArray(arrayRes)) {
            target.add(value);
        }
    }

    private ArrayAdapter<CharSequence> labels(int arrayRes) {
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(this, arrayRes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    /** The diagnostics grid: one row per value, filled by {@link #updateCoreGrid()}. */
    private void setupCoreGrid() {
        int[] keys = {
                R.string.label_phase,
                R.string.label_state_message,
                R.string.label_server_addr,
                R.string.label_client_ip,
                R.string.label_socks,
                R.string.label_http,
                R.string.label_proxy_state,
                R.string.label_connected_at,
                R.string.label_core_version,
                R.string.label_zjuconnect_version,
                R.string.label_platform,
                R.string.label_capabilities,
                R.string.label_core_error,
        };
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int key : keys) {
            View row = inflater.inflate(R.layout.item_core_row, coreGrid, false);
            ((TextView) row.findViewById(R.id.core_key)).setText(key);
            TextView value = row.findViewById(R.id.core_value);
            coreValues.put(key, value);
            coreGrid.addView(row);
        }
    }

    // ------------------------------------------------------------ settings i/o

    private void loadSettings() {
        loading = true;
        userTypeSpinner.setSelection(indexOf(userTypeValues, prefs.userType()));
        protocolSpinner.setSelection(indexOf(protocolValues, prefs.protocol()));
        authTypeSpinner.setSelection(indexOf(authTypeValues, prefs.authType()));
        serverField.setText(prefs.server());
        portField.setText(String.valueOf(prefs.port()));
        usernameField.setText(prefs.username());
        passwordField.setText(prefs.password());
        savePasswordBox.setChecked(prefs.rememberPassword());
        loginDomainField.setText(prefs.loginDomain());
        phoneField.setText(prefs.phone());
        totpField.setText(prefs.totpSecret());
        debugBox.setChecked(prefs.debug());
        proxyAllBox.setChecked(prefs.proxyAll());
        socksPortField.setText(prefs.socksPort() == 0 ? "" : String.valueOf(prefs.socksPort()));
        httpPortField.setText(prefs.httpPort() == 0 ? "" : String.valueOf(prefs.httpPort()));
        loading = false;
        updatePasswordHint();
    }

    private static int indexOf(List<String> values, String value) {
        int index = values.indexOf(value);
        return index < 0 ? 0 : index;
    }

    /** Writes the form into the settings. Returns the validation error or 0. */
    private int collectSettings() {
        prefs.setUserType(selected(userTypeSpinner, userTypeValues, RvpnPrefs.USER_TYPE_ZJU));
        prefs.setProtocol(selected(protocolSpinner, protocolValues, RvpnPrefs.PROTOCOL_ATRUST));
        prefs.setAuthType(selected(authTypeSpinner, authTypeValues, ""));
        prefs.setServer(serverField.getText().toString());
        prefs.setPort(parsePort(portField, 443));
        prefs.setUsername(usernameField.getText().toString());
        prefs.setRememberPassword(savePasswordBox.isChecked());
        prefs.setPassword(passwordField.getText().toString());
        prefs.setLoginDomain(loginDomainField.getText().toString());
        prefs.setPhone(phoneField.getText().toString());
        prefs.setTotpSecret(totpField.getText().toString());
        prefs.setDebug(debugBox.isChecked());
        prefs.setProxyAll(proxyAllBox.isChecked());
        prefs.setSocksPort(parsePort(socksPortField, 0));
        prefs.setHttpPort(parsePort(httpPortField, 0));
        return prefs.validate();
    }

    private static String selected(Spinner spinner, List<String> values, String fallback) {
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= values.size()) {
            return fallback;
        }
        return values.get(position);
    }

    private static int parsePort(EditText field, int fallback) {
        String text = field.getText().toString().trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------- form logic

    private void wireActions() {
        AdapterView.OnItemSelectedListener spinnerListener =
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position,
                                               long id) {
                        syncVisibility();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        syncVisibility();
                    }
                };
        userTypeSpinner.setOnItemSelectedListener(spinnerListener);
        protocolSpinner.setOnItemSelectedListener(spinnerListener);
        authTypeSpinner.setOnItemSelectedListener(spinnerListener);

        savePasswordBox.setOnCheckedChangeListener((v, checked) -> updatePasswordHint());

        modeNormalButton.setOnClickListener(v -> switchMode(RvpnPrefs.MODE_NORMAL));
        modeRvpnButton.setOnClickListener(v -> switchMode(RvpnPrefs.MODE_RVPN));

        connectButton.setOnClickListener(v -> {
            int problem = collectSettings();
            if (problem != 0) {
                Ui.toast(this, problem);
                return;
            }
            prefs.setMode(RvpnPrefs.MODE_RVPN);
            startEngine();
            appendLog(getString(R.string.engine_starting));
            updateModeUi();
        });
        disconnectButton.setOnClickListener(v -> {
            RvpnService.stop(this);
            appendLog(getString(R.string.engine_stopping));
            updateModeUi();
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> save(false));
        findViewById(R.id.btn_save_connect).setOnClickListener(v -> save(true));
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            prefs.resetToDefaults();
            loadSettings();
            syncVisibility();
            saveResult.setText(R.string.reset_done);
        });
        findViewById(R.id.btn_probe).setOnClickListener(v -> probeAuthInfo());
        findViewById(R.id.btn_selftest).setOnClickListener(v -> selfTest());
        findViewById(R.id.btn_refresh_core).setOnClickListener(v -> {
            updateCoreGrid();
            updateStatusLine();
        });
        findViewById(R.id.btn_reconnect).setOnClickListener(v -> reconnect());
        findViewById(R.id.btn_copy_diag).setOnClickListener(v -> copyDiagnostics());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            LogStore.clear();
            renderLog();
        });
    }

    /** Mirrors the visibility rules of the desktop extension. */
    private void syncVisibility() {
        boolean other = RvpnPrefs.USER_TYPE_OTHER.equals(
                selected(userTypeSpinner, userTypeValues, RvpnPrefs.USER_TYPE_ZJU));
        boolean easyconnect = RvpnPrefs.PROTOCOL_EASYCONNECT.equals(
                selected(protocolSpinner, protocolValues, RvpnPrefs.PROTOCOL_ATRUST));
        String authType = selected(authTypeSpinner, authTypeValues, "");

        show(fieldPort, other);
        show(fieldLoginDomain, other);
        show(fieldAuthType, other);
        if (!other) {
            probeResults.setVisibility(View.GONE);
        }
        boolean sms = RvpnPrefs.AUTH_SMS.equals(authType);
        show(fieldPhone, other || sms);

        hintProtocol.setText(easyconnect
                ? (other ? R.string.hint_protocol_easyconnect : R.string.hint_protocol_easyconnect_zju)
                : (other ? R.string.hint_protocol_atrust : R.string.hint_protocol_atrust_zju));
        hintLoginDomain.setText(other
                ? R.string.hint_login_domain_other : R.string.hint_login_domain_zju);
        hintPhone.setText(other ? R.string.hint_phone : R.string.hint_phone_zju);
        modeHint.setText(prefs.isRvpnMode() ? R.string.hint_mode_rvpn : R.string.hint_mode_normal);
        updatePasswordHint();
    }

    private static void show(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updatePasswordHint() {
        passwordHint.setText(savePasswordBox.isChecked()
                ? R.string.warn_password_plain : R.string.hint_password_not_saved);
    }

    // ------------------------------------------------------------ save / mode

    private void save(boolean connect) {
        int problem = collectSettings();
        if (problem != 0) {
            Ui.toast(this, problem);
            saveResult.setText(getString(R.string.save_failed, getString(problem)));
            return;
        }
        saveResult.setText(R.string.saved_ok);
        appendLog(getString(R.string.log_settings_saved));
        if (connect) {
            prefs.setMode(RvpnPrefs.MODE_RVPN);
            startEngine();
            appendLog(getString(R.string.engine_starting));
        } else if (prefs.isRvpnMode() && client.isConnected()) {
            // Settings changed while connected: reconnect with the new values.
            appendLog(getString(R.string.log_settings_restart));
            startEngine();
        }
        updateModeUi();
    }

    private void switchMode(String mode) {
        if (RvpnPrefs.MODE_NORMAL.equals(mode)) {
            prefs.setMode(RvpnPrefs.MODE_NORMAL);
            RvpnService.stop(this);
            BrowserProxy.clear(result -> runOnUiThread(() -> {
                appendLog(getString(R.string.log_mode_normal));
                if (!result.ok) {
                    Ui.toast(this, getString(R.string.proxy_clear_failed, result.error));
                } else {
                    Ui.toast(this, R.string.proxy_cleared);
                }
                updateModeUi();
            }));
            return;
        }
        int problem = collectSettings();
        if (problem != 0) {
            Ui.toast(this, problem);
            return;
        }
        if (!BrowserProxy.isSupported()) {
            Ui.toastLong(this, getString(R.string.proxy_unsupported));
            return;
        }
        prefs.setMode(RvpnPrefs.MODE_RVPN);
        appendLog(getString(R.string.log_mode_rvpn));
        startEngine();
        updateModeUi();
    }

    private void startEngine() {
        try {
            RvpnService.start(this, prefs.configJson());
        } catch (Throwable t) {
            Ui.toastLong(this, getString(R.string.mode_fallback, String.valueOf(t.getMessage())));
        }
    }

    private void reconnect() {
        final int problem = collectSettings();
        if (problem != 0) {
            Ui.toast(this, problem);
            return;
        }
        client.call("stop", null, RvpnClient.DEFAULT_TIMEOUT_MS, (data, error) -> {
            if (error != null) {
                Log.w(TAG, "stop before reconnect failed: " + error.getMessage());
                appendLog(getString(R.string.log_stop_failed, error.getMessage()));
            } else {
                appendLog(getString(R.string.engine_stopped));
            }
            startEngine();
            client.call("start", startParams(), RvpnClient.START_TIMEOUT_MS, (data2, error2) -> {
                if (error2 != null) {
                    Ui.toast(this, getString(R.string.reconnect_failed, error2.getMessage()));
                    appendLog(getString(R.string.log_start_failed, error2.getMessage()));
                } else {
                    Ui.toast(this, R.string.reconnect_ok);
                    appendLog(getString(R.string.engine_started));
                }
            });
        });
    }

    private JSONObject startParams() {
        JSONObject params = new JSONObject();
        try {
            params.put("config", prefs.configJson());
        } catch (Exception ignored) {
            // put(String) cannot fail.
        }
        return params;
    }

    // --------------------------------------------------------------- diagnostics

    private void selfTest() {
        collectSettings();
        selftestResult.setText(R.string.selftest_running);
        JSONObject params = new JSONObject();
        try {
            params.put("server", prefs.server());
            params.put("port", prefs.port());
        } catch (Exception ignored) {
            // put(String) cannot fail.
        }
        client.call("selftest", params, RvpnClient.DEFAULT_TIMEOUT_MS, (data, error) -> {
            if (error != null) {
                selftestResult.setText(getString(R.string.selftest_failed, error.getMessage()));
                return;
            }
            boolean reachable = Json.bool(data, "reachable", false);
            String server = Json.str(data, "server");
            long latency = Json.lng(data, "latencyMs", 0);
            if (reachable) {
                selftestResult.setText(getString(R.string.selftest_ok, server, latency));
            } else {
                selftestResult.setText(getString(R.string.selftest_unreachable, server,
                        Json.str(data, "error")));
            }
            appendLog(selftestResult.getText().toString());
        });
    }

    private void probeAuthInfo() {
        collectSettings();
        probeStatus.setText(R.string.probe_running);
        probeResults.setVisibility(View.GONE);
        probeResults.removeAllViews();
        JSONObject params = new JSONObject();
        try {
            params.put("server", prefs.server());
            params.put("port", prefs.port());
        } catch (Exception ignored) {
            // put(String) cannot fail.
        }
        client.call("authInfo", params, RvpnClient.DEFAULT_TIMEOUT_MS, (data, error) -> {
            if (error != null) {
                probeStatus.setText(getString(R.string.probe_failed, error.getMessage()));
                return;
            }
            JSONArray list = Json.arr(data, "list");
            List<RvpnAuthInfo> items = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    RvpnAuthInfo item = RvpnAuthInfo.from(list.optJSONObject(i));
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            if (items.isEmpty()) {
                probeStatus.setText(R.string.probe_empty);
                return;
            }
            probeStatus.setText(R.string.probe_result_hint);
            LayoutInflater inflater = LayoutInflater.from(this);
            for (RvpnAuthInfo item : items) {
                View row = inflater.inflate(R.layout.item_two_line, probeResults, false);
                ((TextView) row.findViewById(R.id.row_title)).setText(item.label());
                TextView subtitle = row.findViewById(R.id.row_subtitle);
                subtitle.setText(item.loginDomain + " · " + item.authType);
                row.findViewById(R.id.row_action).setVisibility(View.GONE);
                row.setOnClickListener(v -> {
                    loginDomainField.setText(item.loginDomain);
                    int index = authTypeValues.indexOf(item.authType);
                    if (index >= 0) {
                        authTypeSpinner.setSelection(index);
                    }
                    Ui.toast(this, getString(R.string.probe_applied, item.label()));
                    syncVisibility();
                });
                probeResults.addView(row);
            }
            probeResults.setVisibility(View.VISIBLE);
        });
    }

    private void copyDiagnostics() {
        StringBuilder sb = new StringBuilder();
        JSONObject hello = client.hello();
        sb.append("RVPN Browser diagnostics\n");
        sb.append("core hello: ").append(hello == null ? "(unavailable)" : hello.toString())
                .append('\n');
        RvpnState state = client.lastState();
        sb.append("state: ").append(state == null ? "(none)" : state.toString()).append('\n');
        sb.append("connected: ").append(client.isConnected()).append('\n');
        sb.append("mode: ").append(prefs.mode()).append('\n');
        sb.append("engine: ").append(client.engineError().isEmpty()
                ? client.controlFilePath() : client.engineError()).append('\n');
        sb.append("browser proxy: ").append(proxySummary()).append('\n');
        sb.append("\n-- last events --\n");
        List<String> lines = LogStore.lines();
        for (int i = Math.max(0, lines.size() - 100); i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        if (Ui.copyToClipboard(this, "RVPN diagnostics", sb.toString())) {
            Ui.toast(this, R.string.diag_copied);
        } else {
            Ui.toast(this, getString(R.string.diag_copy_failed, ""));
        }
    }

    private String proxySummary() {
        RvpnState state = client.lastState();
        if (!prefs.isRvpnMode()) {
            return getString(R.string.proxy_none);
        }
        if (state == null || (state.socksAddr.isEmpty() && state.httpAddr.isEmpty())) {
            return getString(R.string.proxy_none);
        }
        return state.socksAddr.isEmpty() ? state.httpAddr : state.socksAddr;
    }

    // ------------------------------------------------------------------- core

    @Override
    public void onCoreConnection(boolean connected, String error) {
        updateModeUi();
        if (connected) {
            if (!loggedInstalled) {
                loggedInstalled = true;
                appendLog(getString(R.string.log_installed));
            } else {
                appendLog(getString(R.string.log_core_reconnected));
            }
            return;
        }
        if (!error.isEmpty()) {
            alertBox.setVisibility(View.VISIBLE);
            alertBox.setText(error);
            appendLog(getString(R.string.log_core_disconnected, error));
        }
    }

    @Override
    public void onCoreHello() {
        updateCoreGrid();
    }

    @Override
    public void onCoreState(RvpnState state) {
        updateStatusLine();
        updateCoreGrid();
        updateModeUi();
    }

    @Override
    public void onCoreEvent(RvpnEvent event) {
        if (RvpnEvent.KIND_CHALLENGE.equals(event.kind)) {
            return;
        }
        LogStore.addCore(event.ts, event.level, event.message);
        renderLog();
    }

    @Override
    public void onCoreChallenge(RvpnChallenge challenge) {
        appendLog(getString(R.string.log_challenge_received,
                getString(challenge.typeLabelRes())));
        ChallengeDialogs.show(this, challenge, client, this);
    }

    @Override
    public void onCoreChallengeDone(String challengeId) {
        ChallengeDialogs.dismiss(this, challengeId);
    }

    @Override
    public void openUrlInBrowser(String url) {
        // The browser owns the tabs; hand the login page over to it.
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ------------------------------------------------------------------- ui

    private void updateModeUi() {
        boolean rvpn = prefs.isRvpnMode();
        modeNormalButton.setSelected(!rvpn);
        modeRvpnButton.setSelected(rvpn);
        modeBadge.setText(rvpn ? R.string.mode_rvpn : R.string.mode_normal);
        modeHint.setText(rvpn ? R.string.hint_mode_rvpn : R.string.hint_mode_normal);

        RvpnState state = client.lastState();
        String phase = state == null ? RvpnState.PHASE_IDLE : state.phase;
        phaseBadge.setText(getString(phaseLabel(phase)));
        connectButton.setEnabled(!client.isConnected());
        disconnectButton.setEnabled(client.isConnected() || client.isEngineAvailable());

        String error = client.engineError();
        if (error.isEmpty() && state != null && state.isError()) {
            error = state.lastError;
        }
        if (!error.isEmpty() && !client.isConnected()) {
            alertBox.setVisibility(View.VISIBLE);
            alertBox.setText(getString(R.string.engine_error, error));
        } else {
            alertBox.setVisibility(View.GONE);
        }
        updateStatusLine();
        updateCoreGrid();
    }

    private void updateStatusLine() {
        RvpnState state = client.lastState();
        StringBuilder sb = new StringBuilder();
        if (state == null || (!client.isConnected() && !client.isEngineAvailable())) {
            sb.append(getString(R.string.core_unavailable));
            if (!client.lastError().isEmpty()) {
                sb.append(" · ").append(client.lastError());
            }
        } else {
            sb.append(getString(phaseLabel(state.phase)));
            if (!state.message.isEmpty()) {
                sb.append(" · ").append(state.message);
            }
            if (state.isConnected()) {
                if (!state.clientIP.isEmpty()) {
                    sb.append(" · ").append(getString(R.string.label_client_ip))
                            .append(' ').append(state.clientIP);
                }
                if (state.connectedAt > 0) {
                    sb.append(" · ").append(getString(R.string.label_uptime)).append(' ')
                            .append(Ui.formatDuration(System.currentTimeMillis() - state.connectedAt));
                }
            }
        }
        statusLine.setText(sb.toString());
    }

    private void updateCoreGrid() {
        RvpnState state = client.lastState();
        JSONObject hello = client.hello();
        String phase = state == null ? getString(R.string.value_disconnected)
                : getString(phaseLabel(state.phase));
        setValue(R.string.label_phase, phase);
        setValue(R.string.label_state_message, state == null ? "" : state.message);
        setValue(R.string.label_server_addr, state == null ? "" : state.server);
        setValue(R.string.label_client_ip, state == null || state.clientIP.isEmpty()
                ? getString(R.string.value_none) : state.clientIP);
        setValue(R.string.label_socks, state == null || state.socksAddr.isEmpty()
                ? getString(R.string.value_none) : state.socksAddr);
        setValue(R.string.label_http, state == null || state.httpAddr.isEmpty()
                ? getString(R.string.value_none) : state.httpAddr);
        setValue(R.string.label_proxy_state, proxySummary());
        setValue(R.string.label_connected_at, state == null || state.connectedAt <= 0
                ? getString(R.string.value_disconnected)
                : Ui.formatDuration(System.currentTimeMillis() - state.connectedAt));
        setValue(R.string.label_core_version, Json.str(hello, "coreVersion",
                getString(R.string.value_unknown)));
        setValue(R.string.label_zjuconnect_version, Json.str(hello, "zjuConnectVersion",
                getString(R.string.value_unknown)));
        setValue(R.string.label_platform, Json.str(hello, "platform",
                getString(R.string.value_unknown)));
        JSONArray capabilities = hello == null ? null : Json.arr(hello, "capabilities");
        setValue(R.string.label_capabilities,
                capabilities == null ? getString(R.string.value_unknown)
                        : Json.join(capabilities, ", "));
        String error = client.engineError();
        if (error.isEmpty() && state != null) {
            error = state.lastError;
        }
        setValue(R.string.label_core_error, error.isEmpty() ? getString(R.string.value_none) : error);
    }

    private void setValue(int keyRes, String value) {
        TextView view = coreValues.get(keyRes);
        if (view != null) {
            view.setText(value == null ? "" : value);
        }
    }

    private void appendLog(String line) {
        LogStore.add(line);
        renderLog();
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : LogStore.lines()) {
            sb.append(line).append('\n');
        }
        if (sb.length() == 0) {
            sb.append(getString(R.string.log_empty));
        }
        logView.setText(sb.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
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

}
