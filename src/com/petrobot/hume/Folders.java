package com.petrobot.hume;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Folders the user makes on the Home screen to hold many apps: a name and the keys of the apps
 * inside, stored as JSON in the launcher's preferences. An app in a folder is not on the Home
 * grid; the folder tile (with the first apps' icons) is, where the app was.
 */
final class Folders {
    static final String PREF = "folders";

    private Folders() {
    }

    static final class Folder {
        String id, name;
        final List<String> keys = new ArrayList<>();
    }

    static List<Folder> read(SharedPreferences p) {
        List<Folder> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Folder f = new Folder();
                f.id = o.getString("id");
                f.name = o.optString("name", "Folder");
                JSONArray k = o.optJSONArray("apps");
                for (int j = 0; k != null && j < k.length(); j++) f.keys.add(k.getString(j));
                out.add(f);
            }
        } catch (Exception ignored) {
            // no folders
        }
        return out;
    }

    static void write(SharedPreferences p, List<Folder> list) {
        JSONArray a = new JSONArray();
        try {
            for (Folder f : list) {
                a.put(new JSONObject().put("id", f.id).put("name", f.name).put("apps", new JSONArray(f.keys)));
            }
        } catch (Exception ignored) {
            // keep what was built
        }
        p.edit().putString(PREF, a.toString()).apply();
    }

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36);
    }
}
