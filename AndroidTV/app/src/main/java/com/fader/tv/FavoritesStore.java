package com.fader.tv;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Local favourites, stored as a small JSON list so they survive restarts. */
final class FavoritesStore {
    private static final String KEY = "items";
    private final SharedPreferences prefs;
    private final LinkedHashMap<String, Saved> map = new LinkedHashMap<>();

    private static final class Saved {
        String url = "", name = "", title = "", artist = "", album = "";
        long size, modified;
        int duration;
    }

    FavoritesStore(SharedPreferences preferences) {
        prefs = preferences;
        load();
    }

    int size() { return map.size(); }

    /** A favourite is identified by its URL, or — for the same song copied to two
     *  sources (NAS + USB) — by its file name, so it can never be duplicated. */
    boolean has(TrackItem track) { return track != null && existingKey(track) != null; }

    /** Returns true when the track was added, false when it was removed. */
    boolean toggle(TrackItem track) {
        if (track == null) return false;
        String existing = existingKey(track);
        if (existing != null) {
            map.remove(existing);
            save();
            return false;
        }
        Saved saved = new Saved();
        saved.url = track.entry.url;
        saved.name = track.entry.name;
        saved.size = track.entry.size;
        saved.modified = track.entry.modified;
        saved.title = track.title;
        saved.artist = track.artist;
        saved.album = track.album;
        saved.duration = track.duration;
        map.put(track.entry.url, saved);
        save();
        return true;
    }

    private String existingKey(TrackItem track) {
        String url = track.entry.url;
        if (map.containsKey(url)) return url;
        for (java.util.Map.Entry<String, Saved> e : map.entrySet()) {
            if (e.getValue().name != null && e.getValue().name.equals(track.entry.name)) return e.getKey();
        }
        return null;
    }

    void remove(String key) {
        if (map.remove(key) != null) save();
    }

    /** Rebuilds playable rows from the stored data, choosing the correct source
     *  (network for smb:// URLs, local/USB otherwise) for each saved song. */
    List<TrackItem> tracks(Stream network, Stream local) {
        List<TrackItem> out = new ArrayList<>();
        for (Saved saved : map.values()) {
            Stream stream = saved.url != null && saved.url.startsWith("smb://") ? network : local;
            if (stream == null) continue;
            NasClient.Entry entry = new NasClient.Entry(saved.name, saved.url, false, saved.size, saved.modified);
            TrackItem track = new TrackItem(stream, entry, null);
            track.title = saved.title;
            track.artist = saved.artist;
            track.album = saved.album;
            track.duration = saved.duration;
            out.add(track);
        }
        return out;
    }

    private void load() {
        try {
            String raw = prefs.getString(KEY, "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                Saved saved = new Saved();
                saved.url = o.optString("url");
                saved.name = o.optString("name");
                saved.title = o.optString("title");
                saved.artist = o.optString("artist");
                saved.album = o.optString("album");
                saved.size = o.optLong("size");
                saved.modified = o.optLong("modified");
                saved.duration = o.optInt("duration");
                if (!saved.url.isEmpty()) map.put(key(saved), saved);
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (Saved saved : map.values()) {
                JSONObject o = new JSONObject();
                o.put("url", saved.url);
                o.put("name", saved.name);
                o.put("title", saved.title);
                o.put("artist", saved.artist);
                o.put("album", saved.album);
                o.put("size", saved.size);
                o.put("modified", saved.modified);
                o.put("duration", saved.duration);
                array.put(o);
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static String key(Saved saved) {
        return saved.url;
    }
}
