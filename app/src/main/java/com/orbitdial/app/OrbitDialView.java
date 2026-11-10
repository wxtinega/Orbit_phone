package com.orbitdial.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The dial pad, in two styles that behave the same:
 * ORBIT - twelve keys around a ring like a clock, with the call button in the middle;
 * GRID  - the classic 3 x 4 layout.
 * Tap = digit, long-press 0 = "+", drag across 3+ keys = Gesture Speed Dial.
 */
public class OrbitDialView extends View {

    public interface Listener {
        void onDigit(char key);
        void onDigitLongPress(char key);
        void onCallPressed();
        void onGesture(String pattern);
    }

    static final int ORBIT = 0, GRID = 1;
    static final char[] KEYS = {'1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#'};
    private static final String[] LETTERS = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};
    private static final int MIN_GESTURE = 3, MAX_GESTURE = 8;
    private static final long FADE_MS = 240;

    private int style = ORBIT;
    private final float[] kx = new float[12], ky = new float[12], angle = new float[12];
    private float cx, cy, radius, keyR, centerR, trackR;

    private final Paint keyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letters = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint callPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trailPath = new Path();
    private Drawable callIcon;

    private Listener listener;
    private final List<Integer> path = new ArrayList<>();
    private boolean gesture, longFired, centerDown, ready;
    private float touchX, touchY;
    private final boolean[] down = new boolean[12];
    private final long[] releasedAt = new long[12];
    private float satellite = -60f;
    private ValueAnimator satAnim, pulse;
    private float pulseT, centerScale = 1f;

    private final Runnable longPress = () -> {
        if (!gesture && path.size() == 1 && listener != null) {
            longFired = true;
            haptic(HapticFeedbackConstants.LONG_PRESS);
            listener.onDigitLongPress(KEYS[path.get(0)]);
        }
    };

    public OrbitDialView(Context c) {
        super(c);
        keyEdge.setStyle(Paint.Style.STROKE);
        digit.setTextAlign(Paint.Align.CENTER);
        digit.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        letters.setTextAlign(Paint.Align.CENTER);
        letters.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letters.setLetterSpacing(0.14f);
        track.setStyle(Paint.Style.STROKE);
        trail.setStyle(Paint.Style.STROKE);
        trail.setStrokeCap(Paint.Cap.ROUND);
        trail.setStrokeJoin(Paint.Join.ROUND);
        badge.setTextAlign(Paint.Align.CENTER);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        callIcon = c.getDrawable(R.drawable.ic_call);
        setContentDescription("Dial pad");
        setHapticFeedbackEnabled(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setStyle(int s) {
        style = s;
        requestLayout();
        layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    boolean hasCallButton() {
        return style == ORBIT;
    }

    /** When there's a number to call, the call button breathes gently. */
    void setReady(boolean r) {
        if (ready == r) return;
        ready = r;
        if (r && style == ORBIT) {
            pulse = ValueAnimator.ofFloat(0f, 1f);
            pulse.setDuration(1600);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> {
                pulseT = (float) a.getAnimatedValue();
                invalidate();
            });
            pulse.start();
        } else if (pulse != null) {
            pulse.cancel();
            pulse = null;
            pulseT = 0;
        }
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void haptic(int type) {
        if (Prefs.haptics(getContext())) performHapticFeedback(type);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.EXACTLY) {
            h = style == ORBIT ? Math.min(w, (int) dp(380)) : (int) Math.min(w * 1.2f, dp(400));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int w, int h) {
        if (w <= 0 || h <= 0) return;
        cx = w / 2f;
        cy = h / 2f;
        if (style == ORBIT) {
            radius = Math.min(Math.min(w, h) / 2f, dp(200));
            float ring = radius * 0.70f;
            keyR = radius * 0.165f;
            centerR = radius * 0.30f;
            trackR = radius * 0.955f;
            for (int i = 0; i < 12; i++) {
                float deg = -90f + 30f * (i + 1);
                angle[i] = deg;
                double rad = Math.toRadians(deg);
                kx[i] = cx + (float) (ring * Math.cos(rad));
                ky[i] = cy + (float) (ring * Math.sin(rad));
            }
        } else {
            float cell = Math.min(Math.min(w / 3f, h / 4f), dp(104));
            keyR = Math.min(cell * 0.43f, dp(40));
            float left = cx - cell * 1.5f, top = cy - cell * 2f;
            for (int i = 0; i < 12; i++) {
                int col = i % 3, row = i / 3;
                kx[i] = left + cell * (col + 0.5f);
                ky[i] = top + cell * (row + 0.5f);
                angle[i] = (float) Math.toDegrees(Math.atan2(ky[i] - cy, kx[i] - cx));
            }
            radius = cell * 2f;
        }
        digit.setTextSize(keyR * (style == ORBIT ? 0.80f : 0.74f));
        letters.setTextSize(Math.max(dp(8), keyR * 0.23f));
        badge.setTextSize(Math.max(dp(9), keyR * 0.28f));
    }

    private int hit(float x, float y) {
        float lim = keyR * (style == GRID ? 1.15f : 1.12f);
        int best = -1;
        float bestD = lim * lim;
        for (int i = 0; i < 12; i++) {
            float dx = x - kx[i], dy = y - ky[i], d = dx * dx + dy * dy;
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private boolean onCenter(float x, float y) {
        if (style != ORBIT) return false;
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= centerR * centerR;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                reset();
                int k = hit(x, y);
                if (k >= 0) {
                    press(k);
                    touchX = x;
                    touchY = y;
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (onCenter(x, y)) {
                    centerDown = true;
                    animateCenter(0.92f);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE:
                if (!path.isEmpty()) {
                    touchX = x;
                    touchY = y;
                    int k = hit(x, y);
                    int last = path.get(path.size() - 1);
                    if (k >= 0 && k != last && !path.contains(k) && path.size() < MAX_GESTURE && !longFired) {
                        removeCallbacks(longPress);
                        gesture = true;
                        release(last);
                        press(k);
                        haptic(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                } else if (centerDown && !onCenter(x, y)) {
                    centerDown = false;
                    animateCenter(1f);
                }
                return true;
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (centerDown) {
                    animateCenter(1f);
                    if (onCenter(x, y) && listener != null) {
                        haptic(HapticFeedbackConstants.VIRTUAL_KEY);
                        performClick();
                        listener.onCallPressed();
                    }
                } else if (!path.isEmpty() && listener != null) {
                    if (gesture) {
                        if (path.size() >= MIN_GESTURE) {
                            StringBuilder sb = new StringBuilder();
                            for (int i : path) sb.append(KEYS[i]);
                            haptic(HapticFeedbackConstants.LONG_PRESS);
                            listener.onGesture(sb.toString());
                        }
                    } else if (!longFired) {
                        performClick();
                        listener.onDigit(KEYS[path.get(0)]);
                    }
                }
                for (int i : path) release(i);
                reset();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                for (int i : path) release(i);
                if (centerDown) animateCenter(1f);
                reset();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void press(int k) {
        path.add(k);
        down[k] = true;
        moveSatellite(angle[k]);
        invalidate();
    }

    private void release(int k) {
        if (down[k]) {
            down[k] = false;
            releasedAt[k] = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    private void reset() {
        path.clear();
        gesture = false;
        longFired = false;
        centerDown = false;
    }

    private void animateCenter(float to) {
        ValueAnimator a = ValueAnimator.ofFloat(centerScale, to);
        a.setDuration(to < 1 ? 90 : 260);
        a.setInterpolator(to < 1 ? new DecelerateInterpolator() : new android.view.animation.OvershootInterpolator(2.4f));
        a.addUpdateListener(v -> {
            centerScale = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    private void moveSatellite(float target) {
        if (style != ORBIT) return;
        float start = satellite;
        float delta = ((target - start) % 360f + 540f) % 360f - 180f;
        if (satAnim != null) satAnim.cancel();
        satAnim = ValueAnimator.ofFloat(start, start + delta);
        satAnim.setDuration(300);
        satAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        satAnim.addUpdateListener(a -> {
            satellite = (float) a.getAnimatedValue();
            invalidate();
        });
        satAnim.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ready && style == ORBIT && pulse == null) {
            ready = false;
            setReady(true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPress);
        if (satAnim != null) satAnim.cancel();
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
    }

    /** 0 = resting, 1 = pressed; fades back after release. */
    private float level(int k, long now) {
        if (down[k]) return 1f;
        long t = now - releasedAt[k];
        return t < FADE_MS ? 1f - t / (float) FADE_MS : 0f;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (keyR <= 0) return;
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        int accent = Ui.accent;

        if (style == ORBIT) {
            track.setStrokeWidth(dp(1));
            track.setColor(Ui.faint);
            c.drawCircle(cx, cy, trackR, track);
            double r = Math.toRadians(satellite);
            float sx = cx + (float) (trackR * Math.cos(r)), sy = cy + (float) (trackR * Math.sin(r));
            dot.setColor(Ui.alpha(accent, 0x40));
            c.drawCircle(sx, sy, dp(9), dot);
            dot.setColor(accent);
            c.drawCircle(sx, sy, dp(4), dot);
        }

        if (gesture && path.size() > 0) {
            trailPath.reset();
            trailPath.moveTo(kx[path.get(0)], ky[path.get(0)]);
            for (int i = 1; i < path.size(); i++) trailPath.lineTo(kx[path.get(i)], ky[path.get(i)]);
            trailPath.lineTo(touchX, touchY);
            trail.setStrokeWidth(dp(14));
            trail.setColor(Ui.alpha(accent, 0x33));
            c.drawPath(trailPath, trail);
            trail.setStrokeWidth(dp(4));
            trail.setColor(accent);
            c.drawPath(trailPath, trail);
        }

        keyEdge.setStrokeWidth(Math.max(1f, dp(0.8f)));
        for (int i = 0; i < 12; i++) {
            float lv = level(i, now);
            if (lv > 0 && !down[i]) animating = true;
            float r = keyR * (1f - 0.05f * lv);
            keyFill.setColor(Ui.glassStrong);
            c.drawCircle(kx[i], ky[i], r, keyFill);
            if (lv > 0) {
                keyFill.setColor(Ui.alpha(accent, (int) (0xE6 * lv)));
                c.drawCircle(kx[i], ky[i], r, keyFill);
            }
            keyEdge.setColor(Ui.faint);
            c.drawCircle(kx[i], ky[i], r, keyEdge);

            boolean hasLetters = LETTERS[i].length() > 0;
            int dColor = lv > 0.5f ? Ui.onAccent : Ui.text;
            digit.setColor(dColor);
            float baseline = ky[i] - (digit.descent() + digit.ascent()) / 2f - (hasLetters ? keyR * 0.13f : 0f);
            c.drawText(String.valueOf(KEYS[i]), kx[i], baseline, digit);
            if (hasLetters) {
                letters.setColor(lv > 0.5f ? Ui.alpha(Ui.onAccent, 0xCC) : Ui.muted);
                c.drawText(LETTERS[i], kx[i], ky[i] + keyR * 0.55f, letters);
            }
            if (gesture && path.contains(i)) {
                int step = path.indexOf(i) + 1;
                float bx = kx[i] + keyR * 0.72f, by = ky[i] - keyR * 0.72f, br = Math.max(dp(9), keyR * 0.24f);
                badge.setColor(Ui.text);
                c.drawCircle(bx, by, br, badge);
                badge.setColor(Ui.dark ? 0xFF111114 : 0xFFFFFFFF);
                c.drawText(String.valueOf(step), bx, by - (badge.descent() + badge.ascent()) / 2f, badge);
            }
        }

        if (style == ORBIT) {
            float cr = centerR * centerScale;
            if (ready) {
                float s = (float) Math.sin(pulseT * Math.PI);
                glow.setColor(Ui.alpha(accent, (int) (0x30 + 0x30 * s)));
                c.drawCircle(cx, cy, cr * (1.10f + 0.12f * s), glow);
            }
            callPaint.setColor(accent);
            c.drawCircle(cx, cy, cr, callPaint);
            if (callIcon != null) {
                callIcon.setTint(Ui.onAccent);
                int s = (int) (cr * 0.82f);
                callIcon.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
                callIcon.draw(c);
            }
        }
        if (animating) postInvalidateOnAnimation();
    }
}
