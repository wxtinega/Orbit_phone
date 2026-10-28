package com.orbitdial.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Shared colours and tiny view helpers. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0B1020;
    static final int SURFACE = 0xFF161C33;
    static final int SURFACE2 = 0xFF222B4D;
    static final int ACCENT = 0xFF7C5CFF;
    static final int CYAN = 0xFF22D3EE;
    static final int GREEN = 0xFF22C55E;
    static final int RED = 0xFFF43F5E;
    static final int BLUE = 0xFF60A5FA;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFF8A93B8;

    private static final int[] AVATAR_COLORS = {
            0xFF7C5CFF, 0xFF22D3EE, 0xFFF472B6, 0xFFF59E0B, 0xFF34D399,
            0xFF60A5FA, 0xFFFB7185, 0xFFA78BFA, 0xFF2DD4BF, 0xFFFBBF24
    };

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A rounded background with a touch ripple. */
    static Drawable rippleBg(int color, float radiusPx) {
        GradientDrawable content = round(color, radiusPx);
        GradientDrawable mask = round(0xFFFFFFFF, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    static TextView pill(Context c, String label, int bg, View.OnClickListener click) {
        TextView t = text(c, label, 14, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        t.setBackground(rippleBg(bg, dp(c, 20)));
        t.setOnClickListener(click);
        return t;
    }

    static int colorFor(String key) {
        if (key == null || key.isEmpty()) return AVATAR_COLORS[0];
        return AVATAR_COLORS[(key.hashCode() & 0x7fffffff) % AVATAR_COLORS.length];
    }

    static String initials(String name) {
        if (name == null) return "#";
        String n = name.trim();
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) return "#";
        String[] parts = n.split("\\s+");
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1 && !parts[parts.length - 1].isEmpty() && Character.isLetter(parts[parts.length - 1].charAt(0))) {
            sb.append(Character.toUpperCase(parts[parts.length - 1].charAt(0)));
        }
        return sb.toString();
    }

    static String ago(long time) {
        long now = System.currentTimeMillis();
        if (now - time < DateUtils.MINUTE_IN_MILLIS) return "Just now";
        return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    static String duration(long seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return seconds + "s";
        long m = seconds / 60, s = seconds % 60;
        if (m < 60) return m + "m " + s + "s";
        return (m / 60) + "h " + (m % 60) + "m";
    }
}
