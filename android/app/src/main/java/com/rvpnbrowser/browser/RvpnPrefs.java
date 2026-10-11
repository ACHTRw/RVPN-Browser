package com.rvpnbrowser.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;

/**
 * Persistent RVPN settings, mirroring the fields of the desktop extension
 * (browser/extension/options.html) and the {@code Config} object of
 * docs/PROTOCOL.md section 2.2.
 *
 * <p><b>Password handling.</b> When "记住密码 / Remember password" is enabled the
 * password is written in clear text into the private SharedPreferences file of
 * the app (only readable by this app on a non-rooted device). When it is
 * disabled the password is kept in a static field, i.e. in the memory of the
 * current process only, and {@link #configJson()} still returns it so that the
 * connection works for this run.</p>
 */
public final class RvpnPrefs {

    public static final String MODE_NORMAL = "normal";
    public static final String MODE_RVPN = "rvpn";

    public static final String USER_TYPE_ZJU = "zju";
    public static final String USER_TYPE_OTHER = "other";

    public static final String PROTOCOL_ATRUST = "atrust";
    public static final String PROTOCOL_EASYCONNECT = "easyconnect";

    public static final String AUTH_PSW = "auth/psw";
    public static final String AUTH_CAS = "auth/cas";
    public static final String AUTH_SMS = "auth/smsCheckCode";
    public static final String AUTH_OAUTH2 = "auth/httpsOauth2";

    private static final String FILE = "rvpn_settings";

    private static final String K_MODE = "mode";
    private static final String K_AUTO_CONNECT = "autoConnect";
    private static final String K_USER_TYPE = "userType";
    private static final String K_PROTOCOL = "protocol";
    private static final String K_SERVER = "server";
    private static final String K_PORT = "port";
    private static final String K_USERNAME = "username";
    private static final String K_PASSWORD = "password";
    private static final String K_REMEMBER = "rememberPassword";
    private static final String K_LOGIN_DOMAIN = "loginDomain";
    private static final String K_AUTH_TYPE = "authType";
    private static final String K_PHONE = "phone";
    private static final String K_TOTP = "totpSecret";
    private static final String K_DEBUG = "debug";
    private static final String K_PROXY_ALL = "proxyAll";
    private static final String K_SOCKS_PORT = "socksPort";
    private static final String K_HTTP_PORT = "httpPort";

    /** Password of the current run when "remember password" is off. */
    private static volatile String transientPassword = "";

    private final Context appContext;
    private final SharedPreferences sp;

