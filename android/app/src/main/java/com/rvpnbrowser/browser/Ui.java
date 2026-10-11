package com.rvpnbrowser.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

/** Small UI helpers shared by the activities. */
public final class Ui {

    private static final String TAG = "Ui";

    private Ui() {
    }

    public static void toast(Context context, int resId) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public static void toastLong(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    public static boolean copyToClipboard(Context context, String label, String text) {
        try {
            ClipboardManager cm = context.getSystemService(ClipboardManager.class);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "clipboard failed", t);
            return false;
        }
    }

    public static String readClipboard(Context context) {
        try {
            ClipboardManager cm = context.getSystemService(ClipboardManager.class);
            if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
                return "";
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
            return text == null ? "" : text.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void share(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url);
        startChooser(context, intent, context.getString(R.string.share_chooser_title));
    }

    private static void startChooser(Context context, Intent intent, String title) {
        try {
            Intent chooser = Intent.createChooser(intent, title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Throwable t) {
            Log.w(TAG, "no activity for " + intent, t);
            toast(context, R.string.share_no_app);
        }
    }

    /** Hands a link (or any URI) to another app. Returns false when none can handle it. */
    public static boolean openExternal(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot open " + url, t);
            toast(context, R.string.external_app_missing);
            return false;
        }
    }

    /** "3:07" or "1:02:03" - used for the uptime of a connection. */
    public static String formatDuration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** Human readable byte count. */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    /** "14:32" style clock time of a millisecond timestamp. */
    public static String formatClock(long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(timestamp);
        return String.format(Locale.US, "%02d:%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
    }

    /**
     * Decodes a {@code data:image/...;base64,...} URL used by the captcha
     * challenges. Returns {@code null} when the payload is not a base64 image.
     */
    public static Bitmap decodeDataUrl(String dataUrl) {
        if (dataUrl == null) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        if (comma <= 0 || comma == dataUrl.length() - 1) {
            return null;
        }
        String header = dataUrl.substring(0, comma);
        if (!header.contains("base64")) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            Log.w(TAG, "cannot decode the challenge image", t);
            return null;
        }
    }

    /** Best-effort MIME type from a URL, for the download notification. */
    public static String guessMime(String url, String fallback) {
        if (url == null) {
            return fallback;
        }
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "application/msword";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "application/vnd.ms-excel";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "application/vnd.ms-powerpoint";
        }
        return fallback;
    }
}
