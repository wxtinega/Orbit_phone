package com.orbitdial.app;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Four short steps: welcome, become the phone app, permissions, make it yours. */
public class OnboardingActivity extends BaseActivity {
    private static final int REQ_PERMS = 51;
    private int step;
    private FrameLayout stage;
    private LinearLayout dots;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) step = state.getInt("step");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(12), dp(24), dp(16));
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        root.addView(dots, new LinearLayout.LayoutParams(-1, dp(32)));
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        Ui.applyWindow(this, root);
        show(step, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step == 1 || step == 2) show(step, false); // reflect what was just allowed
    }

    @Override
    public void onBackPressed() {
        if (step > 0) show(step - 1, true);
        else super.onBackPressed();
    }

    private void show(int s, boolean animate) {
        boolean forward = s >= step;
        step = s;
        dots.removeAllViews();
        for (int i = 0; i < 4; i++) {
            View d = new View(this);
            d.setBackground(Ui.round(i == s ? Ui.accent : Ui.faint, dp(4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == s ? 22 : 8), dp(8));
            lp.setMargins(dp(3), 0, dp(3), 0);
            dots.addView(d, lp);
        }
        View page = s == 0 ? welcome() : s == 1 ? phoneApp() : s == 2 ? permissions() : personalise();
        View old = stage.getChildCount() > 0 ? stage.getChildAt(0) : null;
        stage.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (animate && old != null) {
            page.setAlpha(0f);
            page.setTranslationX(dp(forward ? 40 : -40));
            page.animate().alpha(1f).translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
            old.animate().alpha(0f).translationX(dp(forward ? -40 : 40)).setDuration(180)
                    .withEndAction(() -> stage.removeView(old)).start();
        } else if (old != null) {
            stage.removeView(old);
        }
    }

    private LinearLayout pageShell(int icon, String titleText, String sub) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        if (icon != 0) {
            ImageView i = Ui.icon(this, icon, Ui.onAccent);
            i.setPadding(dp(18), dp(18), dp(18), dp(18));
            i.setBackground(Ui.oval(Ui.accent));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(72), dp(72));
            ilp.topMargin = dp(28);
            p.addView(i, ilp);
        }
        TextView t = Ui.text(this, titleText, 28, Ui.text, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(22), 0, 0);
        p.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null) {
            TextView s = Ui.text(this, sub, 16, Ui.muted, false);
            s.setGravity(Gravity.CENTER);
            s.setPadding(dp(8), dp(10), dp(8), 0);
            p.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        return p;
    }

    private View withButtons(View content, View... buttons) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (View b : buttons) {
            if (b == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            col.addView(b, lp);
        }
        return col;
    }

    private View welcome() {
        LinearLayout p = pageShell(0, "Orbit Dial", "A faster, cleaner way to call.");
        OrbitDialView preview = new OrbitDialView(this);
        preview.setStyle(OrbitDialView.ORBIT);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout box = new FrameLayout(this);
        box.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        box.addView(new View(this), new FrameLayout.LayoutParams(-1, -1)); // just a picture here
        box.getChildAt(1).setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(260));
        lp.topMargin = dp(24);
        p.addView(box, 0, lp);
        TextView feats = Ui.text(this, "Draw a path on the keypad to call someone instantly. Notes after calls. "
                + "Caller photos. All on your phone.", 15, Ui.muted, false);
        feats.setGravity(Gravity.CENTER);
        feats.setPadding(dp(12), dp(18), dp(12), 0);
        p.addView(feats);
        return withButtons(p, Ui.button(this, "Get started", Ui.FILLED, v -> show(1, true)));
    }

    private View phoneApp() {
        boolean isDefault = Actions.isDefaultPhoneApp(this);
        LinearLayout p = pageShell(isDefault ? R.drawable.ic_check : R.drawable.ic_call,
                isDefault ? "You're all set" : "Make Orbit Dial your phone app",
                isDefault ? "Orbit Dial now handles your calls." : null);
        if (!isDefault) {
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, dp(18), 0, 0);
            list.addView(point(R.drawable.ic_call, "See who's calling the moment it rings, even on the lock screen"));
            list.addView(point(R.drawable.ic_add_photo, "Caller photos, custom ringtones and blocking"));
            list.addView(point(R.drawable.ic_add_call, "Add people to a call, merge and swap calls"));
            list.addView(point(R.drawable.ic_notes, "Notes after calls, and recording if you turn it on"));
            p.addView(list, new LinearLayout.LayoutParams(-1, -2));
            TextView small = Ui.text(this, "You can switch back to your old phone app any time in Settings.", 13, Ui.muted, false);
            small.setGravity(Gravity.CENTER);
            small.setPadding(dp(8), dp(16), dp(8), 0);
            p.addView(small);
            return withButtons(p,
                    Ui.button(this, "Set as phone app", Ui.FILLED, v -> Actions.askToBeDefault(this)),
                    Ui.button(this, "Not now", Ui.TEXT, v -> show(2, true)));
        }
        return withButtons(p, Ui.button(this, "Continue", Ui.FILLED, v -> show(2, true)));
    }

    private View point(int icon, String text) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(10), dp(4), dp(10));
        ImageView i = Ui.icon(this, icon, Ui.accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
        lp.rightMargin = dp(16);
        r.addView(i, lp);
        r.addView(Ui.text(this, text, 15, Ui.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private static final class Perm {
        final String perm, name, why;
        final int icon;

        Perm(String perm, int icon, String name, String why) {
            this.perm = perm;
            this.icon = icon;
            this.name = name;
            this.why = why;
        }
    }

    private List<Perm> perms() {
        List<Perm> p = new ArrayList<>();
        p.add(new Perm(Manifest.permission.READ_CONTACTS, R.drawable.ic_person, "Contacts", "Show names and photos, and search"));
        p.add(new Perm(Manifest.permission.READ_CALL_LOG, R.drawable.ic_history, "Call history", "Show your recent calls"));
        p.add(new Perm(Manifest.permission.CALL_PHONE, R.drawable.ic_call, "Phone", "Make calls and pick a SIM"));
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(new Perm(Manifest.permission.POST_NOTIFICATIONS, R.drawable.ic_notes, "Notifications", "Missed calls and calls in progress"));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(new Perm(Manifest.permission.BLUETOOTH_CONNECT, R.drawable.ic_bluetooth, "Nearby devices", "Show your headset or car by name"));
        }
        return p;
    }

    private View permissions() {
        LinearLayout p = pageShell(R.drawable.ic_settings, "A few permissions", "Orbit Dial only uses these on your phone.");
        List<View> rows = new ArrayList<>();
        boolean all = true;
        for (Perm x : perms()) {
            boolean ok = Actions.has(this, x.perm);
            all &= ok;
            View status = ok ? Ui.icon(this, R.drawable.ic_check, Ui.accent) : null;
            if (status != null) status.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
            rows.add(Ui.row(this, x.icon, x.name, x.why, status, null));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(20);
        p.addView(Ui.card(this, rows.toArray(new View[0])), lp);
        if (all) return withButtons(p, Ui.button(this, "Continue", Ui.FILLED, v -> show(3, true)));
        return withButtons(p,
                Ui.button(this, "Allow", Ui.FILLED, v -> {
                    List<String> need = new ArrayList<>();
                    for (Perm x : perms()) if (!Actions.has(this, x.perm)) need.add(x.perm);
                    need.add(Manifest.permission.WRITE_CONTACTS);
                    need.add(Manifest.permission.WRITE_CALL_LOG);
                    need.add(Manifest.permission.READ_PHONE_STATE);
                    requestPermissions(need.toArray(new String[0]), REQ_PERMS);
                }),
                Ui.button(this, "Skip", Ui.TEXT, v -> show(3, true)));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == REQ_PERMS) show(2, false);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Actions.REQ_DEFAULT_DIALER) {
            if (Actions.isDefaultPhoneApp(this)) show(2, true);
            else show(1, false);
        }
    }

    private View personalise() {
        LinearLayout p = pageShell(0, "Make it yours", "Pick a dial pad. You can change everything later in Settings.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(20);
        p.addView(SettingsActivity.dialPicker(this, this::recreate), lp);
        TextView themeLabel = Ui.text(this, "Theme", 14, Ui.text, true);
        themeLabel.setPadding(dp(2), dp(22), 0, dp(8));
        p.addView(themeLabel, new LinearLayout.LayoutParams(-1, -2));
        String mode = Prefs.mode(this);
        p.addView(Ui.segmented(this, new String[]{"Auto", "Light", "Dark"},
                Prefs.MODE_LIGHT.equals(mode) ? 1 : Prefs.MODE_DARK.equals(mode) ? 2 : 0, i -> stage.postDelayed(() -> {
                    Prefs.setMode(this, i == 1 ? Prefs.MODE_LIGHT : i == 2 ? Prefs.MODE_DARK : Prefs.MODE_AUTO);
                    recreate();
                }, 180)), new LinearLayout.LayoutParams(-1, -2));
        TextView accentLabel = Ui.text(this, "Accent colour", 14, Ui.text, true);
        accentLabel.setPadding(dp(2), dp(22), 0, dp(8));
        p.addView(accentLabel, new LinearLayout.LayoutParams(-1, -2));
        p.addView(SettingsActivity.swatches(this, this::recreate), new LinearLayout.LayoutParams(-1, -2));
        return withButtons(p, Ui.button(this, "Start calling", Ui.FILLED, v -> {
            Prefs.setOnboarded(this);
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }));
    }
}
