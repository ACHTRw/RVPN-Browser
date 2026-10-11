package com.rvpnbrowser.browser;

import org.json.JSONObject;

/** One item of the incremental event stream (docs/PROTOCOL.md section 2.5). */
public final class RvpnEvent {

    public static final String KIND_LOG = "log";
    public static final String KIND_STATE = "state";
    public static final String KIND_CHALLENGE = "challenge";
    public static final String KIND_CHALLENGE_DONE = "challengeDone";
    public static final String KIND_ERROR = "error";

    public static final String LEVEL_DEBUG = "debug";
    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_ERROR = "error";

    public final long seq;
    public final long ts;
    public final String kind;
    public final String level;
    public final String message;
    public final RvpnChallenge challenge;

    public RvpnEvent(long seq, long ts, String kind, String level, String message,
                     RvpnChallenge challenge) {
        this.seq = seq;
        this.ts = ts;
        this.kind = kind == null ? KIND_LOG : kind;
        this.level = level == null ? LEVEL_INFO : level;
        this.message = message == null ? "" : message;
        this.challenge = challenge;
    }

    public static RvpnEvent from(JSONObject o) {
        if (o == null) {
            return null;
        }
        RvpnChallenge challenge = null;
        JSONObject c = Json.obj(o, "challenge");
        if (c != null) {
            challenge = RvpnChallenge.from(c);
        }
        return new RvpnEvent(
                Json.lng(o, "seq", 0),
                Json.lng(o, "ts", 0),
                Json.str(o, "kind", KIND_LOG),
                Json.str(o, "level", LEVEL_INFO),
                Json.str(o, "message"),
                challenge);
    }

    public boolean isError() {
        return KIND_ERROR.equals(kind) || LEVEL_ERROR.equals(level);
    }

    public String toString() {
        return kind + "/" + level + ": " + message;
    }
}
