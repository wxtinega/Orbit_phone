package com.orbitdial.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Calendar;

/** The design system: colours, type, glass surfaces, tap feedback, sheets and messages. */
final class Ui {
    private Ui() {}

    // ------------------------------------------------------------------ palette
    static boolean dark;
    static int accent, onAccent, text, muted, faint, glass, glassStrong, field, scrim, solidBg, sheet, danger;
    static final int DANGER = 0xFFE5484D;
    static float glassLevel; // 0 = solid, 1 = soft, 2 = strong
    private static Context app; // for date formatting (12/24-hour clock)

    /** Avatar colours. Warm and cool tones, no purple, no green. */
    private static final int[] AVATAR = {0xFFF86939, 0xFF2F80ED, 0xFF0EA5A4, 0xFFE8456B, 0xFFE0A100,
            0xFF64748B, 0xFF0284C7, 0xFFC2410C, 0xFFBE185D, 0xFF0F766E};

    static void load(Context c) {
        app = c.getApplicationContext();
        String mode = Prefs.mode(c);
        boolean sysDark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dark = Prefs.MODE_DARK.equals(mode) || (Prefs.MODE_AUTO.equals(mode) && sysDark);
        accent = Prefs.accent(c);
        if (dark && accent == 0xFF5B6472) accent = 0xFFA3ACBA; // graphite needs to be lighter on dark
        onAccent = 0xFFFFFFFF;
        danger = DANGER;
        String g = Prefs.glass(c);
        glassLevel = Prefs.GLASS_OFF.equals(g) ? 0 : Prefs.GLASS_STRONG.equals(g) ? 2 : 1;
        if (dark) {
            text = 0xFFF4F4F6;
            muted = 0xFFA1A1AA;
            faint = 0x1FFFFFFF;
            glass = 0x14FFFFFF;
            glassStrong = 0x24FFFFFF;
            field = 0x1AFFFFFF;
            solidBg = 0xFF0F1012;
            scrim = glassLevel == 2 ? 0x99101114 : 0xCC101114;
            sheet = 0xF21A1B1F;
        } else {
            text = 0xFF111114;
            muted = 0xFF6B6B76;
            faint = 0x14000000;
            glass = 0x99FFFFFF;
            glassStrong = 0xCCFFFFFF;
            field = 0x0F000000;
            solidBg = 0xFFF3F3F5;
            scrim = glassLevel == 2 ? 0x8CF6F6F8 : 0xC7F6F6F8;
            sheet = 0xF7FFFFFF;
        }
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    // -------------------------------------------------------------------- window

    /** Glass over the wallpaper (blurred where the phone supports it), edge to edge, correct bar icons. */
    @SuppressWarnings("deprecation")
    static void applyWindow(Activity a, View root) {
        load(a);
        Window w = a.getWindow();
        w.getDecorView(); // make sure the window exists before styling its bars
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (glassLevel > 0) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            w.setBackgroundDrawable(new ColorDrawable(scrim));
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    if (a.getSystemService(WindowManager.class).isCrossWindowBlurEnabled()) {
                        w.setBackgroundBlurRadius(dp(a, glassLevel == 2 ? 28 : 40));
                    }
                } catch (RuntimeException ignored) {
                }
            }
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            w.setBackgroundDrawable(new ColorDrawable(solidBg));
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController ic = w.getInsetsController();
            if (ic != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
        if (root != null) padForBars(root);
    }

