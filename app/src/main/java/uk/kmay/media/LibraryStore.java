package uk.kmay.media;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Local user data. Legacy keys remain readable across APK upgrades. */
final class LibraryStore {
    final SharedPreferences prefs;
    LibraryStore(SharedPreferences prefs) {
        this.prefs = prefs;
        if (prefs.getInt("libraryVersion", 0) < 2) prefs.edit().putInt("libraryVersion", 2).apply();
    }
    static String key(JSONObject media) { return media.optString("type", "movie") + ":" + media.optString("id"); }
    static String episodeId(JSONObject media, JSONObject episode) {
        return episode.optString("id", media.optString("id") + ":" + episode.optInt("season", 1) + ":" + episode.optInt("episode", 1));
    }
    JSONArray array(String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }
    void put(String key, JSONArray value) { prefs.edit().putString(key, value.toString()).apply(); }
    boolean contains(String list, JSONObject item) {
        for (JSONObject old : Net.objects(array(list))) if (key(old).equals(key(item))) return true;
        return false;
    }
    void toggle(String list, JSONObject item) {
        boolean found = contains(list, item);
        JSONArray result = new JSONArray();
        for (JSONObject old : Net.objects(array(list))) if (!key(old).equals(key(item))) result.put(old);
        if (!found) result.put(item);
        put(list, result);
    }
    void removeHistory(JSONObject item) {
        JSONArray out = new JSONArray();
        for (JSONObject old : Net.objects(array("history"))) if (!key(old).equals(key(item))) out.put(old);
        put("history", out);
    }
    JSONObject resume(JSONObject item) {
        for (JSONObject old : Net.objects(array("history"))) if (key(old).equals(key(item))) return old;
        return null;
    }
    boolean watched(String id) { return prefs.getBoolean("watched:" + id, false); }
    void watched(String id, boolean value) { prefs.edit().putBoolean("watched:" + id, value).putLong("position:" + id, 0).apply(); }
    void remember(JSONObject item, String id, String label, long position, long duration) {
        if (item == null || id.isEmpty() || position < 0) return;
        boolean complete = duration > 0 && position >= Math.max(0, duration - 30000);
        SharedPreferences.Editor edit = prefs.edit().putLong("position:" + id, complete ? 0 : position);
        if (duration > 0) edit.putLong("duration:" + id, duration);
        if (complete) edit.putBoolean("watched:" + id, true);
        else if (position > 0) edit.putBoolean("watched:" + id, false);
        edit.apply();
        JSONArray out = new JSONArray();
        if (!complete && position > 0) {
            try { out.put(new JSONObject(item.toString()).put("_videoId", id).put("_videoLabel", label)); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
        for (JSONObject old : Net.objects(array("history"))) if (!key(old).equals(key(item)) && out.length() < 30) out.put(old);
        put("history", out);
    }
    List<String> strings(String key, List<String> defaults) {
        if (!prefs.contains(key)) return new ArrayList<>(defaults);
        List<String> result = new ArrayList<>(); JSONArray a = array(key);
        for (int i = 0; i < a.length(); i++) result.add(a.optString(i));
        return result;
    }
    void putStrings(String key, List<String> values) { put(key, new JSONArray(values)); }
    void search(String value) {
        if (value.isEmpty()) return;
        List<String> recent = strings("recentSearches", Collections.emptyList()); recent.remove(value); recent.add(0, value);
        putStrings("recentSearches", recent.subList(0, Math.min(12, recent.size())));
    }
    void clearProgress() {
        SharedPreferences.Editor edit = prefs.edit().remove("history");
        for (String key : prefs.getAll().keySet()) if (key.startsWith("position:") || key.startsWith("duration:") || key.startsWith("watched:")) edit.remove(key);
        edit.apply();
    }
}
