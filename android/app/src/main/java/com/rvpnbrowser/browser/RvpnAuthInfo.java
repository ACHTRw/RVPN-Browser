package com.rvpnbrowser.browser;

import org.json.JSONObject;

/** One entry of the {@code authInfo} reply (docs/PROTOCOL.md section 2.8). */
public final class RvpnAuthInfo {

    public final String loginDomain;
    public final String authType;
    public final String authName;
    public final String loginUrl;

    public RvpnAuthInfo(String loginDomain, String authType, String authName, String loginUrl) {
        this.loginDomain = loginDomain == null ? "" : loginDomain;
        this.authType = authType == null ? "" : authType;
        this.authName = authName == null ? "" : authName;
        this.loginUrl = loginUrl == null ? "" : loginUrl;
    }

    public static RvpnAuthInfo from(JSONObject o) {
        if (o == null) {
            return null;
        }
        return new RvpnAuthInfo(
                Json.str(o, "loginDomain"),
                Json.str(o, "authType"),
                Json.str(o, "authName"),
                Json.str(o, "loginUrl"));
    }

    /** "上网账号 (auth/psw)" style label, falling back to whichever part exists. */
    public String label() {
        String type = authType.isEmpty() ? loginDomain : authType;
        if (!authName.isEmpty() && !type.isEmpty()) {
            return authName + " (" + type + ")";
        }
        if (!authName.isEmpty()) {
            return authName;
        }
        if (loginDomain.isEmpty()) {
            return type;
        }
        return loginDomain + " (" + type + ")";
    }

    @Override
    public String toString() {
        return "RvpnAuthInfo{" + loginDomain + ", " + authType + ", " + authName + "}";
    }
}
