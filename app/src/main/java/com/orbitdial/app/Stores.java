package com.orbitdial.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small on-device stores. Nothing leaves the phone. */
final class Stores {
    private Stores() {}

    /** A saved Gesture Speed Dial: a key pattern bound to a contact. */
    static final class Gesture {
        final String pattern, name, number;

        Gesture(String pattern, String name, String number) {
            this.pattern = pattern;
            this.name = name;
            this.number = number;
        }

        String prettyPattern() {
            return prettify(pattern);
        }
    }

    static String prettify(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(pattern.charAt(i));
        }
        return sb.toString();
    }

    static final class GestureStore {
        private static final String SEP = "\u001F";
        private final SharedPreferences prefs;

        GestureStore(Context c) {
            prefs = c.getSharedPreferences("gestures", Context.MODE_PRIVATE);
        }

        Gesture get(String pattern) {
            String v = prefs.getString(pattern, null);
            if (v == null) return null;
            String[] parts = v.split(SEP, 2);
            return parts.length == 2 ? new Gesture(pattern, parts[0], parts[1]) : null;
        }

        void put(String pattern, String name, String number) {
            prefs.edit().putString(pattern, name + SEP + number).apply();
        }

        void remove(String pattern) {
            prefs.edit().remove(pattern).apply();
        }

        List<Gesture> all() {
            List<Gesture> out = new ArrayList<>();
            Map<String, ?> sorted = new TreeMap<>(prefs.getAll());
            for (String key : sorted.keySet()) {
                Gesture g = get(key);
                if (g != null) out.add(g);
            }
            return out;
        }
    }

    static final class Recent {
        final String number, label;
        final long time;

        Recent(String number, String label, long time) {
            this.number = number;
            this.label = label;
            this.time = time;
        }
    }

    static final class RecentStore {
        private static final int MAX = 25;
        private final SharedPreferences prefs;

        RecentStore(Context c) {
            prefs = c.getSharedPreferences("recents", Context.MODE_PRIVATE);
        }

        List<Recent> all() {
            List<Recent> out = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(prefs.getString("list", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    out.add(new Recent(o.getString("n"), o.optString("l", ""), o.optLong("t")));
                }
            } catch (JSONException ignored) {
                // Corrupt data: start fresh.
            }
            return out;
        }

        void add(String number, String label) {
            List<Recent> list = all();
            JSONArray arr = new JSONArray();
            try {
                JSONObject first = new JSONObject();
                first.put("n", number);
                first.put("l", label == null ? "" : label);
                first.put("t", System.currentTimeMillis());
                arr.put(first);
                for (Recent r : list) {
                    if (arr.length() >= MAX) break;
                    if (r.number.equals(number)) continue; // keep one entry per number
                    JSONObject o = new JSONObject();
                    o.put("n", r.number);
                    o.put("l", r.label);
                    o.put("t", r.time);
                    arr.put(o);
                }
            } catch (JSONException ignored) {
                return;
            }
            prefs.edit().putString("list", arr.toString()).apply();
        }

        void clear() {
            prefs.edit().remove("list").apply();
        }
    }
}
