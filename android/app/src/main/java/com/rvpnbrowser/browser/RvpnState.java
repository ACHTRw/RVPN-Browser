package com.rvpnbrowser.browser;

import org.json.JSONObject;

/**
 * The {@code state} object of the core (docs/PROTOCOL.md section 2.3).
 *
 * <p>Immutable snapshot; {@link #key()} is used to detect changes without
 * comparing field by field in the UI.</p>
 */
public final class RvpnState {

    public static final String PHASE_IDLE = "idle";
    public static final String PHASE_CONNECTING = "connecting";
    public static final String PHASE_AUTHENTICATING = "authenticating";
    public static final String PHASE_CONNECTED = "connected";
    public static final String PHASE_ERROR = "error";
    public static final String PHASE_STOPPING = "stopping";

    public final String phase;
    public final String message;
    public final long since;
    public final String protocol;
    public final String userType;
    public final String server;
    public final String socksAddr;
    public final String httpAddr;
    public final String clientIP;
    public final long connectedAt;
    public final String lastError;

    public RvpnState(String phase, String message, long since, String protocol, String userType,
                     String server, String socksAddr, String httpAddr, String clientIP,
                     long connectedAt, String lastError) {
        this.phase = phase == null ? PHASE_IDLE : phase;
        this.message = message == null ? "" : message;
        this.since = since;
        this.protocol = protocol == null ? "" : protocol;
        this.userType = userType == null ? "" : userType;
        this.server = server == null ? "" : server;
        this.socksAddr = socksAddr == null ? "" : socksAddr;
        this.httpAddr = httpAddr == null ? "" : httpAddr;
        this.clientIP = clientIP == null ? "" : clientIP;
        this.connectedAt = connectedAt;
        this.lastError = lastError == null ? "" : lastError;
    }

    public static RvpnState idle() {
        return new RvpnState(PHASE_IDLE, "", 0, "", "", "", "", "", "", 0, "");
    }

    public static RvpnState from(JSONObject o) {
        if (o == null) {
            return null;
        }
        return new RvpnState(
                Json.str(o, "phase", PHASE_IDLE),
                Json.str(o, "message"),
                Json.lng(o, "since", 0),
                Json.str(o, "protocol"),
                Json.str(o, "userType"),
                Json.str(o, "server"),
                Json.str(o, "socksAddr"),
                Json.str(o, "httpAddr"),
                Json.str(o, "clientIP"),
                Json.lng(o, "connectedAt", 0),
                Json.str(o, "lastError"));
    }

    public boolean isConnected() {
        return PHASE_CONNECTED.equals(phase);
    }

    public boolean isError() {
        return PHASE_ERROR.equals(phase);
    }

    /** Changes whenever anything the UI shows may have changed. */
    public String key() {
        return phase + '\u0000' + message + '\u0000' + since + '\u0000' + protocol + '\u0000'
                + userType + '\u0000' + server + '\u0000' + socksAddr + '\u0000' + httpAddr
                + '\u0000' + clientIP + '\u0000' + connectedAt + '\u0000' + lastError;
    }

    public String toString() {
        return "RvpnState{" + phase + ", " + message + ", socks=" + socksAddr
                + ", http=" + httpAddr + ", ip=" + clientIP + "}";
    }
}
