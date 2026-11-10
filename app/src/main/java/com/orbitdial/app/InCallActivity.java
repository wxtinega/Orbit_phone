package com.orbitdial.app;

import android.app.KeyguardManager;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The call screen: incoming, in progress, keypad, and a short summary when the call ends. */
public class InCallActivity extends BaseActivity implements CallManager.Listener {
    private static final int PANEL_INCOMING = 0, PANEL_CONTROLS = 1, PANEL_KEYPAD = 2, PANEL_ENDED = 3;

    private ImageView photoBg;
    private View photoScrim;
    private LinearLayout holdBanner, statusRow;
    private TextView status, hdBadge, nameView, subView;
    private LivelyBackground recDot;
    private AvatarView avatar;
    private TextView manageConf;
    private FrameLayout panelBox;
    private int panel = -1;
    private TextView dtmfView;
    private Call shown;
    private String shownPhoto;
    private boolean simAsked, keypadWanted, ended, userTouched;
    private PowerManager.WakeLock proximity;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            status.postDelayed(this, 1000);
        }
    };
    private final Runnable autoClose = this::finishAndRemoveTask;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showOverLockScreen();
        FrameLayout root = new FrameLayout(this);
        photoBg = new ImageView(this);
        photoBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photoBg.setAlpha(0f);
        root.addView(photoBg, new FrameLayout.LayoutParams(-1, -1));
        photoScrim = new View(this);
        photoScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x26000000, 0x40000000, 0xE6000000}));
        photoScrim.setAlpha(0f);
        root.addView(photoScrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(20), dp(8), dp(20), dp(16));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        holdBanner = new LinearLayout(this);
        holdBanner.setGravity(Gravity.CENTER_VERTICAL);
        holdBanner.setBackground(Ui.glassCard(this, 20, true));
        holdBanner.setPadding(dp(14), dp(6), dp(6), dp(6));
        holdBanner.setVisibility(View.GONE);
        col.addView(holdBanner, new LinearLayout.LayoutParams(-1, -2));

        avatar = new AvatarView(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(84), dp(84));
        alp.topMargin = dp(36);
        col.addView(avatar, alp);
        nameView = Ui.text(this, "", 34, Ui.text, false);
        nameView.setGravity(Gravity.CENTER);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setLetterSpacing(-0.01f);
        nameView.setPadding(0, dp(14), 0, 0);
        nameView.setShadowLayer(dp(8), 0, 0, 0x66000000);
        col.addView(nameView, new LinearLayout.LayoutParams(-1, -2));

        statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER);
        recDot = new LivelyBackground(this, Ui.danger);
        recDot.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(14), dp(14));
        rlp.rightMargin = dp(8);
        statusRow.addView(recDot, rlp);
        status = Ui.oneLine(Ui.text(this, "", 17, Ui.muted, false));
        statusRow.addView(status);
        hdBadge = Ui.text(this, "HD", 11, Ui.text, true);
        hdBadge.setBackground(Ui.round(Ui.glassStrong, dp(6)));
        hdBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        hdBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.leftMargin = dp(8);
        statusRow.addView(hdBadge, hlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(6);
        col.addView(statusRow, slp);

        subView = Ui.oneLine(Ui.text(this, "", 15, Ui.muted, false));
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(0, dp(4), 0, 0);
        col.addView(subView, new LinearLayout.LayoutParams(-1, -2));
        manageConf = Ui.button(this, "Manage conference", Ui.TONAL, v -> manageConference());
        manageConf.setVisibility(View.GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = dp(12);
        col.addView(manageConf, mlp);

        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        panelBox = new FrameLayout(this);
        col.addView(panelBox, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        Ui.applyWindow(this, col);
        keypadWanted = getIntent().getBooleanExtra("keypad", false);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "orbitdial:proximity");
        }
        handleAnswer(getIntent());
    }

    @SuppressWarnings("deprecation")
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("keypad", false)) keypadWanted = true;
        handleAnswer(intent);
        render();
    }

    private void handleAnswer(Intent i) {
        if (i != null && i.getBooleanExtra("answer", false)) {
            i.removeExtra("answer");
            Call r = CallManager.ringing();
            if (r != null) {
                Call active = CallManager.find(Call.STATE_ACTIVE);
                if (active != null) active.hold();
                CallManager.answer(r);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallManager.addListener(this);
        render();
        status.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        CallManager.removeListener(this);
        status.removeCallbacks(ticker);
        setProximity(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        status.removeCallbacks(autoClose);
        setProximity(false);
        if (!CallManager.hasCalls()) CallManager.lastEnded = null;
    }

    @Override
    public void onCallsChanged() {
        if (!isFinishing()) render();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ended && ev.getAction() == MotionEvent.ACTION_DOWN) {
            userTouched = true;
            status.removeCallbacks(autoClose);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume keys silence the ringer, like on any phone.
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && CallManager.ringing() != null) {
            try {
                getSystemService(TelecomManager.class).silenceRinger();
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (panel == PANEL_KEYPAD) {
            keypadWanted = false;
            render();
        } else if (ended || !CallManager.hasCalls()) {
            finishAndRemoveTask();
        } else {
            moveTaskToBack(true); // the call keeps going; tap the notification or the banner to return
        }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        Call c = CallManager.primary();
        if (c == null) {
            if (!ended) showEnded();
            return;
        }
        if (ended) {
            ended = false;
            status.removeCallbacks(autoClose);
            panel = -1;
        }
        int s = CallManager.state(c);
        CallManager.Info info = CallManager.info(c);
        boolean conf = CallManager.isConference(c);

        // Who
        String name = conf ? "Conference call" : info.hidden ? "Private number" : info.name != null ? info.name : format(info.number);
        nameView.setText(name);
        String sub;
        if (conf) {
            int n = c.getChildren().size();
            sub = n > 0 ? n + " people" : "";
        } else if (info.hidden) {
            sub = "";
        } else if (info.name != null) {
            sub = (info.label != null && !info.label.isEmpty() ? info.label + "  ·  " : "") + format(info.number);
        } else {
            sub = "Not in your contacts";
        }
        String sim = simName(c);
        if (sim != null && s == Call.STATE_RINGING) sub = sub.isEmpty() ? "To " + sim : sub + "  ·  " + sim;
        subView.setText(sub);
        subView.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        manageConf.setVisibility(conf && c.getChildren().size() > 0 ? View.VISIBLE : View.GONE);
        if (c != shown) {
            avatar.set(conf || info.hidden ? null : info.name, info.number);
            shown = c;
            shownPhoto = null;
        }
        if (info.photo != null && info.photoUri != null && !info.photoUri.equals(shownPhoto)) {
            shownPhoto = info.photoUri;
            avatar.setPhoto(info.photo);
            photoBg.setImageBitmap(info.photo);
            // Contact poster: the photo fills the screen, like on an iPhone.
            boolean big = info.photo.getWidth() >= 320;
            if (big) {
                avatar.setVisibility(View.INVISIBLE);
                photoBg.setRenderEffect(null);
            } else if (Build.VERSION.SDK_INT >= 31) {
                photoBg.setRenderEffect(RenderEffect.createBlurEffect(dp(30), dp(30), Shader.TileMode.CLAMP));
            }
            if (big || Build.VERSION.SDK_INT >= 31) {
                photoBg.animate().alpha(1f).setDuration(300).start();
                photoScrim.animate().alpha(1f).setDuration(300).start();
            }
        } else if (info.photo == null && shownPhoto == null) {
            avatar.setVisibility(View.VISIBLE);
            photoBg.setAlpha(0f);
            photoScrim.setAlpha(0f);
        }

        updateStatus();
        renderHoldBanner(c);

        if (s == Call.STATE_SELECT_PHONE_ACCOUNT && !simAsked) {
            simAsked = true;
            Actions.chooseSim(this, info.number, h -> c.phoneAccountSelected(h, false));
        }

        boolean ringing = s == Call.STATE_RINGING || (Build.VERSION.SDK_INT >= 29 && s == Call.STATE_SIMULATED_RINGING);
        int want = ringing ? PANEL_INCOMING : keypadWanted ? PANEL_KEYPAD : PANEL_CONTROLS;
        setPanel(want, c);
        setProximity(!ringing && want != PANEL_KEYPAD && CallManager.route() == CallAudioState.ROUTE_EARPIECE);
    }

    private String format(String n) {
        if (n == null) return "";
        String f = android.telephony.PhoneNumberUtils.formatNumber(n, Actions.country(this));
        return f != null ? f : n;
    }

    private String simName(Call c) {
        PhoneAccountHandle h = c.getDetails().getAccountHandle();
        if (h == null || Actions.sims(this).size() < 2) return null;
        return Actions.simLabel(this, h);
    }

    private void updateStatus() {
        if (ended) return;
        Call c = CallManager.primary();
        if (c == null) return;
        int s = CallManager.state(c);
        String text;
        switch (s) {
            case Call.STATE_RINGING:
                text = CallManager.find(Call.STATE_ACTIVE, Call.STATE_HOLDING) != null ? "Another call is coming in" : "Incoming call";
                break;
            case Call.STATE_ACTIVE: {
                long t = c.getDetails().getConnectTimeMillis();
                text = t > 0 ? Ui.clock((System.currentTimeMillis() - t) / 1000) : "Connected";
                break;
            }
            case Call.STATE_HOLDING:
                text = "On hold";
                break;
            case Call.STATE_SELECT_PHONE_ACCOUNT:
                text = "Choose a SIM";
                break;
            case Call.STATE_DISCONNECTING:
                text = "Ending call";
                break;
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
            default:
                text = "Calling";
                break;
        }
        if (RecorderService.recording) {
            text = "Recording  " + Ui.clock((System.currentTimeMillis() - RecorderService.startedAt) / 1000) + "   ·   " + text;
        }
        status.setText(text);
        status.setTextColor(s == Call.STATE_ACTIVE ? Ui.text : Ui.muted);
        recDot.setVisibility(RecorderService.recording ? View.VISIBLE : View.GONE);
        Call.Details d = c.getDetails();
        boolean hd = d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO);
        boolean wifi = d.hasProperty(Call.Details.PROPERTY_WIFI);
        hdBadge.setText(hd && wifi ? "HD  Wi-Fi" : hd ? "HD" : "Wi-Fi");
        hdBadge.setVisibility((hd || wifi) && s == Call.STATE_ACTIVE ? View.VISIBLE : View.GONE);
    }

    private void renderHoldBanner(Call primary) {
        Call other = CallManager.other(primary);
        if (other == null || CallManager.state(primary) == Call.STATE_RINGING) {
            holdBanner.setVisibility(View.GONE);
            return;
        }
        holdBanner.removeAllViews();
        CallManager.Info oi = CallManager.info(other);
        int os = CallManager.state(other);
        String who = CallManager.isConference(other) ? "Conference call" : oi.name != null ? oi.name : format(oi.number);
        TextView t = Ui.oneLine(Ui.text(this, (os == Call.STATE_HOLDING ? "On hold  ·  " : "") + who, 14, Ui.text, true));
        holdBanner.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        holdBanner.addView(Ui.button(this, "Swap", Ui.TEXT, v -> CallManager.swap(primary, other)));
        holdBanner.setVisibility(View.VISIBLE);
    }

    private void setProximity(boolean on) {
        if (proximity == null) return;
        try {
            if (on && !proximity.isHeld()) proximity.acquire(4 * 60 * 60 * 1000L);
            else if (!on && proximity.isHeld()) proximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ panels

    private void setPanel(int p, Call c) {
        boolean same = p == panel;
        panel = p;
        View v;
        switch (p) {
            case PANEL_INCOMING:
                v = incomingPanel(c);
                break;
            case PANEL_KEYPAD:
                if (same) return; // keep typed digits
                v = keypadPanel(c);
                break;
            default:
                v = controlsPanel(c);
                break;
        }
        panelBox.removeAllViews();
        panelBox.addView(v, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (!same) {
            v.setAlpha(0f);
            v.setTranslationY(dp(24));
            v.animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private View incomingPanel(Call c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        // Small actions above the big buttons, as on an iPhone.
        LinearLayout small = new LinearLayout(this);
        small.addView(smallAction(R.drawable.ic_volume_off, "Silence", v -> {
            try {
                getSystemService(TelecomManager.class).silenceRinger();
            } catch (RuntimeException ignored) {
            }
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        small.addView(smallAction(R.drawable.ic_message, "Message", v -> replyMenu(c)), new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(small, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(30);
        rowLp.bottomMargin = dp(8);
        box.addView(row, rowLp);
        Call active = CallManager.find(Call.STATE_ACTIVE);
        row.addView(Ui.tile(this, R.drawable.ic_call_end, "Decline", Ui.danger, 0xFFFFFFFF, 78, v -> CallManager.decline(c)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        if (active != null) {
            row.addView(Ui.tile(this, R.drawable.ic_call_end, "End & Accept", 0x33FFFFFF, 0xFFFFFFFF, 78, v -> {
                CallManager.hangUp(active);
                CallManager.answer(c);
            }), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        row.addView(Ui.tile(this, R.drawable.ic_call, active != null ? "Hold & Accept" : "Accept", Ui.accent, Ui.onAccent, 78, v -> {
            if (active != null) active.hold();
            CallManager.answer(c);
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        return box;
    }

    private LinearLayout smallAction(int icon, String label, View.OnClickListener l) {
        LinearLayout t = Ui.tile(this, icon, label, 0x00000000, 0xFFFFFFFF, 48, l);
        ((TextView) t.getChildAt(1)).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        ((TextView) t.getChildAt(1)).setPadding(0, 0, 0, 0);
        return t;
    }

    private View offset(View v) {
        FrameLayout f = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = dp(18);
        f.addView(v, lp);
        return f;
    }

    private void replyMenu(Call c) {
        List<Ui.Action> acts = new ArrayList<>();
        for (String r : Prefs.quickReplies(this)) {
            acts.add(new Ui.Action(R.drawable.ic_message, r, false, () -> CallManager.declineWithMessage(c, r)));
        }
        acts.add(new Ui.Action(R.drawable.ic_edit, "Write your own", false, () ->
                Ui.input(this, "Reply", "Message", null, true, "Decline and send", text -> {
                    if (!text.isEmpty()) CallManager.declineWithMessage(c, text);
                })));
        Ui.actions(this, "Decline with a message", acts.toArray(new Ui.Action[0]));
    }

    private LinearLayout controlTile(int icon, String label, boolean on, boolean enabled, View.OnClickListener l) {
        LinearLayout t = Ui.tile(this, icon, label, on ? 0xFFFFFFFF : 0x33FFFFFF, on ? 0xFF111114 : 0xFFFFFFFF, 76, enabled ? l : null);
        t.setAlpha(enabled ? 1f : 0.4f);
        t.setEnabled(enabled);
        return t;
    }

    private View controlsPanel(Call c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int s = CallManager.state(c);
        boolean live = s == Call.STATE_ACTIVE || s == Call.STATE_HOLDING;
        Call other = CallManager.other(c);
        List<View> tiles = new ArrayList<>();
        boolean muted = CallManager.muted();
        tiles.add(controlTile(muted ? R.drawable.ic_mic_off : R.drawable.ic_mic, muted ? "Unmute" : "Mute", muted, true,
                v -> CallManager.setMuted(!CallManager.muted())));
        tiles.add(controlTile(R.drawable.ic_dialpad, "Keypad", false, true, v -> {
            keypadWanted = true;
            render();
        }));
        tiles.add(audioTile());
        if (other != null) {
            tiles.add(controlTile(R.drawable.ic_swap, "Swap", false, true, v -> CallManager.swap(c, other)));
            tiles.add(controlTile(R.drawable.ic_merge, "Merge", false, CallManager.canMerge(c), v -> CallManager.merge(c)));
        } else {
            boolean held = s == Call.STATE_HOLDING;
            tiles.add(controlTile(held ? R.drawable.ic_play : R.drawable.ic_pause, held ? "Resume" : "Hold", held,
                    live && CallManager.canHold(c), v -> CallManager.toggleHold(c)));
            tiles.add(controlTile(R.drawable.ic_add_call, "Add call", false, live, v -> addCall()));
        }
        if (!Prefs.REC_OFF.equals(Prefs.recording(this))) {
            boolean rec = RecorderService.recording;
            LinearLayout r = Ui.tile(this, rec ? R.drawable.ic_stop : R.drawable.ic_record, rec ? "Stop" : "Record",
                    rec ? Ui.danger : 0x33FFFFFF, 0xFFFFFFFF, 76, live ? v -> toggleRecording(c) : null);
            r.setAlpha(live ? 1f : 0.4f);
            tiles.add(r);
        } else {
            tiles.add(controlTile(R.drawable.ic_notes, "Note", false, true, v -> noteDuringCall(c)));
        }
        for (int i = 0; i < tiles.size(); i += 3) {
            LinearLayout row = new LinearLayout(this);
            for (int j = i; j < Math.min(i + 3, tiles.size()); j++) row.addView(tiles.get(j), new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(22);
            box.addView(row, lp);
        }
        ImageView end = Ui.iconButton(this, R.drawable.ic_call_end, 78, Ui.danger, 0xFFFFFFFF, "End call", v -> CallManager.hangUp(c));
        LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) end.getLayoutParams();
        elp.topMargin = dp(16);
        elp.bottomMargin = dp(8);
        box.addView(end);
        return box;
    }

    private LinearLayout audioTile() {
        int mask = CallManager.routes(), route = CallManager.route();
        boolean extra = (mask & (CallAudioState.ROUTE_BLUETOOTH | CallAudioState.ROUTE_WIRED_HEADSET)) != 0;
        if (!extra) {
            boolean speaker = route == CallAudioState.ROUTE_SPEAKER;
            return controlTile(R.drawable.ic_speaker, "Speaker", speaker, true,
                    v -> CallManager.setRoute(speaker ? CallAudioState.ROUTE_EARPIECE : CallAudioState.ROUTE_SPEAKER));
        }
        int icon = route == CallAudioState.ROUTE_BLUETOOTH ? R.drawable.ic_bluetooth : route == CallAudioState.ROUTE_SPEAKER
                ? R.drawable.ic_speaker : route == CallAudioState.ROUTE_WIRED_HEADSET ? R.drawable.ic_headset : R.drawable.ic_phone;
        String label = route == CallAudioState.ROUTE_BLUETOOTH ? deviceName(CallManager.activeBluetooth(), "Bluetooth")
                : route == CallAudioState.ROUTE_SPEAKER ? "Speaker" : route == CallAudioState.ROUTE_WIRED_HEADSET ? "Headset" : "Phone";
        return controlTile(icon, label, route != CallAudioState.ROUTE_EARPIECE, true, v -> audioMenu());
    }

    private String deviceName(BluetoothDevice d, String fallback) {
        if (d == null) return fallback;
        try {
            String n = d.getName();
            return n != null && !n.isEmpty() ? n : fallback;
        } catch (SecurityException e) {
            return fallback;
        }
    }

    private void audioMenu() {
        int mask = CallManager.routes(), route = CallManager.route();
        List<Ui.Action> acts = new ArrayList<>();
        if ((mask & CallAudioState.ROUTE_EARPIECE) != 0) {
            acts.add(new Ui.Action(R.drawable.ic_phone, mark("Phone", route == CallAudioState.ROUTE_EARPIECE), false,
                    () -> CallManager.setRoute(CallAudioState.ROUTE_EARPIECE)));
        }
        if ((mask & CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            acts.add(new Ui.Action(R.drawable.ic_headset, mark("Wired headset", route == CallAudioState.ROUTE_WIRED_HEADSET), false,
                    () -> CallManager.setRoute(CallAudioState.ROUTE_WIRED_HEADSET)));
        }
        acts.add(new Ui.Action(R.drawable.ic_speaker, mark("Speaker", route == CallAudioState.ROUTE_SPEAKER), false,
                () -> CallManager.setRoute(CallAudioState.ROUTE_SPEAKER)));
        if ((mask & CallAudioState.ROUTE_BLUETOOTH) != 0) {
            List<BluetoothDevice> devices = CallManager.bluetooth();
            BluetoothDevice activeBt = CallManager.activeBluetooth();
            if (devices.isEmpty()) {
                acts.add(new Ui.Action(R.drawable.ic_bluetooth, mark("Bluetooth", route == CallAudioState.ROUTE_BLUETOOTH), false,
                        () -> CallManager.setRoute(CallAudioState.ROUTE_BLUETOOTH)));
            }
            for (BluetoothDevice d : devices) {
                boolean on = route == CallAudioState.ROUTE_BLUETOOTH && d.equals(activeBt);
                acts.add(new Ui.Action(R.drawable.ic_bluetooth, mark(deviceName(d, "Bluetooth device"), on), false,
                        () -> CallManager.useBluetooth(d)));
            }
        }
        Ui.actions(this, "Play call audio on", acts.toArray(new Ui.Action[0]));
    }

    private String mark(String s, boolean on) {
        return on ? s + "  (in use)" : s;
    }

    private View keypadPanel(Call c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        dtmfView = Ui.text(this, "", 28, Ui.text, false);
        dtmfView.setGravity(Gravity.CENTER);
        dtmfView.setSingleLine(true);
        dtmfView.setEllipsize(TextUtils.TruncateAt.START);
        dtmfView.setMinHeight(dp(40));
        box.addView(dtmfView, new LinearLayout.LayoutParams(-1, -2));
        OrbitDialView pad = new OrbitDialView(this);
        pad.setStyle(OrbitDialView.GRID);
        pad.setListener(new OrbitDialView.Listener() {
            @Override
            public void onDigit(char key) {
                dtmfView.append(String.valueOf(key));
                CallManager.dtmf(CallManager.primary(), key);
            }

            @Override
            public void onDigitLongPress(char key) {
                onDigit(key);
            }

            @Override
            public void onCallPressed() {
            }

            @Override
            public void onGesture(String pattern) {
            }
        });
        box.addView(pad, new LinearLayout.LayoutParams(-1, dp(300)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(Ui.iconButton(this, R.drawable.ic_call_end, 72, Ui.danger, 0xFFFFFFFF, "End call", v -> CallManager.hangUp(c)));
        FrameLayout right = new FrameLayout(this);
        right.addView(Ui.button(this, "Hide", Ui.TEXT, v -> {
            keypadWanted = false;
            render();
        }), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        box.addView(row, rlp);
        return box;
    }

    // ----------------------------------------------------------------- actions

    private void addCall() {
        Runnable go = () -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_ADD_CALL, true);
            i.putExtra("skip_setup", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        };
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    go.run();
                }
            });
        } else {
            go.run();
        }
    }

    private void toggleRecording(Call c) {
        Intent svc = new Intent(this, RecorderService.class);
        if (RecorderService.recording) {
            stopService(svc);
            snack("Recording saved to " + (CallManager.info(c).name != null ? CallManager.info(c).name + "'s" : "this number's") + " page.");
            return;
        }
        if (!Actions.has(this, android.Manifest.permission.RECORD_AUDIO)) {
            Ui.snack(this, "Recording needs the microphone permission.", "Allow",
                    () -> requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 61));
            return;
        }
        if (Prefs.REC_SPEAKER.equals(Prefs.recording(this)) && CallManager.route() == CallAudioState.ROUTE_EARPIECE) {
            CallManager.setRoute(CallAudioState.ROUTE_SPEAKER); // so the microphone hears both sides
        }
        CallManager.Info info = CallManager.info(c);
        svc.putExtra("number", info.number);
        svc.putExtra("name", info.name);
        long created = c.getDetails().getCreationTimeMillis();
        svc.putExtra("callTime", created > 0 ? created : info.created);
        try {
            startForegroundService(svc);
        } catch (RuntimeException e) {
            snack("Recording couldn't start. Please try again.");
            return;
        }
        status.postDelayed(() -> {
            if (!RecorderService.recording && RecorderService.lastError != null) snack(RecorderService.lastError);
            render();
        }, 700);
    }

    private void noteDuringCall(Call c) {
        CallManager.Info info = CallManager.info(c);
        long created = c.getDetails().getCreationTimeMillis();
        long callTime = created > 0 ? created : info.created;
        Ui.input(this, "Note", "Write while you talk", null, true, "Save", text -> {
            if (text.isEmpty()) return;
            Notes.add(this, info.number, Notes.NOTE, callTime, text, 0);
            snack("Note saved.");
        });
    }

    private void manageConference() {
        Call conf = CallManager.primary();
        if (conf == null) return;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.app.Dialog[] d = new android.app.Dialog[1];
        for (Call child : conf.getChildren()) {
            CallManager.Info i = CallManager.info(child);
            LinearLayout buttons = new LinearLayout(this);
            if (child.getDetails().can(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE)) {
                buttons.addView(Ui.button(this, "Private", Ui.TEXT, v -> {
                    d[0].dismiss();
                    child.splitFromConference();
                }));
            }
            if (child.getDetails().can(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE)) {
                buttons.addView(Ui.iconButton(this, R.drawable.ic_call_end, 48, 0, Ui.danger, "Remove from call", v -> {
                    d[0].dismiss();
                    child.disconnect();
                }));
            }
            list.addView(Ui.row(this, R.drawable.ic_person, i.name != null ? i.name : format(i.number),
                    i.name != null ? format(i.number) : null, buttons, null));
        }
        d[0] = Ui.sheet(this, "People on this call", list);
        d[0].show();
    }

    // ------------------------------------------------------------------ ended

    private void showEnded() {
        CallManager.Ended e = CallManager.lastEnded;
        if (e == null) {
            finishAndRemoveTask();
            return;
        }
        ended = true;
        setProximity(false);
        holdBanner.setVisibility(View.GONE);
        hdBadge.setVisibility(View.GONE);
        recDot.setVisibility(View.GONE);
        boolean connected = e.connectTime > 0;
        String reason;
        if (connected) reason = "Call ended  ·  " + Ui.clock(e.seconds);
        else if (e.cause == DisconnectCause.BUSY) reason = "Line busy";
        else if (e.cause == DisconnectCause.REJECTED) reason = "Declined";
        else if (e.cause == DisconnectCause.MISSED) reason = "Missed call";
        else if (e.cause == DisconnectCause.CANCELED || e.cause == DisconnectCause.LOCAL) reason = "Call cancelled";
        else if (e.cause == DisconnectCause.ERROR) reason = e.reason != null && !e.reason.isEmpty() ? e.reason : "The call couldn't connect";
        else reason = "Call ended";
        status.setText(reason);
        status.setTextColor(e.cause == DisconnectCause.ERROR || e.cause == DisconnectCause.BUSY ? Ui.danger : Ui.muted);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean known = e.number != null && !e.number.isEmpty();
        if (connected && known && Prefs.notePrompt(this)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.glassCard(this, 22, true));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView q = Ui.text(this, "Add a note about this call?", 15, Ui.text, true);
            card.addView(q);
            EditText note = Ui.field(this, "Something to remember");
            note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            note.setMaxLines(4);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
            nlp.topMargin = dp(10);
            card.addView(note, nlp);
            TextView save = Ui.button(this, "Save note", Ui.FILLED, v -> {
                String t = note.getText().toString().trim();
                if (t.isEmpty()) {
                    snack("Type a note first.");
                    return;
                }
                Notes.add(this, e.number, Notes.NOTE, e.callTime, t, 0);
                snack("Note saved.");
                status.postDelayed(this::finishAndRemoveTask, 900);
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.gravity = Gravity.END;
            sl.topMargin = dp(10);
            card.addView(save, sl);
            box.addView(card, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(20);
        box.addView(row, rl);
        if (known) {
            row.addView(Ui.tile(this, R.drawable.ic_call, "Call again", Ui.accent, Ui.onAccent, 60, v -> {
                finishAndRemoveTask();
                Actions.call(this, e.number);
            }), new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(Ui.tile(this, R.drawable.ic_message, "Message", Ui.glassStrong, Ui.text, 60, v -> Actions.sms(this, e.number)),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            if (e.contactId < 0) {
                row.addView(Ui.tile(this, R.drawable.ic_person_add, "Add contact", Ui.glassStrong, Ui.text, 60,
                        v -> Actions.addContact(this, e.number)), new LinearLayout.LayoutParams(0, -2, 1f));
            }
        }
        row.addView(Ui.tile(this, R.drawable.ic_close, "Close", Ui.glassStrong, Ui.text, 60, v -> finishAndRemoveTask()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        panel = PANEL_ENDED;
        panelBox.removeAllViews();
        panelBox.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        box.setAlpha(0f);
        box.animate().alpha(1f).setDuration(220).start();
        long wait = !connected ? 2500 : Prefs.notePrompt(this) ? 15000 : 4000;
        if (!userTouched) status.postDelayed(autoClose, wait);
        CallManager.lastEnded = null;
    }
}
