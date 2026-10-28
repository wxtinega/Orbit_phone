package com.orbitdial.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Slowly drifting colour glows behind the whole app. */
class LivelyBackground extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;
    private ValueAnimator anim;
    private int mood = Ui.ACCENT;

    LivelyBackground(Context c) {
        super(c);
    }

    /** Shifts the main glow colour, e.g. green while a number is ready to call. */
    void setMood(int color) {
        if (mood != color) {
            mood = color;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        anim.setDuration(14000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        c.drawColor(Ui.BG);
        float r = Math.max(w, h) * 0.55f;
        glow(c, w * (0.25f + 0.15f * (float) Math.sin(t)), h * (0.22f + 0.08f * (float) Math.cos(t * 2)), r, withAlpha(mood, 70));
        glow(c, w * (0.8f + 0.12f * (float) Math.cos(t)), h * (0.7f + 0.1f * (float) Math.sin(t)), r * 0.9f, withAlpha(Ui.CYAN, 40));
        glow(c, w * (0.5f + 0.3f * (float) Math.sin(t * 0.5f)), h * 1.05f, r * 0.8f, withAlpha(0xFFF472B6, 28));
    }

    private void glow(Canvas c, float x, float y, float r, int color) {
        paint.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
