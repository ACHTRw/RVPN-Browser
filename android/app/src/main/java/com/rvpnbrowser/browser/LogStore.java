package com.rvpnbrowser.browser;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Process-wide ring buffer of the last {@value #MAX_LINES} log lines.
 *
 * <p>The RVPN settings screen shows it, both activities write into it, and the
 * "copy diagnostics" button includes it. It is deliberately a plain static
 * buffer instead of LiveData or a local broadcast: the two activities are never
 * visible at the same time, so a getter is enough and no lifecycle aware
 * plumbing is needed.</p>
 */
public final class LogStore {

    private static final int MAX_LINES = 500;
    private static final List<String> LINES = new ArrayList<>();

    private LogStore() {
    }

    /** Appends one line with a wall-clock prefix. */
    public static void add(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        String entry = Ui.formatClock(System.currentTimeMillis()) + "  " + line;
        synchronized (LINES) {
            LINES.add(entry);
            while (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
        }
    }

    /** Appends a localised line. */
    public static void add(Context context, int stringRes) {
        add(context.getString(stringRes));
    }

    /** Appends a localised line with format arguments. */
    public static void add(Context context, int stringRes, Object... args) {
        add(context.getString(stringRes, args));
    }

    /** Appends a line produced by the core, keeping its own timestamp. */
    public static void addCore(long timestamp, String level, String message) {
        String prefix = timestamp > 0 ? Ui.formatClock(timestamp) : Ui.formatClock(
                System.currentTimeMillis());
        add(prefix + " [" + (level == null ? "info" : level) + "] " + (message == null ? "" : message));
    }

    /** Snapshot, oldest first. */
    public static List<String> lines() {
        synchronized (LINES) {
            return Collections.unmodifiableList(new ArrayList<>(LINES));
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
