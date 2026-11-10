package com.orbitdial.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** All user settings in one place. */
final class Prefs {
    private Prefs() {}

    static final String MODE_AUTO = "auto", MODE_LIGHT = "light", MODE_DARK = "dark";
    static final String GLASS_OFF = "off", GLASS_SOFT = "soft", GLASS_STRONG = "strong";
    static final String DIAL_ORBIT = "orbit", DIAL_GRID = "grid";
    static final String REC_OFF = "off", REC_SPEAKER = "speaker", REC_CALL_AUDIO = "call_audio";

    /** Accent choices. Orange is the default; no purple. */
    static final int[] ACCENTS = {0xFFF86939, 0xFF2F80ED, 0xFF0EA5A4, 0xFFE8456B, 0xFFE0A100, 0xFF5B6472};
    static final String[] ACCENT_NAMES = {"Orange", "Ocean", "Teal", "Rose", "Amber", "Graphite"};

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String mode(Context c) { return sp(c).getString("mode", MODE_AUTO); }
    static void setMode(Context c, String v) { sp(c).edit().putString("mode", v).apply(); bump(c); }

    static int accent(Context c) { return sp(c).getInt("accent", ACCENTS[0]); }
    static void setAccent(Context c, int v) { sp(c).edit().putInt("accent", v).apply(); bump(c); }

    static String glass(Context c) { return sp(c).getString("glass", GLASS_SOFT); }
    static void setGlass(Context c, String v) { sp(c).edit().putString("glass", v).apply(); bump(c); }

    static String dial(Context c) { return sp(c).getString("dial", DIAL_ORBIT); }
    static void setDial(Context c, String v) { sp(c).edit().putString("dial", v).apply(); bump(c); }

    static String recording(Context c) { return sp(c).getString("recording", REC_OFF); }
    static void setRecording(Context c, String v) { sp(c).edit().putString("recording", v).apply(); }

    static boolean notePrompt(Context c) { return sp(c).getBoolean("note_prompt", true); }
    static void setNotePrompt(Context c, boolean v) { sp(c).edit().putBoolean("note_prompt", v).apply(); }

    static boolean haptics(Context c) { return sp(c).getBoolean("haptics", true); }
    static void setHaptics(Context c, boolean v) { sp(c).edit().putBoolean("haptics", v).apply(); }

    static boolean dialTones(Context c) { return sp(c).getBoolean("tones", true); }
    static void setDialTones(Context c, boolean v) { sp(c).edit().putBoolean("tones", v).apply(); }

    static boolean onboarded(Context c) { return sp(c).getBoolean("onboarded", false); }
    static void setOnboarded(Context c) { sp(c).edit().putBoolean("onboarded", true).apply(); }

    static boolean recordingWarned(Context c) { return sp(c).getBoolean("rec_warned", false); }
    static void setRecordingWarned(Context c) { sp(c).edit().putBoolean("rec_warned", true).apply(); }

    /** Changes every time something visual changes, so open screens know to redraw. */
    static int themeVersion(Context c) { return sp(c).getInt("theme_v", 0); }
    private static void bump(Context c) { sp(c).edit().putInt("theme_v", themeVersion(c) + 1).apply(); }

    // SIM remembered per number (stored by the last 9 digits).
    static String simFor(Context c, String number) { return sp(c).getString("sim_" + Data.key(number), null); }
    static void setSimFor(Context c, String number, String accountId) {
        if (accountId == null) sp(c).edit().remove("sim_" + Data.key(number)).apply();
        else sp(c).edit().putString("sim_" + Data.key(number), accountId).apply();
    }

    static List<String> quickReplies(Context c) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString("replies", null);
        if (raw != null) {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            out.add("Can't talk right now. I'll call you back.");
            out.add("I'm on my way.");
            out.add("I'm in a meeting. Can you text me?");
            out.add("Call you in a few minutes.");
        }
        return out;
    }

    static void setQuickReplies(Context c, List<String> replies) {
        JSONArray a = new JSONArray();
        for (String r : replies) if (r != null && !r.trim().isEmpty()) a.put(r.trim());
        sp(c).edit().putString("replies", a.toString()).apply();
    }
}