    /** Keeps content clear of the status bar, navigation bar and keyboard. */
    @SuppressWarnings("deprecation")
    static void padForBars(View root) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                        | WindowInsets.Type.displayCutout());
                top = s.top;
                bottom = s.bottom;
                left = s.left;
                right = s.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(l + left, t + top, r + right, b + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    // ---------------------------------------------------------------------- type

    static TextView text(Context c, CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, 26, text, true);
        t.setLetterSpacing(-0.01f);
        oneLine(t);
        return t;
    }

    static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 13, muted, true);
        t.setPadding(dp(c, 4), dp(c, 18), dp(c, 4), dp(c, 8));
        t.setLetterSpacing(0.02f);
        return t;
    }

    // ------------------------------------------------------------------ surfaces

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** A frosted card: translucent fill plus a hairline edge. */
    static Drawable glassCard(Context c, float radiusDp, boolean strong) {
        GradientDrawable g = round(strong ? glassStrong : glass, dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, 0.7f)), faint);
        return g;
    }

    static Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x26FFFFFF : 0x1A000000), content, mask);
    }

    static Drawable rippleRound(Context c, int color, float radiusDp) {
        return ripple(round(color, dp(c, radiusDp)), round(0xFFFFFFFF, dp(c, radiusDp)));
    }

    static Drawable rippleOval(int color) {
        return ripple(oval(color), oval(0xFFFFFFFF));
    }

    // ----------------------------------------------------------------- feedback

    /** Every tappable thing gets this: shrinks a little on touch, springs back on release. */
    @SuppressLint("ClickableViewAccessibility")
    static <T extends View> T press(T v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(2.2f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        return v;
    }

    static void tick(View v) {
        if (v != null && Prefs.haptics(v.getContext())) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    static View.OnClickListener tap(View.OnClickListener l) {
        return v -> {
            tick(v);
            if (l != null) l.onClick(v);
        };
    }

    // ------------------------------------------------------------------ controls

    static final int FILLED = 0, TONAL = 1, DANGER_FILLED = 2, TEXT = 3;

    /** A button at least 48dp tall. */
    static TextView button(Context c, String label, int style, View.OnClickListener click) {
        TextView t = text(c, label, 15, style == FILLED || style == DANGER_FILLED ? onAccent : style == TEXT ? accent : text, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 48));
        t.setMinWidth(dp(c, 64));
        t.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        int bg = style == FILLED ? accent : style == DANGER_FILLED ? danger : style == TONAL ? glassStrong : 0x00000000;
        t.setBackground(rippleRound(c, bg, 24));
        t.setOnClickListener(tap(click));
        return press(t);
    }

    static ImageView icon(Context c, int res, int tint) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(tint));
        i.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return i;
    }

    /** Round icon button; the touch target is always at least 48dp. */
    static ImageView iconButton(Context c, int res, float sizeDp, int bg, int tint, String desc, View.OnClickListener click) {
        ImageView b = icon(c, res, tint);
        float size = Math.max(48, sizeDp);
        int pad = dp(c, size * 0.27f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(rippleOval(bg));
        b.setContentDescription(desc);
        b.setOnClickListener(tap(click));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
        return press(b);
    }

    /** Icon in a round tile with a caption underneath (contact actions, in-call controls). */
    static LinearLayout tile(Context c, int res, String label, int bg, int tint, float sizeDp, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView i = icon(c, res, tint);
        int pad = dp(c, sizeDp * 0.29f);
        i.setPadding(pad, pad, pad, pad);
        i.setBackground(rippleOval(bg));
        box.addView(i, new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        TextView t = text(c, label, 13, text, false);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(c, 8), 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(-2, -2));
        box.setContentDescription(label);
        box.setOnClickListener(tap(click));
        box.setClickable(click != null);
        press(box);
        return box;
    }

    /** A settings / details row: icon, title, optional subtitle, optional trailing view. */
    static LinearLayout row(Context c, int iconRes, String titleText, String sub, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 56));
        row.setPadding(dp(c, 14), dp(c, 10), dp(c, 12), dp(c, 10));
        if (iconRes != 0) {
            ImageView i = icon(c, iconRes, muted);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22));
            ilp.rightMargin = dp(c, 16);
            row.addView(i, ilp);
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, titleText, 16, text, false);
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 13, muted, false);
            s.setPadding(0, dp(c, 3), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) row.addView(trailing);
        if (click != null) {
            row.setBackground(rippleRound(c, 0x00000000, 14));
            row.setOnClickListener(tap(click));
        }
        return row;
    }

    /** A group of rows on one glass card, with hairlines between them. */
    static LinearLayout card(Context c, View... rows) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassCard(c, 18, false));
        card.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null) continue;
            if (card.getChildCount() > 0) card.addView(hairline(c));
            card.addView(rows[i]);
        }
        return card;
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(faint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.7f)));
        lp.leftMargin = dp(c, 14);
        lp.rightMargin = dp(c, 14);
        v.setLayoutParams(lp);
        return v;
    }

    static Switch toggle(Context c, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(c);
        s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, dark ? 0xFFD4D4D8 : 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states, new int[]{accent, dark ? 0x55FFFFFF : 0x33000000}));
        s.setOnCheckedChangeListener((b, v) -> {
            tick(b);
            l.onCheckedChanged(b, v);
        });
        return s;
    }

    /** Segmented choice, e.g. Auto / Light / Dark. */
    interface Choice {
        void chosen(int index);
    }

    static LinearLayout segmented(Context c, String[] labels, int selected, Choice cb) {
        LinearLayout seg = new LinearLayout(c);
        seg.setBackground(round(field, dp(c, 22)));
        seg.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = text(c, labels[i], 14, text, true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(c, 40));
            t.setPadding(dp(c, 6), 0, dp(c, 6), 0);
            oneLine(t);
            items[i] = t;
            t.setOnClickListener(tap(v -> {
                for (int j = 0; j < items.length; j++) styleSeg(c, items[j], j == idx);
                cb.chosen(idx);
            }));
            press(t);
            styleSeg(c, t, i == selected);
            seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return seg;
    }

    private static void styleSeg(Context c, TextView t, boolean on) {
        t.setBackground(on ? round(dark ? 0x33FFFFFF : 0xFFFFFFFF, dp(c, 18)) : null);
        t.setTextColor(on ? text : muted);
    }

    static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(text);
        e.setHintTextColor(muted);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(round(field, dp(c, 16)));
        e.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        e.setMinHeight(dp(c, 52));
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = round(accent, dp(c, 1));
            cursor.setSize(dp(c, 2), dp(c, 20));
            e.setTextCursorDrawable(cursor);
        }
        e.setHighlightColor(alpha(accent, 0x55));
        return e;
    }

    // -------------------------------------------------------------------- sheets

    /** Bottom sheet holding any content. Slides up, dims and blurs what's behind. */
    static Dialog sheet(Activity a, String titleText, View content) {
        load(a);
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(sheet, dp(a, 28)));
        box.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 18));

        View grip = new View(a);
        grip.setBackground(round(dark ? 0x40FFFFFF : 0x26000000, dp(a, 3)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(a, 12);
        box.addView(grip, glp);
        if (titleText != null) {
            TextView t = text(a, titleText, 19, text, true);
            t.setPadding(dp(a, 6), 0, dp(a, 6), dp(a, 12));
            box.addView(t);
        }
        ScrollView sv = new ScrollView(a);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(content);
        box.addView(sv, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout outer = new FrameLayout(a);
        outer.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 8));
        outer.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        padForBars(outer);
        d.setContentView(outer);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(dark ? 0.5f : 0.3f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.getAttributes().setBlurBehindRadius(dp(a, 12));
            }
        }
        box.setTranslationY(dp(a, 400));
        d.setOnShowListener(x -> box.animate().translationY(0).setDuration(280)
                .setInterpolator(new DecelerateInterpolator(2f)).start());
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    static final class Action {
        final int icon;
        final String label;
        final boolean danger;
        final Runnable run;

        Action(int icon, String label, boolean danger, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.danger = danger;
            this.run = run;
        }
    }

    /** A list of actions in a sheet. */
    static Dialog actions(Activity a, String titleText, Action... acts) {
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        Dialog[] holder = new Dialog[1];
        for (Action act : acts) {
            if (act == null) continue;
            LinearLayout r = row(a, act.icon, act.label, null, null, v -> {
                holder[0].dismiss();
                if (act.run != null) act.run.run();
            });
            if (act.danger) {
                ((TextView) ((LinearLayout) r.getChildAt(act.icon != 0 ? 1 : 0)).getChildAt(0)).setTextColor(danger);
                if (act.icon != 0) ((ImageView) r.getChildAt(0)).setImageTintList(ColorStateList.valueOf(danger));
            }
            press(r);
            list.addView(r);
        }
        holder[0] = sheet(a, titleText, list);
        holder[0].show();
        return holder[0];
    }

    interface TextResult {
        void done(String value);
    }

    /** Text entry in a sheet (notes, quick replies). */
    static Dialog input(Activity a, String titleText, String hint, String initial, boolean multiLine, String saveLabel, TextResult cb) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText e = field(a, hint);
        if (initial != null) e.setText(initial);
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setMaxLines(8);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(a, 14);
        box.addView(buttons, blp);
        Dialog[] holder = new Dialog[1];
        buttons.addView(button(a, "Cancel", TEXT, v -> holder[0].dismiss()));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(a, 8);
        buttons.addView(button(a, saveLabel, FILLED, v -> {
            holder[0].dismiss();
            cb.done(e.getText().toString().trim());
        }), slp);
        holder[0] = sheet(a, titleText, box);
        holder[0].setOnShowListener(x -> {
            ViewGroup outer = (ViewGroup) holder[0].findViewById(android.R.id.content);
            View card = ((ViewGroup) outer.getChildAt(0)).getChildAt(0);
            card.animate().translationY(0).setDuration(260).start();
            e.requestFocus();
            e.setSelection(e.getText().length());
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            e.postDelayed(() -> {
                if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        });
        holder[0].show();
        return holder[0];
    }

    /** Short confirmation with two choices. */
    static Dialog confirm(Activity a, String titleText, String message, String yes, boolean destructive, Runnable onYes) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView m = text(a, message, 15, muted, false);
        m.setPadding(dp(a, 6), 0, dp(a, 6), 0);
        box.addView(m);
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(a, 18);
        box.addView(buttons, blp);
        Dialog[] holder = new Dialog[1];
        buttons.addView(button(a, "Cancel", TEXT, v -> holder[0].dismiss()));
        LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(-2, -2);
        ylp.leftMargin = dp(a, 8);
        buttons.addView(button(a, yes, destructive ? DANGER_FILLED : FILLED, v -> {
            holder[0].dismiss();
            onYes.run();
        }), ylp);
        holder[0] = sheet(a, titleText, box);
        holder[0].show();
        return holder[0];
    }

    // ------------------------------------------------------------------ messages

    /** A friendly message at the bottom of the screen, with an optional fix-it button. */
    static void snack(Activity a, String message, String action, Runnable onAction) {
        if (a == null || a.isFinishing()) return;
        load(a);
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return;
        View old = content.findViewWithTag("snack");
        if (old != null) content.removeView(old);

        LinearLayout bar = new LinearLayout(a);
        bar.setTag("snack");
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(dark ? 0xF2F4F4F6 : 0xF21C1C20, dp(a, 18)));
        bar.setPadding(dp(a, 18), dp(a, 6), dp(a, 6), dp(a, 6));
        bar.setMinimumHeight(dp(a, 52));
        bar.setElevation(dp(a, 6));
        TextView t = text(a, message, 14, dark ? 0xFF111114 : 0xFFF4F4F6, false);
        t.setMaxLines(3);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Runnable[] hide = new Runnable[1];
        if (action != null && onAction != null) {
            TextView b = text(a, action, 14, accent, true);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(a, 44));
            b.setPadding(dp(a, 14), 0, dp(a, 14), 0);
            b.setBackground(rippleRound(a, 0x00000000, 14));
            b.setOnClickListener(tap(v -> {
                hide[0].run();
                onAction.run();
            }));
            bar.addView(b);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        int side = dp(a, 12);
        lp.setMargins(side, 0, side, dp(a, 100));
        content.addView(bar, lp);
        bar.setAlpha(0f);
        bar.setTranslationY(dp(a, 24));
        bar.animate().alpha(1f).translationY(0).setDuration(220).start();
        hide[0] = () -> bar.animate().alpha(0f).translationY(dp(a, 24)).setDuration(180)
                .withEndAction(() -> content.removeView(bar)).start();
        bar.postDelayed(() -> {
            if (bar.getParent() != null) hide[0].run();
        }, action != null ? 5000 : 2800);
    }

    // ---------------------------------------------------------------- formatting

    static int avatarColor(String key) {
        if (key == null || key.isEmpty()) return AVATAR[0];
        return AVATAR[(key.hashCode() & 0x7fffffff) % AVATAR.length];
    }

    static String initials(String name) {
        if (name == null) return "";
        String n = name.trim();
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) return "";
        String[] parts = n.split("\\s+");
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(Character.toUpperCase(parts[0].codePointAt(0)));
        String last = parts[parts.length - 1];
        if (parts.length > 1 && !last.isEmpty() && Character.isLetter(last.charAt(0))) {
            sb.appendCodePoint(Character.toUpperCase(last.codePointAt(0)));
        }
        return sb.toString();
    }

    static String ago(long time) {
        long now = System.currentTimeMillis();
        long diff = now - time;
        if (diff < DateUtils.MINUTE_IN_MILLIS) return "Just now";
        if (diff < DateUtils.HOUR_IN_MILLIS) return (diff / DateUtils.MINUTE_IN_MILLIS) + " min ago";
        if (isSameDay(time, now)) return DateUtils.formatDateTime(app, time, DateUtils.FORMAT_SHOW_TIME);
        return DateUtils.formatDateTime(app, time, DateUtils.FORMAT_SHOW_TIME);
    }

    static String dayLabel(long time) {
        long now = System.currentTimeMillis();
        if (isSameDay(time, now)) return "Today";
        if (isSameDay(time, now - DateUtils.DAY_IN_MILLIS)) return "Yesterday";
        if (now - time < 6 * DateUtils.DAY_IN_MILLIS) {
            return DateUtils.formatDateTime(app, time, DateUtils.FORMAT_SHOW_WEEKDAY);
        }
        return DateUtils.formatDateTime(app, time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
    }

    static boolean isSameDay(long a, long b) {
        Calendar ca = Calendar.getInstance(), cb = Calendar.getInstance();
        ca.setTimeInMillis(a);
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    static String duration(long seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return seconds + " sec";
        long m = seconds / 60, s = seconds % 60;
        if (m < 60) return s == 0 ? m + " min" : m + " min " + s + " sec";
        return (m / 60) + " hr " + (m % 60) + " min";
    }

    static String clock(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }
}
