package com.orbitdial.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Gesture Speed Dial paths, stored on this device. */
final class Stores {
    private Stores() {}

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

    /** "159" becomes "1-5-9". */
    static String prettify(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            if (i > 0) sb.append('-');
            sb.append(pattern.charAt(i));
        }
        return sb.toString();
    }

    static final class GestureStore {
        private static final String SEP = "\u001F";
        private final SharedPreferences prefs;

        GestureStore(Context c) {
            prefs = c.getApplicationContext().getSharedPreferences("gestures", Context.MODE_PRIVATE);
        }

        Gesture get(String pattern) {
            String v = prefs.getString(pattern, null);
            if (v == null) return null;
            String[] parts = v.split(SEP, 2);
            return parts.length == 2 ? new Gesture(pattern, parts[0], parts[1]) : null;
        }

        void put(String pattern, String name, String number) {
            // One gesture per person: drop any older path for the same number.
            for (Gesture g : all()) if (Data.sameNumber(g.number, number)) remove(g.pattern);
            prefs.edit().putString(pattern, name + SEP + number).apply();
        }

        void remove(String pattern) {
            prefs.edit().remove(pattern).apply();
        }

        Gesture forNumber(String number) {
            for (Gesture g : all()) if (Data.sameNumber(g.number, number)) return g;
            return null;
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
}
