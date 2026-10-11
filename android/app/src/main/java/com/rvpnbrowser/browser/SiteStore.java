package com.rvpnbrowser.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A small persistent list of {title, url, time} entries, used for the bookmarks
 * and for the browsing history.
 *
 * <p>Stored as a JSON array in SharedPreferences - the volume (history is capped
 * at 2000 entries) does not justify a database, and it keeps the app free of
 * schema migrations.</p>
 */
public final class SiteStore {

    private static final String TAG = "SiteStore";
    private static final String FILE = "rvpn_sites";

    private final SharedPreferences prefs;
    private final String key;
    private final int capacity;
    private final List<Entry> entries = new ArrayList<>();
    private boolean loaded;

    /** One entry. */
    public static final class Entry {
        public final String title;
        public final String url;
        public final long time;

        public Entry(String title, String url, long time) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.time = time;
        }
    }

    public SiteStore(Context context, String key, int capacity) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        this.key = key;
        this.capacity = capacity;
    }

    /** Most recent entry first. */
    public synchronized List<Entry> list() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized int size() {
        ensureLoaded();
        return entries.size();
    }

    public synchronized boolean contains(String url) {
        ensureLoaded();
        if (url == null) {
            return false;
        }
        for (Entry e : entries) {
            if (url.equals(e.url)) {
                return true;
            }
        }
        return false;
    }

    /** Adds (or moves to the front) an entry and persists the list. */
    public synchronized void add(String title, String url) {
        ensureLoaded();
        if (url == null || url.isEmpty()) {
            return;
        }
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (url.equals(it.next().url)) {
                it.remove();
            }
        }
        entries.add(0, new Entry(title, url, System.currentTimeMillis()));
        while (entries.size() > capacity) {
            entries.remove(entries.size() - 1);
        }
        persist();
    }

    public synchronized boolean remove(String url) {
        ensureLoaded();
        boolean removed = false;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().url.equals(url)) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (removed) {
            persist();
        }
        return removed;
    }

    public synchronized void clear() {
        ensureLoaded();
        entries.clear();
        persist();
    }

    // ------------------------------------------------------------ internals

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        String raw = prefs.getString(key, "");
        JSONArray array = null;
        if (!raw.isEmpty()) {
            try {
                array = new JSONArray(raw);
            } catch (Exception e) {
                Log.w(TAG, "cannot parse the stored list " + key, e);
            }
        }
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length() && entries.size() < capacity; i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String url = Json.str(o, "url");
            if (url.isEmpty()) {
                continue;
            }
            entries.add(new Entry(Json.str(o, "title"), url, Json.lng(o, "time", 0)));
        }
    }

    private void persist() {
        JSONArray array = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", e.title);
                o.put("url", e.url);
                o.put("time", e.time);
            } catch (Exception ignored) {
                continue;
            }
            array.put(o);
        }
        prefs.edit().putString(key, array.toString()).apply();
    }
}