    public RvpnPrefs(Context context) {
        this.appContext = context.getApplicationContext();
        this.sp = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- mode

    public String mode() {
        String m = sp.getString(K_MODE, MODE_NORMAL);
        return MODE_RVPN.equals(m) ? MODE_RVPN : MODE_NORMAL;
    }

    public void setMode(String mode) {
        sp.edit().putString(K_MODE, MODE_RVPN.equals(mode) ? MODE_RVPN : MODE_NORMAL).apply();
    }

    public boolean isRvpnMode() {
        return MODE_RVPN.equals(mode());
    }

    public boolean autoConnect() {
        return sp.getBoolean(K_AUTO_CONNECT, true);
    }

    public void setAutoConnect(boolean value) {
        sp.edit().putBoolean(K_AUTO_CONNECT, value).apply();
    }

    // ---------------------------------------------------------- account

    public String userType() {
        String v = sp.getString(K_USER_TYPE, USER_TYPE_ZJU);
        return USER_TYPE_OTHER.equals(v) ? USER_TYPE_OTHER : USER_TYPE_ZJU;
    }

    public void setUserType(String value) {
        sp.edit().putString(K_USER_TYPE,
                USER_TYPE_OTHER.equals(value) ? USER_TYPE_OTHER : USER_TYPE_ZJU).apply();
    }

    public boolean isZju() {
        return USER_TYPE_ZJU.equals(userType());
    }

    public String protocol() {
        String v = sp.getString(K_PROTOCOL, PROTOCOL_ATRUST);
        return PROTOCOL_EASYCONNECT.equals(v) ? PROTOCOL_EASYCONNECT : PROTOCOL_ATRUST;
    }

    public void setProtocol(String value) {
        sp.edit().putString(K_PROTOCOL,
                PROTOCOL_EASYCONNECT.equals(value) ? PROTOCOL_EASYCONNECT : PROTOCOL_ATRUST).apply();
    }

    public String server() {
        return sp.getString(K_SERVER, "");
    }

    public void setServer(String value) {
        sp.edit().putString(K_SERVER, value == null ? "" : value.trim()).apply();
    }

    public int port() {
        return sp.getInt(K_PORT, 443);
    }

    public void setPort(int value) {
        sp.edit().putInt(K_PORT, value).apply();
    }

    public String username() {
        return sp.getString(K_USERNAME, "");
    }

    public void setUsername(String value) {
        sp.edit().putString(K_USERNAME, value == null ? "" : value.trim()).apply();
    }

    public boolean rememberPassword() {
        return sp.getBoolean(K_REMEMBER, false);
    }

    public void setRememberPassword(boolean value) {
        SharedPreferences.Editor e = sp.edit().putBoolean(K_REMEMBER, value);
        if (!value) {
            e.remove(K_PASSWORD);
        }
        e.apply();
    }

    /** The password to use: the stored one, or the in-memory one for this run. */
    public String password() {
        if (rememberPassword()) {
            return sp.getString(K_PASSWORD, "");
        }
        return transientPassword == null ? "" : transientPassword;
    }

    public void setPassword(String value) {
        String pw = value == null ? "" : value;
        if (rememberPassword()) {
            sp.edit().putString(K_PASSWORD, pw).apply();
        } else {
            transientPassword = pw;
            sp.edit().remove(K_PASSWORD).apply();
        }
    }

    public String loginDomain() {
        return sp.getString(K_LOGIN_DOMAIN, "");
    }

    public void setLoginDomain(String value) {
        sp.edit().putString(K_LOGIN_DOMAIN, value == null ? "" : value.trim()).apply();
    }

    public String authType() {
        return sp.getString(K_AUTH_TYPE, "");
    }

    public void setAuthType(String value) {
        sp.edit().putString(K_AUTH_TYPE, value == null ? "" : value.trim()).apply();
    }

    public String phone() {
        return sp.getString(K_PHONE, "");
    }

    public void setPhone(String value) {
        sp.edit().putString(K_PHONE, value == null ? "" : value.trim()).apply();
    }

    // --------------------------------------------------------- advanced

    public String totpSecret() {
        return sp.getString(K_TOTP, "");
    }

    public void setTotpSecret(String value) {
        sp.edit().putString(K_TOTP, value == null ? "" : value.trim()).apply();
    }

    public boolean debug() {
        return sp.getBoolean(K_DEBUG, false);
    }

    public void setDebug(boolean value) {
        sp.edit().putBoolean(K_DEBUG, value).apply();
    }

    public boolean proxyAll() {
        return sp.getBoolean(K_PROXY_ALL, false);
    }

    public void setProxyAll(boolean value) {
        sp.edit().putBoolean(K_PROXY_ALL, value).apply();
    }

    /** 0 = let the core pick a free port. */
    public int socksPort() {
        return sp.getInt(K_SOCKS_PORT, 0);
    }

    public void setSocksPort(int value) {
        sp.edit().putInt(K_SOCKS_PORT, value).apply();
    }

    /** 0 = let the core pick a free port. The HTTP proxy is always enabled. */
    public int httpPort() {
        return sp.getInt(K_HTTP_PORT, 0);
    }

    public void setHttpPort(int value) {
        sp.edit().putInt(K_HTTP_PORT, value).apply();
    }

    public String socksBind() {
        return "127.0.0.1:" + Math.max(0, socksPort());
    }

    public String httpBind() {
        return "127.0.0.1:" + Math.max(0, httpPort());
    }

    /** aTrust session persistence file, kept inside the private app storage. */
    public String clientDataFile() {
        File dir = new File(appContext.getFilesDir(), "rvpn");
        return new File(dir, "client_data.json").getAbsolutePath();
    }

    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    // -------------------------------------------------------- validation

    /**
     * Validates the settings the same way the core normalises a configuration.
     *
     * @return 0 when the settings are usable, otherwise the string resource id of
     *     the first problem.
     */
    public int validate() {
        if (USER_TYPE_OTHER.equals(userType())) {
            if (server().isEmpty()) {
                return R.string.err_server_required;
            }
            if (!isValidPort(port())) {
                return R.string.err_bad_port;
            }
        }
        String auth = authType();
        if (PROTOCOL_ATRUST.equals(protocol())) {
            if (AUTH_SMS.equals(auth)) {
                if (phone().isEmpty()) {
                    return R.string.err_phone_required;
                }
            } else if (auth.isEmpty() || AUTH_PSW.equals(auth)) {
                if (username().isEmpty() || password().isEmpty()) {
                    return R.string.err_username_required;
                }
            }
        } else if (username().isEmpty() || password().isEmpty()) {
            return R.string.err_username_required;
        }
        if (socksPort() != 0 && !isValidPort(socksPort())) {
            return R.string.err_bad_port;
        }
        if (httpPort() != 0 && !isValidPort(httpPort())) {
            return R.string.err_bad_port;
        }
        return 0;
    }

    /**
     * The {@code config} object sent to the core with the {@code start} command
     * (docs/PROTOCOL.md section 2.2).
     */
    public JSONObject configJson() {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("userType", userType());
            cfg.put("protocol", protocol());
            cfg.put("server", server());
            cfg.put("port", port());
            cfg.put("username", username());
            cfg.put("password", password());
            cfg.put("loginDomain", loginDomain());
            cfg.put("authType", authType());
            cfg.put("phone", phone());
            cfg.put("totpSecret", totpSecret());
            cfg.put("clientDataFile", clientDataFile());
            // Both local proxies are always enabled: the browser prefers SOCKS5 and
            // falls back to HTTP when the WebView refuses the SOCKS rule.
            cfg.put("socksBind", socksBind());
            cfg.put("httpBind", httpBind());
            cfg.put("proxyAll", proxyAll());
            cfg.put("disableZJUConfig", !isZju());
            cfg.put("skipDomainResource", !isZju());
            cfg.put("disableRemoteDNS", false);
            cfg.put("remoteDNSServer", "auto");
            cfg.put("secondaryDNSServer", "auto");
            cfg.put("dnsTTLSeconds", 3600);
            cfg.put("debug", debug());
        } catch (Exception e) {
            // JSONObject.put only fails on NaN/Infinity, which cannot happen here.
            throw new IllegalStateException("cannot build the core configuration", e);
        }
        return cfg;
    }

    /** Persists the complete state of the user interface at once. */
    public void save(String userType, String protocol, String server, int port, String username,
                     String password, boolean rememberPassword, String loginDomain,
                     String authType, String phone, String totpSecret, boolean debug,
                     boolean proxyAll, int socksPort, int httpPort) {
        setUserType(userType);
        setProtocol(protocol);
        setServer(server);
        setPort(port);
        setUsername(username);
        setRememberPassword(rememberPassword);
        setPassword(password);
        setLoginDomain(loginDomain);
        setAuthType(authType);
        setPhone(phone);
        setTotpSecret(totpSecret);
        setDebug(debug);
        setProxyAll(proxyAll);
        setSocksPort(socksPort);
        setHttpPort(httpPort);
    }

    /** Restores the built-in defaults, keeping the selected mode. */
    public void resetToDefaults() {
        String mode = mode();
        sp.edit().clear().apply();
        transientPassword = "";
        setMode(mode);
    }
}
