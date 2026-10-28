package com.orbitdial.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Orbit Dial: a circular dial pad. The twelve keys sit around a ring like the
 * hours on a clock face, with the call button in the middle.
 *
 *  - Tap a key            -> types that digit
 *  - Long-press 0         -> types "+"
 *  - Drag across 3+ keys  -> a "dial gesture" (e.g. 1 -> 5 -> 9) used for Gesture Speed Dial
 *  - Tap the centre       -> call
 */
public class OrbitDialView extends View {

    public interface Listener {
        void onDigit(char key);
        void onDigitLongPress(char key);
        void onCallPressed();
        void onGesture(String pattern);
    }

    static final char[] KEYS = {'1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#'};
    private static final String[] LETTERS = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};
    static final int MIN_GESTURE_KEYS = 3;
    private static final int MAX_GESTURE_KEYS = 8;

    private final float[] keyX = new float[12];
    private final float[] keyY = new float[12];
    private final float[] keyAngle = new float[12];
    private float cx, cy, radius, ringR, keyR, centerR, trackR;

    private final Paint keyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trail = new Path();

    private int accent = Color.parseColor("#7C5CFF");
    private int callColor = Color.parseColor("#22C55E");

    private Listener listener;
    private final List<Integer> path = new ArrayList<>();
    private boolean gestureMode;
    private boolean longPressFired;
    private boolean centerPressed;
    private float touchX, touchY;
    private float orbitAngle = -90f;
    private ValueAnimator orbitAnim;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!gestureMode && path.size() == 1 && listener != null) {
                longPressFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onDigitLongPress(KEYS[path.get(0)]);
            }
        }
    };

    public OrbitDialView(Context context) {
        this(context, null);
    }

    public OrbitDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        keyFill.setStyle(Paint.Style.FILL);
        keyStroke.setStyle(Paint.Style.STROKE);
        keyStroke.setStrokeWidth(dp(1.2f));
        keyStroke.setColor(Color.argb(60, 255, 255, 255));

        digitPaint.setColor(Color.WHITE);
        digitPaint.setTextAlign(Paint.Align.CENTER);
        digitPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        letterPaint.setColor(Color.argb(150, 255, 255, 255));
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setLetterSpacing(0.15f);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(1.5f));
        trackPaint.setColor(Color.argb(40, 255, 255, 255));

        orbitPaint.setStyle(Paint.Style.FILL);

        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(dp(6));
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);

        centerPaint.setStyle(Paint.Style.FILL);
        centerText.setColor(Color.WHITE);
        centerText.setTextAlign(Paint.Align.CENTER);
        centerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        centerText.setLetterSpacing(0.12f);

        setHapticFeedbackEnabled(true);
        setClickable(true);
        setContentDescription("Circular dial pad");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setColors(int accentColor, int callButtonColor) {
        accent = accentColor;
        callColor = callButtonColor;
        invalidate();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f;
        ringR = radius * 0.69f;
        keyR = radius * 0.165f;
        centerR = radius * 0.29f;
        trackR = radius * 0.94f;
        for (int i = 0; i < 12; i++) {
            // Key i sits at "hour" i+1 on a clock face: '1' at 1 o'clock ... '#' at 12 o'clock.
            float deg = -90f + 30f * (i + 1);
            keyAngle[i] = deg;
            double rad = Math.toRadians(deg);
            keyX[i] = cx + (float) (ringR * Math.cos(rad));
            keyY[i] = cy + (float) (ringR * Math.sin(rad));
        }
        digitPaint.setTextSize(keyR * 0.82f);
        letterPaint.setTextSize(keyR * 0.27f);
        centerText.setTextSize(centerR * 0.30f);
    }

    private int hitKey(float x, float y) {
        float limit = keyR * 1.08f;
        for (int i = 0; i < 12; i++) {
            float dx = x - keyX[i], dy = y - keyY[i];
            if (dx * dx + dy * dy <= limit * limit) return i;
        }
        return -1;
    }

    private boolean hitCenter(float x, float y) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= centerR * centerR;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                resetTouch();
                int k = hitKey(x, y);
                if (k >= 0) {
                    path.add(k);
                    touchX = x;
                    touchY = y;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    animateOrbitTo(keyAngle[k]);
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                if (hitCenter(x, y)) {
                    centerPressed = true;
                    invalidate();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!path.isEmpty()) {
                    touchX = x;
                    touchY = y;
                    int k = hitKey(x, y);
                    if (k >= 0 && k != path.get(path.size() - 1) && path.size() < MAX_GESTURE_KEYS && !longPressFired) {
                        path.add(k);
                        gestureMode = true;
                        removeCallbacks(longPressRunnable);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        animateOrbitTo(keyAngle[k]);
                    }
                    invalidate();
                } else if (centerPressed && !hitCenter(x, y)) {
                    centerPressed = false;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressRunnable);
                if (centerPressed && hitCenter(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    performClick();
                    if (listener != null) listener.onCallPressed();
                } else if (!path.isEmpty() && listener != null) {
                    if (gestureMode) {
                        if (path.size() >= MIN_GESTURE_KEYS) {
                            StringBuilder sb = new StringBuilder();
                            for (int idx : path) sb.append(KEYS[idx]);
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            listener.onGesture(sb.toString());
                        }
                    } else if (!longPressFired) {
                        performClick();
                        listener.onDigit(KEYS[path.get(0)]);
                    }
                }
                resetTouch();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                resetTouch();
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void resetTouch() {
        path.clear();
        gestureMode = false;
        longPressFired = false;
        centerPressed = false;
    }

    private void animateOrbitTo(float target) {
        float start = orbitAngle;
        float delta = ((target - start) % 360f + 540f) % 360f - 180f; // shortest way round
        if (orbitAnim != null) orbitAnim.cancel();
        orbitAnim = ValueAnimator.ofFloat(start, start + delta);
        orbitAnim.setDuration(260);
        orbitAnim.setInterpolator(new DecelerateInterpolator());
        orbitAnim.addUpdateListener(a -> {
            orbitAngle = (float) a.getAnimatedValue();
            invalidate();
        });
        orbitAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPressRunnable);
        if (orbitAnim != null) orbitAnim.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (radius <= 0) return;

        // Outer orbit track and the moving "satellite" that follows your finger.
        c.drawCircle(cx, cy, trackR, trackPaint);
        double oRad = Math.toRadians(orbitAngle);
        float ox = cx + (float) (trackR * Math.cos(oRad));
        float oy = cy + (float) (trackR * Math.sin(oRad));
        orbitPaint.setColor(accent);
        orbitPaint.setAlpha(60);
        c.drawCircle(ox, oy, dp(11), orbitPaint);
        orbitPaint.setAlpha(255);
        c.drawCircle(ox, oy, dp(5), orbitPaint);

        // Gesture trail.
        if (gestureMode && !path.isEmpty()) {
            trail.reset();
            trail.moveTo(keyX[path.get(0)], keyY[path.get(0)]);
            for (int i = 1; i < path.size(); i++) trail.lineTo(keyX[path.get(i)], keyY[path.get(i)]);
            trail.lineTo(touchX, touchY);
            trailPaint.setColor(accent);
            trailPaint.setAlpha(170);
            c.drawPath(trail, trailPaint);
        }

        // Keys.
        for (int i = 0; i < 12; i++) {
            boolean active = path.contains(i);
            if (active) {
                keyFill.setColor(accent);
                keyFill.setAlpha(path.get(path.size() - 1) == i ? 230 : 140);
            } else {
                keyFill.setColor(Color.argb(22, 255, 255, 255));
            }
            c.drawCircle(keyX[i], keyY[i], keyR, keyFill);
            c.drawCircle(keyX[i], keyY[i], keyR, keyStroke);

            boolean hasLetters = LETTERS[i].length() > 0;
            float digitY = keyY[i] - (digitPaint.descent() + digitPaint.ascent()) / 2f - (hasLetters ? keyR * 0.14f : 0f);
            c.drawText(String.valueOf(KEYS[i]), keyX[i], digitY, digitPaint);
            if (hasLetters) {
                c.drawText(LETTERS[i], keyX[i], keyY[i] + keyR * 0.58f, letterPaint);
            }
            if (gestureMode && active) {
                // Order badge: shows the step number inside the gesture.
                int step = path.indexOf(i) + 1;
                letterPaint.setColor(Color.WHITE);
                c.drawText(String.valueOf(step), keyX[i] + keyR * 0.62f, keyY[i] - keyR * 0.55f, letterPaint);
                letterPaint.setColor(Color.argb(150, 255, 255, 255));
            }
        }

        // Centre call button.
        centerPaint.setColor(callColor);
        if (centerPressed) centerPaint.setAlpha(170);
        c.drawCircle(cx, cy, centerR, centerPaint);
        centerPaint.setAlpha(255);
        float ty = cy - (centerText.descent() + centerText.ascent()) / 2f;
        c.drawText("CALL", cx, ty, centerText);
    }
}
