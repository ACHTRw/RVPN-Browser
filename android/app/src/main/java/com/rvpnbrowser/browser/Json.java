package com.rvpnbrowser.browser;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Defensive helpers around {@link JSONObject}.
 *
 * <p>Android's {@code org.json} turns a JSON {@code null} into the literal
 * string {@code "null"} when {@code optString} is used, which would silently
 * corrupt every value the core omits. These helpers always return the caller's
 * fallback for missing, JSON-null and wrongly typed values, so no parsing code
 * has to care.</p>
 */
public final class Json {

    private Json() {
    }

    public static String str(JSONObject o, String key, String fallback) {
        if (o == null) {
            return fallback;
        }
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) {
            return fallback;
        }
        if (v instanceof String) {
            return (String) v;
        }
        return String.valueOf(v);
    }

    public static String str(JSONObject o, String key) {
        return str(o, key, "");
    }

    public static long lng(JSONObject o, String key, long fallback) {
        if (o == null) {
            return fallback;
        }
        Object v = o.opt(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(JSONObject o, String key, boolean fallback) {
        if (o == null) {
            return fallback;
        }
        Object v = o.opt(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean(((String) v).trim());
        }
        return fallback;
    }

    public static JSONObject obj(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    public static JSONArray arr(JSONObject o, String key) {
        return o == null ? null : o.optJSONArray(key);
    }

    /** Parses a line of JSON, returning {@code null} instead of throwing. */
    public static JSONObject parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    /** Joins a JSON array of strings, ignoring non-string entries. */
    public static String join(JSONArray array, String separator) {
        if (array == null || array.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            Object v = array.opt(i);
            if (v == null || v == JSONObject.NULL) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
