package com.orbitdial.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A small dot that gently pulses: used for "recording" and "call in progress" indicators. */
class LivelyBackground extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float t;
    private int color;

    LivelyBackground(Context c, int color) {
        super(c);
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setColor(int c) {
        color = c;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1200);
        anim.setRepeatCount(ValueAnimator.INFINITE);
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
        float r = Math.min(getWidth(), getHeight()) / 2f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        paint.setColor(Ui.alpha(color, (int) (0x55 * (1f - t))));
        c.drawCircle(cx, cy, r * (0.5f + 0.5f * t), paint);
        paint.setColor(color);
        c.drawCircle(cx, cy, r * 0.45f, paint);
    }
}
