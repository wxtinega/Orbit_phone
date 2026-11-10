package com.orbitdial.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends BaseActivity {
    private static final int REQ_MIC = 41;
    private LinearLayout body;
    private String pendingRecording;

    @Override
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(28));
        sv.addView(body);
        setContentView(sv);
        Ui.applyWindow(this, sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, 48, 0, Ui.text, "Back", v -> finish()));
        TextView t = Ui.title(this, "Settings");
        t.setPadding(dp(4), 0, 0, 0);
        bar.addView(t);
        body.addView(bar);

        // Phone app
        boolean isDefault = Actions.isDefaultPhoneApp(this);
        TextView setBtn = isDefault ? null : Ui.button(this, "Set", Ui.FILLED, v -> Actions.askToBeDefault(this));
        body.addView(Ui.section(this, "Phone app"));
        body.addView(Ui.card(this, Ui.row(this, R.drawable.ic_call, "Default phone app",
                isDefault ? "T.I Dial shows incoming calls instantly and handles your calls."
                        : "Not set. Incoming call screens, blocking and caller photos need this.", setBtn,
                isDefault ? v -> Actions.openDefaultAppsSettings(this) : null)));

        // Appearance
        body.addView(Ui.section(this, "Appearance"));
        LinearLayout look = new LinearLayout(this);
        look.setOrientation(LinearLayout.VERTICAL);
        look.setPadding(dp(14), dp(12), dp(14), dp(14));
        look.setBackground(Ui.glassCard(this, 18, false));
        look.addView(label("Theme"));
        String mode = Prefs.mode(this);
        look.addView(Ui.segmented(this, new String[]{"Auto", "Light", "Dark"},
                Prefs.MODE_LIGHT.equals(mode) ? 1 : Prefs.MODE_DARK.equals(mode) ? 2 : 0,
                i -> later(() -> Prefs.setMode(this, i == 1 ? Prefs.MODE_LIGHT : i == 2 ? Prefs.MODE_DARK : Prefs.MODE_AUTO))));
        look.addView(label("Accent colour"));
        look.addView(swatches(this, () -> later(() -> { })));
        look.addView(label("Glass effect"));
        String g = Prefs.glass(this);
        look.addView(Ui.segmented(this, new String[]{"Off", "Soft", "Strong"},
                Prefs.GLASS_OFF.equals(g) ? 0 : Prefs.GLASS_STRONG.equals(g) ? 2 : 1,
                i -> later(() -> Prefs.setGlass(this, i == 0 ? Prefs.GLASS_OFF : i == 2 ? Prefs.GLASS_STRONG : Prefs.GLASS_SOFT))));
        TextView glassNote = Ui.text(this, "Glass lets your wallpaper show through, blurred on phones that support it.", 12, Ui.muted, false);
        glassNote.setPadding(dp(2), dp(8), dp(2), 0);
        look.addView(glassNote);
        look.addView(label("Dial pad"));
        look.addView(dialPicker(this, () -> later(() -> { })));
        body.addView(look);

        // Calls
        body.addView(Ui.section(this, "Calls"));
        List<View> rows = new ArrayList<>();
        String rec = Prefs.recording(this);
        LinearLayout recBox = new LinearLayout(this);
        recBox.setOrientation(LinearLayout.VERTICAL);
        recBox.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout recHead = new LinearLayout(this);
        recHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView recIcon = Ui.icon(this, R.drawable.ic_record, Ui.muted);
        LinearLayout.LayoutParams rilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        rilp.rightMargin = dp(16);
        recHead.addView(recIcon, rilp);
        recHead.addView(Ui.text(this, "Call recording", 16, Ui.text, false));
        recBox.addView(recHead);
        TextView recExplain = Ui.text(this, recordingExplanation(rec), 13, Ui.muted, false);
        recExplain.setPadding(0, dp(8), 0, dp(10));
        recBox.addView(recExplain);
        recBox.addView(Ui.segmented(this, new String[]{"Off", "Speaker", "Call audio"},
                Prefs.REC_SPEAKER.equals(rec) ? 1 : Prefs.REC_CALL_AUDIO.equals(rec) ? 2 : 0, i -> {
                    String v = i == 1 ? Prefs.REC_SPEAKER : i == 2 ? Prefs.REC_CALL_AUDIO : Prefs.REC_OFF;
                    recExplain.setText(recordingExplanation(v));
                    setRecording(v);
                }));
        rows.add(recBox);
        rows.add(Ui.row(this, R.drawable.ic_notes, "Note after calls", "Offer to add a note when a call ends",
                Ui.toggle(this, Prefs.notePrompt(this), (b, v) -> Prefs.setNotePrompt(this, v)), null));
        rows.add(Ui.row(this, R.drawable.ic_reply, "Quick replies", "Text sent when you decline with a message", chevron(), v -> editReplies()));
        rows.add(Ui.row(this, R.drawable.ic_block, "Blocked numbers", null, chevron(), v -> showBlocked()));
        rows.add(Ui.row(this, R.drawable.ic_music, "Phone ringtone", "Ringtones per contact are on each contact's page", chevron(), v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
            } catch (RuntimeException e) {
                snack("Couldn't open sound settings.");
            }
        }));
        body.addView(Ui.card(this, rows.toArray(new View[0])));

        // Feel
        body.addView(Ui.section(this, "Touch and sound"));
        body.addView(Ui.card(this,
                Ui.row(this, R.drawable.ic_dialpad, "Dial pad tones", null,
                        Ui.toggle(this, Prefs.dialTones(this), (b, v) -> Prefs.setDialTones(this, v)), null),
                Ui.row(this, R.drawable.ic_phone, "Vibrate on touch", null,
                        Ui.toggle(this, Prefs.haptics(this), (b, v) -> Prefs.setHaptics(this, v)), null)));

        // Gestures
        body.addView(Ui.section(this, "Gesture Speed Dial"));
        Stores.GestureStore gs = new Stores.GestureStore(this);
        List<View> grows = new ArrayList<>();
        for (Stores.Gesture gg : gs.all()) {
            grows.add(Ui.row(this, R.drawable.ic_gesture, gg.name, "Draw " + gg.prettyPattern(),
                    Ui.iconButton(this, R.drawable.ic_delete, 48, 0, Ui.muted, "Remove gesture for " + gg.name, v -> {
                        gs.remove(gg.pattern);
                        render();
                    }), null));
        }
        if (grows.isEmpty()) {
            grows.add(Ui.row(this, R.drawable.ic_gesture, "No gestures yet",
                    "On the keypad, drag across 3 or more keys without lifting your finger, then pick who it calls.", null, null));
        }
        body.addView(Ui.card(this, grows.toArray(new View[0])));

        // About
        body.addView(Ui.section(this, "About"));
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        body.addView(Ui.card(this,
                Ui.row(this, R.drawable.ic_settings, "App permissions", null, chevron(), v -> Actions.openAppSettings(this)),
                Ui.row(this, R.drawable.ic_phone, "T.I Dial " + version,
                        "Notes and recordings are stored only on this phone.", null, null)));
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 14, Ui.text, true);
        t.setPadding(dp(2), dp(14), 0, dp(8));
        if (s.equals("Theme")) t.setPadding(dp(2), dp(2), 0, dp(8));
        return t;
    }

    private View chevron() {
        ImageView i = Ui.icon(this, R.drawable.ic_chevron, Ui.muted);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        return i;
    }

    /** Lets the tap animation finish before the screen redraws with the new look. */
    private void later(Runnable r) {
        body.postDelayed(() -> {
            r.run();
            if (!isFinishing()) recreate();
        }, 180);
    }

    static LinearLayout swatches(Activity a, Runnable after) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int current = Prefs.accent(a);
        for (int i = 0; i < Prefs.ACCENTS.length; i++) {
            int color = Prefs.ACCENTS[i];
            FrameLayout f = new FrameLayout(a);
            View dot = new View(a);
            dot.setBackground(Ui.oval(color));
            f.addView(dot, new FrameLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36), Gravity.CENTER));
            if (color == current) {
                ImageView check = Ui.icon(a, R.drawable.ic_check, 0xFFFFFFFF);
                f.addView(check, new FrameLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20), Gravity.CENTER));
            }
            f.setContentDescription(Prefs.ACCENT_NAMES[i] + (color == current ? ", selected" : ""));
            f.setOnClickListener(Ui.tap(v -> {
                Prefs.setAccent(a, color);
                after.run();
            }));
            Ui.press(f);
            row.addView(f, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f));
        }
        return row;
    }

    static LinearLayout dialPicker(Activity a, Runnable after) {
        LinearLayout row = new LinearLayout(a);
        String current = Prefs.dial(a);
        String[] ids = {Prefs.DIAL_ORBIT, Prefs.DIAL_GRID};
        String[] names = {"Orbit", "Classic"};
        for (int i = 0; i < 2; i++) {
            String id = ids[i];
            boolean on = id.equals(current);
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10));
            android.graphics.drawable.GradientDrawable bg = Ui.round(on ? Ui.alpha(Ui.accent, Ui.dark ? 0x26 : 0x18) : Ui.field, Ui.dp(a, 18));
            if (on) bg.setStroke(Ui.dp(a, 2), Ui.accent);
            card.setBackground(bg);
            FrameLayout previewBox = new FrameLayout(a);
            OrbitDialView preview = new OrbitDialView(a);
            preview.setStyle(i == 0 ? OrbitDialView.ORBIT : OrbitDialView.GRID);
            previewBox.addView(preview, new FrameLayout.LayoutParams(-1, -1));
            View shield = new View(a); // the preview is just a picture here
            previewBox.addView(shield, new FrameLayout.LayoutParams(-1, -1));
            card.addView(previewBox, new LinearLayout.LayoutParams(-1, Ui.dp(a, 150)));
            TextView t = Ui.text(a, names[i], 14, on ? Ui.accent : Ui.text, true);
            t.setPadding(0, Ui.dp(a, 8), 0, 0);
            card.addView(t);
            View.OnClickListener pick = Ui.tap(v -> {
                Prefs.setDial(a, id);
                after.run();
            });
            card.setOnClickListener(pick);
            shield.setOnClickListener(pick);
            Ui.press(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : Ui.dp(a, 6), 0, i == 0 ? Ui.dp(a, 6) : 0, 0);
            row.addView(card, lp);
        }
        return row;
    }

    private String recordingExplanation(String mode) {
        if (Prefs.REC_SPEAKER.equals(mode)) {
            return "Records through the microphone with the call on speaker, so both voices are captured. Works on most phones.";
        }
        if (Prefs.REC_CALL_AUDIO.equals(mode)) {
            return "Tries to record the call directly. Many phones block this, and T.I Dial falls back to the microphone.";
        }
        return "A record button appears during calls when this is on. Notes work either way.";
    }

    private void setRecording(String v) {
        if (Prefs.REC_OFF.equals(v)) {
            Prefs.setRecording(this, v);
            return;
        }
        Runnable enable = () -> {
            if (Actions.has(this, Manifest.permission.RECORD_AUDIO)) {
                Prefs.setRecording(this, v);
            } else {
                pendingRecording = v;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
        };
        if (!Prefs.recordingWarned(this)) {
            Ui.confirm(this, "Before you record",
                    "Recording laws differ by country. In many places you must tell the other person you're recording. "
                            + "Recordings stay on this phone.", "Turn on", false, () -> {
                        Prefs.setRecordingWarned(this);
                        enable.run();
                    });
            render();
        } else {
            enable.run();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && pendingRecording != null) {
                Prefs.setRecording(this, pendingRecording);
            } else {
                Ui.snack(this, "Recording needs the microphone permission.", "Settings", () -> Actions.openAppSettings(this));
            }
            pendingRecording = null;
            render();
        }
    }

    private void editReplies() {
        List<String> replies = Prefs.quickReplies(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.app.Dialog[] d = new android.app.Dialog[1];
        for (int i = 0; i < replies.size(); i++) {
            final int idx = i;
            list.addView(Ui.press(Ui.row(this, R.drawable.ic_reply, replies.get(i), null,
                    Ui.iconButton(this, R.drawable.ic_delete, 48, 0, Ui.muted, "Delete reply", v -> {
                        d[0].dismiss();
                        List<String> copy = new ArrayList<>(replies);
                        copy.remove(idx);
                        if (copy.isEmpty()) copy.add("Can't talk right now. I'll call you back.");
                        Prefs.setQuickReplies(this, copy);
                        editReplies();
                    }), v -> {
                        d[0].dismiss();
                        Ui.input(this, "Edit reply", "Message", replies.get(idx), true, "Save", text -> {
                            List<String> copy = new ArrayList<>(replies);
                            if (text.isEmpty()) copy.remove(idx);
                            else copy.set(idx, text);
                            Prefs.setQuickReplies(this, copy);
                            editReplies();
                        });
                    })));
        }
        list.addView(Ui.press(Ui.row(this, R.drawable.ic_add, "Add a reply", null, null, v -> {
            d[0].dismiss();
            Ui.input(this, "New reply", "Message", null, true, "Add", text -> {
                if (text.isEmpty()) return;
                List<String> copy = new ArrayList<>(replies);
                copy.add(text);
                Prefs.setQuickReplies(this, copy);
                editReplies();
            });
        })));
        d[0] = Ui.sheet(this, "Quick replies", list);
        d[0].show();
    }

    private void showBlocked() {
        List<String> numbers;
        try {
            numbers = Phonebook.blocked(this);
        } catch (Phonebook.Problem p) {
            Ui.snack(this, p.getMessage(), Actions.isDefaultPhoneApp(this) ? null : "Set up", () -> Actions.askToBeDefault(this));
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.app.Dialog[] d = new android.app.Dialog[1];
        if (numbers.isEmpty()) {
            TextView t = Ui.text(this, "You haven't blocked anyone. Block a number from its contact page or from Recents.", 15, Ui.muted, false);
            t.setPadding(dp(6), dp(4), dp(6), dp(12));
            list.addView(t);
        }
        for (String n : numbers) {
            String name = Data.nameFor(n);
            TextView unblock = Ui.button(this, "Unblock", Ui.TONAL, v -> {
                try {
                    Phonebook.unblock(this, n);
                    d[0].dismiss();
                    showBlocked();
                } catch (Phonebook.Problem p) {
                    snack(p.getMessage());
                }
            });
            list.addView(Ui.row(this, R.drawable.ic_block, name != null ? name : n, name != null ? n : null, unblock, null));
        }
        d[0] = Ui.sheet(this, "Blocked numbers", list);
        d[0].show();
    }

    static void tintFirst(LinearLayout row, int color) {
        ((ImageView) row.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
    }
}
