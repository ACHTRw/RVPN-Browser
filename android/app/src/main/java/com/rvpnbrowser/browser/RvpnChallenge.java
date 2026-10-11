package com.rvpnbrowser.browser;

import org.json.JSONObject;

/** A pending interactive authentication step (docs/PROTOCOL.md section 2.6). */
public final class RvpnChallenge {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_CLICK_CODE = "clickCode";
    public static final String TYPE_SMS_CODE = "smsCode";
    public static final String TYPE_TOTP = "totp";
    public static final String TYPE_RADIUS_CODE = "radiusCode";
    public static final String TYPE_SECONDARY_AUTH = "secondaryAuth";
    public static final String TYPE_EXTERNAL_LOGIN = "externalLogin";

    public final String id;
    public final String type;
    public final String message;
    public final String imageDataUrl;
    public final boolean canSkipSecondaryAuth;
    public final String loginUrl;
    public final long deadline;
    public final long createdAt;

    public RvpnChallenge(String id, String type, String message, String imageDataUrl,
                         boolean canSkipSecondaryAuth, String loginUrl, long deadline,
                         long createdAt) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.message = message == null ? "" : message;
        this.imageDataUrl = imageDataUrl == null ? "" : imageDataUrl;
        this.canSkipSecondaryAuth = canSkipSecondaryAuth;
        this.loginUrl = loginUrl == null ? "" : loginUrl;
        this.deadline = deadline;
        this.createdAt = createdAt;
    }

    public static RvpnChallenge from(JSONObject o) {
        if (o == null) {
            return null;
        }
        String id = Json.str(o, "id");
        if (id.isEmpty()) {
            return null;
        }
        return new RvpnChallenge(
                id,
                Json.str(o, "type"),
                Json.str(o, "message"),
                Json.str(o, "imageDataUrl"),
                Json.bool(o, "canSkipSecondaryAuth", false),
                Json.str(o, "loginUrl"),
                Json.lng(o, "deadline", 0),
                Json.lng(o, "createdAt", 0));
    }

    public boolean hasImage() {
        return !imageDataUrl.isEmpty();
    }

    /** True when the challenge declares a deadline that is already in the past. */
    public boolean isExpired() {
        return deadline > 0 && System.currentTimeMillis() > deadline;
    }

    /** Milliseconds left, or {@code -1} when the challenge has no deadline. */
    public long remainingMs() {
        if (deadline <= 0) {
            return -1;
        }
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    /** Localised name of the challenge type. */
    public int typeLabelRes() {
        switch (type) {
            case TYPE_TEXT:
                return R.string.challenge_type_text;
            case TYPE_CLICK_CODE:
                return R.string.challenge_type_clickCode;
            case TYPE_SMS_CODE:
                return R.string.challenge_type_smsCode;
            case TYPE_TOTP:
                return R.string.challenge_type_totp;
            case TYPE_RADIUS_CODE:
                return R.string.challenge_type_radiusCode;
            case TYPE_SECONDARY_AUTH:
                return R.string.challenge_type_secondaryAuth;
            case TYPE_EXTERNAL_LOGIN:
                return R.string.challenge_type_externalLogin;
            default:
                return R.string.challenge_unknown;
        }
    }

    @Override
    public String toString() {
        return "RvpnChallenge{" + id + ", " + type + "}";
    }
}
