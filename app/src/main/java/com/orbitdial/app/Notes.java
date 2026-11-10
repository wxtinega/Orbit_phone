package com.orbitdial.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Call notes and recordings, stored per phone number on this device only. */
final class Notes {
    private Notes() {}

    static final int NOTE = 0, RECORDING = 1;

    static final class Item {
        final int kind;
        final long created, callTime;
        final String text;   // note text, or recording file path
        final long length;   // recording length in seconds

        Item(int kind, long created, long callTime, String text, long length) {
            this.kind = kind;
            this.created = created;
            this.callTime = callTime;
            this.text = text;
            this.length = length;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("notes", Context.MODE_PRIVATE);
    }

    static synchronized List<Item> forNumber(Context c, String number) {
        List<Item> out = new ArrayList<>();
        String k = Data.key(number);
        if (k.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(sp(c).getString(k, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item(o.optInt("k"), o.optLong("c"), o.optLong("t"), o.optString("x"), o.optLong("l")));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (x, y) -> Long.compare(y.created, x.created));
        return out;
    }

    static synchronized void add(Context c, String number, int kind, long callTime, String text, long length) {
        String k = Data.key(number);
        if (k.isEmpty() || text == null || text.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(sp(c).getString(k, "[]"));
            JSONObject o = new JSONObject();
            o.put("k", kind);
            o.put("c", System.currentTimeMillis());
            o.put("t", callTime);
            o.put("x", text);
            o.put("l", length);
            a.put(o);
            sp(c).edit().putString(k, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized void remove(Context c, String number, Item item) {
        String k = Data.key(number);
        try {
            JSONArray a = new JSONArray(sp(c).getString(k, "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("c") == item.created && o.optString("x").equals(item.text)) continue;
                keep.put(o);
            }
            sp(c).edit().putString(k, keep.toString()).apply();
            if (item.kind == RECORDING) {
                //noinspection ResultOfMethodCallIgnored
                new File(item.text).delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** The note or recording made during a particular call, matched by time. */
    static Item forCall(List<Item> items, long callDate, int kind) {
        for (Item i : items) {
            if (i.kind == kind && Math.abs(i.callTime - callDate) < 3 * 60 * 1000) return i;
        }
        return null;
    }

    static boolean hasAny(Context c, String number) {
        return sp(c).contains(Data.key(number)) && !forNumber(c, number).isEmpty();
    }
}
