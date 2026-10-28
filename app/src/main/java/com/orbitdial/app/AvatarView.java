package com.orbitdial.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Round avatar: the contact photo if there is one, otherwise coloured initials. */
class AvatarView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private String initials = "#";
    private int color = Ui.ACCENT;
    private Bitmap photo;
    private Object boundKey;

    AvatarView(Context c) {
        super(c);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    void set(String name, String colorKey) {
        initials = Ui.initials(name);
        color = Ui.colorFor(colorKey == null ? name : colorKey);
        photo = null;
        photoPaint.setShader(null);
        invalidate();
    }

    /** Tag used by lists so a late-arriving photo isn't drawn into a recycled row. */
    void setBoundKey(Object key) {
        boundKey = key;
    }

    Object getBoundKey() {
        return boundKey;
    }

    void setPhoto(Bitmap b) {
        photo = b;
        if (b != null) {
            photoPaint.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            photoPaint.setShader(null);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f;
        float cx = w / 2f, cy = h / 2f;
        if (photo != null) {
            float scale = (2 * r) / Math.min(photo.getWidth(), photo.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - photo.getWidth() * scale / 2f, cy - photo.getHeight() * scale / 2f);
            photoPaint.getShader().setLocalMatrix(matrix);
            c.drawCircle(cx, cy, r, photoPaint);
            return;
        }
        int darker = blend(color, 0xFF000000, 0.35f);
        fill.setShader(new LinearGradient(0, 0, w, h, color, darker, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, fill);
        textPaint.setTextSize(r * (initials.length() > 1 ? 0.78f : 0.9f));
        float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        c.drawText(initials, cx, ty, textPaint);
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
