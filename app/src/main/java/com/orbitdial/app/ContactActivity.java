package com.orbitdial.app;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One person: photo, numbers, quick actions, ringtone, gesture, notes, recordings, history, block. */
public class ContactActivity extends BaseActivity {
    private static final int PICK_PHOTO = 31, PICK_RINGTONE = 32, REQ_WRITE = 33;

    private long contactId;
    private String number;
    private Data.Contact contact;
    private LinearLayout body;
    private Stores.GestureStore gestures;
    private MediaPlayer player;
    private String playing;
    private Runnable afterWritePermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        contactId = getIntent().getLongExtra("id", -1);
        number = getIntent().getStringExtra("number");
        gestures = new Stores.GestureStore(this);
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(24));
        sv.addView(body);
        setContentView(sv);
        Ui.applyWindow(this, sv);
        resolve();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // picks up edits made in the contacts app
    }

    private void refresh() {
        Data.IO.execute(() -> {
            Data.loadAll(getApplicationContext());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                resolve();
                render();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }

    private void resolve() {
        contact = contactId >= 0 ? Data.byId(contactId) : null;
        if (contact == null && number != null) contact = Data.find(number);
        if (contact != null) {
            contactId = contact.id;
            if (number == null || number.isEmpty()) number = contact.primary();
        }
        if (number == null) number = "";
    }

    private String fmt(String n) {
        String f = PhoneNumberUtils.formatNumber(n, Actions.country(this));
        return f != null ? f : n;
    }

    private void render() {
        body.removeAllViews();
        boolean saved = contact != null;
        String name = saved ? contact.name : fmt(number);

        // Top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, 48, 0, Ui.text, "Back", v -> finish()));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (saved) {
            bar.addView(Ui.iconButton(this, contact.starred ? R.drawable.ic_star : R.drawable.ic_star_border, 48, 0,
                    contact.starred ? Ui.accent : Ui.text, contact.starred ? "Remove from favourites" : "Add to favourites",
                    v -> withWrite(this::toggleStar)));
            bar.addView(Ui.iconButton(this, R.drawable.ic_edit, 48, 0, Ui.text, "Edit contact",
                    v -> Actions.editContact(this, contact.id, contact.lookupKey)));
        }
        body.addView(bar);

        // Photo and name
        FrameLayout photoBox = new FrameLayout(this);
        AvatarView av = new AvatarView(this);
        av.set(saved ? contact.name : null, saved ? contact.lookupKey : number);
        if (saved) Data.bindPhoto(this, av, contact.photoUri != null ? contact.photoUri : contact.thumbUri);
        photoBox.addView(av, new FrameLayout.LayoutParams(dp(112), dp(112)));
        if (saved) {
            ImageView cam = Ui.icon(this, R.drawable.ic_add_photo, Ui.onAccent);
            cam.setPadding(dp(8), dp(8), dp(8), dp(8));
            cam.setBackground(Ui.oval(Ui.accent));
            photoBox.addView(cam, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM | Gravity.END));
            photoBox.setContentDescription("Change photo");
            photoBox.setOnClickListener(Ui.tap(v -> photoMenu()));
            Ui.press(photoBox);
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(112), dp(112));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(4);
        body.addView(photoBox, plp);

        TextView title = Ui.text(this, name, 26, Ui.text, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(14), 0, 0);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));
        String subText = saved ? (contact.nickname.isEmpty() ? null : contact.nickname) : "Not in your contacts";
        Stores.Gesture g = gestures.forNumber(number);
        if (g != null) subText = (subText == null ? "" : subText + "  ·  ") + "Gesture " + g.prettyPattern();
        if (Phonebook.isBlocked(this, number)) subText = "Blocked";
        if (subText != null) {
            TextView sub = Ui.text(this, subText, 14, Phonebook.isBlocked(this, number) ? Ui.danger : Ui.muted, false);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(6), 0, 0);
            body.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }

        // Quick actions
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(20), 0, dp(4));
        addTile(actions, R.drawable.ic_call, "Call", Ui.accent, Ui.onAccent, v -> Actions.call(this, number));
        addTile(actions, R.drawable.ic_message, "Message", Ui.glassStrong, Ui.text, v -> Actions.sms(this, number));
        if (Phonebook.whatsAppPackage(this) != null) {
            addTile(actions, R.drawable.ic_chat, "WhatsApp", Ui.glassStrong, Ui.text, v -> whatsApp());
        }
        addTile(actions, R.drawable.ic_notes, "Add note", Ui.glassStrong, Ui.text, v -> addNote());
        body.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        // Numbers
        body.addView(Ui.section(this, saved && contact.numbers.size() > 1 ? "Numbers" : "Number"));
        List<View> numRows = new ArrayList<>();
        List<Data.Number> numbers = saved ? contact.numbers : new ArrayList<>();
        if (!saved) numbers.add(new Data.Number(number, ""));
        for (Data.Number n : numbers) numRows.add(numberRow(n));
        body.addView(Ui.card(this, numRows.toArray(new View[0])));

        // Personalise
        body.addView(Ui.section(this, "Personalise"));
        List<View> rows = new ArrayList<>();
        if (saved) {
            rows.add(Ui.row(this, R.drawable.ic_music, "Ringtone", ringtoneName(), chevron(), v -> withWrite(this::pickRingtone)));
        }
        rows.add(Ui.row(this, R.drawable.ic_gesture, "Gesture Speed Dial",
                g != null ? "Draw " + g.prettyPattern() + " on the keypad to call" : "Draw a path on the keypad to call instantly",
                chevron(), v -> gestureMenu()));
        if (!saved) rows.add(Ui.row(this, R.drawable.ic_person_add, "Add to contacts", null, chevron(), v -> Actions.addContact(this, number)));
        body.addView(Ui.card(this, rows.toArray(new View[0])));

        // Notes and recordings
        List<Notes.Item> items = Notes.forNumber(this, number);
        if (!items.isEmpty()) {
            body.addView(Ui.section(this, "Notes and recordings"));
            List<View> nrows = new ArrayList<>();
            for (Notes.Item it : items) nrows.add(it.kind == Notes.NOTE ? noteRow(it) : recordingRow(it));
            body.addView(Ui.card(this, nrows.toArray(new View[0])));
        }

        // History
        List<Data.Call> history = new ArrayList<>();
        for (Data.Call c : Data.calls) {
            boolean mine = saved ? Data.find(c.number) == contact : Data.sameNumber(c.number, number);
            if (mine) history.add(c);
            if (history.size() >= 8) break;
        }
        if (!history.isEmpty()) {
            body.addView(Ui.section(this, "Recent calls"));
            List<View> hrows = new ArrayList<>();
            for (Data.Call c : history) hrows.add(historyRow(c));
            body.addView(Ui.card(this, hrows.toArray(new View[0])));
        }

        // Block
        boolean blocked = Phonebook.isBlocked(this, number);
        LinearLayout blockRow = Ui.row(this, R.drawable.ic_block, blocked ? "Unblock number" : "Block number",
                blocked ? "Calls from this number are blocked" : "Calls from this number won't ring", null, v -> toggleBlock(blocked));
        if (!blocked) {
            ((TextView) ((LinearLayout) blockRow.getChildAt(1)).getChildAt(0)).setTextColor(Ui.danger);
            ((ImageView) blockRow.getChildAt(0)).setImageTintList(ColorStateList.valueOf(Ui.danger));
        }
        LinearLayout danger = Ui.card(this, blockRow);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(18);
        body.addView(danger, dlp);
    }

    private View chevron() {
        ImageView i = Ui.icon(this, R.drawable.ic_chevron, Ui.muted);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        return i;
    }

    private void addTile(LinearLayout parent, int icon, String label, int bg, int tint, View.OnClickListener l) {
        parent.addView(Ui.tile(this, icon, label, bg, tint, 56, l), new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private View numberRow(Data.Number n) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(14), dp(8), dp(4), dp(8));
        row.setBackground(Ui.rippleRound(this, 0, 16));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.oneLine(Ui.text(this, fmt(n.number), 17, Ui.text, false)));
        String sub = n.label.isEmpty() ? "Phone" : n.label;
        String sim = Prefs.simFor(this, n.number);
        if (sim != null) {
            for (PhoneAccountHandle h : Actions.sims(this)) if (sim.equals(h.getId())) sub += "  ·  " + Actions.simLabel(this, h);
        }
        TextView s = Ui.oneLine(Ui.text(this, sub, 13, Ui.muted, false));
        s.setPadding(0, dp(4), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(Ui.iconButton(this, R.drawable.ic_message, 48, 0, Ui.text, "Message " + n.number, v -> Actions.sms(this, n.number)));
        row.addView(Ui.iconButton(this, R.drawable.ic_call, 48, 0, Ui.accent, "Call " + n.number, v -> Actions.call(this, n.number)));
        row.setOnClickListener(Ui.tap(v -> Actions.call(this, n.number)));
        row.setOnLongClickListener(v -> {
            List<Ui.Action> acts = new ArrayList<>();
            acts.add(new Ui.Action(R.drawable.ic_copy, "Copy number", false, () -> Actions.copy(this, n.number)));
            if (Actions.sims(this).size() > 1) {
                acts.add(new Ui.Action(R.drawable.ic_sim, "Always call with...", false, () -> Actions.chooseSim(this, null, h -> {
                    Prefs.setSimFor(this, n.number, h.getId());
                    render();
                })));
                if (Prefs.simFor(this, n.number) != null) {
                    acts.add(new Ui.Action(R.drawable.ic_sim, "Ask which SIM each time", false, () -> {
                        Prefs.setSimFor(this, n.number, null);
                        render();
                    }));
                }
            }
            Ui.actions(this, fmt(n.number), acts.toArray(new Ui.Action[0]));
            return true;
        });
        Ui.press(row);
        return row;
    }

    private View noteRow(Notes.Item it) {
        LinearLayout row = Ui.row(this, R.drawable.ic_notes, it.text, when(it), null, v -> Ui.actions(this, "Note",
                new Ui.Action(R.drawable.ic_copy, "Copy", false, () -> Actions.copy(this, it.text)),
                new Ui.Action(R.drawable.ic_delete, "Delete note", true, () -> {
                    Notes.remove(this, number, it);
                    render();
                })));
        ((ImageView) row.getChildAt(0)).setImageTintList(ColorStateList.valueOf(Ui.accent));
        return row;
    }

    private View recordingRow(Notes.Item it) {
        boolean exists = new File(it.text).exists();
        boolean isPlaying = it.text.equals(playing);
        ImageView play = Ui.iconButton(this, isPlaying ? R.drawable.ic_pause : R.drawable.ic_play, 48,
                Ui.glassStrong, Ui.text, isPlaying ? "Pause" : "Play", v -> togglePlay(it.text));
        LinearLayout row = Ui.row(this, R.drawable.ic_record, "Recording" + (it.length > 0 ? ", " + Ui.duration(it.length) : ""),
                exists ? when(it) : "File was removed", exists ? play : null, v -> Ui.actions(this, "Recording",
                        new Ui.Action(R.drawable.ic_delete, "Delete recording", true, () -> {
                            if (it.text.equals(playing)) stopPlayback();
                            Notes.remove(this, number, it);
                            render();
                        })));
        ((ImageView) row.getChildAt(0)).setImageTintList(ColorStateList.valueOf(Ui.danger));
        return row;
    }

    private String when(Notes.Item it) {
        return DateUtils.formatDateTime(this, it.created, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private View historyRow(Data.Call c) {
        int icon = c.missed() ? R.drawable.ic_call_missed : c.outgoing() ? R.drawable.ic_call_out : R.drawable.ic_call_in;
        String kind = c.missed() ? "Missed" : c.outgoing() ? "Outgoing" : "Incoming";
        String d = Ui.duration(c.duration);
        LinearLayout row = Ui.row(this, icon, kind + (d.isEmpty() ? "" : ", " + d), Ui.dayLabel(c.date) + ", " + Ui.ago(c.date), null, null);
        ((ImageView) row.getChildAt(0)).setImageTintList(ColorStateList.valueOf(c.missed() ? Ui.danger : Ui.muted));
        return row;
    }

    // ------------------------------------------------------------------ actions

    private void whatsApp() {
        long cid = contact != null ? contact.id : -1;
        boolean canCall = Phonebook.whatsAppCallDataId(this, cid, number) >= 0;
        if (!canCall) {
            Actions.whatsAppChat(this, number);
            return;
        }
        Ui.actions(this, "WhatsApp",
                new Ui.Action(R.drawable.ic_chat, "Open chat", false, () -> Actions.whatsAppChat(this, number)),
                new Ui.Action(R.drawable.ic_call, "Voice call", false, () -> Actions.whatsAppCall(this, cid, number)));
    }

    private void addNote() {
        Ui.input(this, "Note", "What do you want to remember?", null, true, "Save", text -> {
            if (text.isEmpty()) return;
            Notes.add(this, number, Notes.NOTE, 0, text, 0);
            render();
            snack("Note saved.");
        });
    }

    private void gestureMenu() {
        Stores.Gesture g = gestures.forNumber(number);
        String name = contact != null ? contact.name : fmt(number);
        Runnable assign = () -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_ASSIGN_NUMBER, number);
            i.putExtra(MainActivity.EXTRA_ASSIGN_NAME, name);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        };
        if (g == null) {
            assign.run();
            return;
        }
        Ui.actions(this, "Gesture " + g.prettyPattern(),
                new Ui.Action(R.drawable.ic_gesture, "Draw a new path", false, assign),
                new Ui.Action(R.drawable.ic_delete, "Remove gesture", true, () -> {
                    gestures.remove(g.pattern);
                    render();
                }));
    }

    private void toggleStar() {
        try {
            Phonebook.setStarred(this, contact.id, !contact.starred);
            contact.starred = !contact.starred;
            render();
            snack(contact.starred ? "Added to favourites." : "Removed from favourites.");
        } catch (Phonebook.Problem p) {
            snack(p.getMessage());
        }
    }

    private void toggleBlock(boolean blocked) {
        Runnable go = () -> {
            try {
                if (blocked) Phonebook.unblock(this, number);
                else Phonebook.block(this, number);
                render();
                snack(blocked ? "Unblocked." : "Blocked. Calls from this number won't ring.");
            } catch (Phonebook.Problem p) {
                Ui.snack(this, p.getMessage(), Actions.isDefaultPhoneApp(this) ? null : "Set up", () -> Actions.askToBeDefault(this));
            }
        };
        if (blocked) go.run();
        else Ui.confirm(this, "Block " + (contact != null ? contact.name : fmt(number)) + "?",
                "You won't get calls or texts from this number. You can unblock it any time.", "Block", true, go);
    }

    /** Runs the action once Orbit Dial is allowed to edit contacts. */
    private void withWrite(Runnable r) {
        if (Actions.has(this, Manifest.permission.WRITE_CONTACTS)) {
            r.run();
        } else {
            afterWritePermission = r;
            requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS}, REQ_WRITE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_WRITE) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && afterWritePermission != null) {
            afterWritePermission.run();
        } else {
            Ui.snack(this, "Orbit Dial needs permission to edit contacts for this.", "Settings", () -> Actions.openAppSettings(this));
        }
        afterWritePermission = null;
    }

    private void photoMenu() {
        boolean has = contact.photoUri != null || contact.thumbUri != null;
        Ui.actions(this, "Contact photo",
                new Ui.Action(R.drawable.ic_add_photo, has ? "Choose a new photo" : "Add a photo", false, () -> withWrite(this::pickPhoto)),
                has ? new Ui.Action(R.drawable.ic_delete, "Remove photo", true, () -> withWrite(() -> {
                    try {
                        Phonebook.removePhoto(this, contact.id);
                        Data.forgetPhoto(contact.photoUri);
                        Data.forgetPhoto(contact.thumbUri);
                        refresh();
                    } catch (Phonebook.Problem p) {
                        snack(p.getMessage());
                    }
                })) : null);
    }

    private void pickPhoto() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(i, PICK_PHOTO);
        } catch (RuntimeException e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK_PHOTO);
            } catch (RuntimeException e2) {
                snack("No gallery app found on this phone.");
            }
        }
    }

    private String ringtoneName() {
        if (contact == null || contact.ringtone == null) return "Phone default";
        try {
            Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(contact.ringtone));
            if (r != null) return r.getTitle(this);
        } catch (RuntimeException ignored) {
        }
        return "Custom";
    }

    private void pickRingtone() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone for " + contact.name);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                contact.ringtone != null ? Uri.parse(contact.ringtone) : Settings.System.DEFAULT_RINGTONE_URI);
        try {
            startActivityForResult(i, PICK_RINGTONE);
        } catch (RuntimeException e) {
            snack("This phone doesn't have a ringtone picker.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || contact == null) return;
        if (req == PICK_PHOTO && data.getData() != null) {
            Uri picked = data.getData();
            long id = contact.id;
            String oldPhoto = contact.photoUri, oldThumb = contact.thumbUri;
            snack("Saving photo...");
            Data.IO.execute(() -> {
                String error = null;
                try {
                    Bitmap b = Phonebook.preparePhoto(this, picked);
                    Phonebook.setPhoto(this, id, b);
                } catch (Phonebook.Problem p) {
                    error = p.getMessage();
                }
                Data.forgetPhoto(oldPhoto);
                Data.forgetPhoto(oldThumb);
                Data.loadAll(getApplicationContext());
                String err = error;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    resolve();
                    render();
                    snack(err != null ? err : "Photo saved. It will show when " + contact.name.split("\\s+")[0] + " calls.");
                });
            });
        } else if (req == PICK_RINGTONE) {
            Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            String value = u == null ? "" /* silent */ : Settings.System.DEFAULT_RINGTONE_URI.equals(u) ? null : u.toString();
            try {
                Phonebook.setRingtone(this, contact.id, value);
                contact.ringtone = value;
                render();
                snack(value == null ? "Using your phone's ringtone." : value.isEmpty() ? "Calls from " + contact.name + " will be silent." : "Ringtone set.");
            } catch (Phonebook.Problem p) {
                snack(p.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- playback

    private void togglePlay(String path) {
        if (path.equals(playing) && player != null) {
            if (player.isPlaying()) player.pause();
            else player.start();
            return;
        }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(path);
            player.setOnCompletionListener(mp -> {
                stopPlayback();
                render();
            });
            player.prepare();
            player.start();
            playing = path;
            render();
        } catch (Exception e) {
            stopPlayback();
            snack("Couldn't play this recording.");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
        player = null;
        playing = null;
    }
}
