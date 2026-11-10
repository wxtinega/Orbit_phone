package com.orbitdial.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Round avatar: the contact's photo, otherwise initials on a soft colour, otherwise a person icon. */
class AvatarView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private String initials = "";
    private int color = 0xFFF86939;
    private Bitmap photo;
    private Object boundKey;
    private Drawable person;

    AvatarView(Context c) {
        super(c);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(String name, String colorKey) {
        initials = Ui.initials(name);
        color = Ui.avatarColor(colorKey == null ? name : colorKey);
        photo = null;
        photoPaint.setShader(null);
        invalidate();
    }

    void setBoundKey(Object key) {
        boundKey = key;
    }

    Object getBoundKey() {
        return boundKey;
    }

    void setPhoto(Bitmap b) {
        photo = b;
        photoPaint.setShader(b != null ? new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null);
        if (b != null) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(160).start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f, cx = w / 2f, cy = h / 2f;
        if (photo != null) {
            float scale = (2 * r) / Math.min(photo.getWidth(), photo.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - photo.getWidth() * scale / 2f, cy - photo.getHeight() * scale / 2f);
            photoPaint.getShader().setLocalMatrix(matrix);
            c.drawCircle(cx, cy, r, photoPaint);
            return;
        }
        // Tinted circle: soft in light mode, deeper in dark mode, with the colour as text.
        fill.setColor(Ui.alpha(color, Ui.dark ? 0x38 : 0x26));
        c.drawCircle(cx, cy, r, fill);
        if (initials.isEmpty()) {
            if (person == null) person = getContext().getDrawable(R.drawable.ic_person);
            if (person != null) {
                person.setTint(Ui.dark ? AvatarView.lighten(color) : color);
                int s = (int) (r * 0.95f);
                person.setBounds((int) cx - s / 2, (int) cy - s / 2, (int) cx + s / 2, (int) cy + s / 2);
                person.draw(c);
            }
            return;
        }
        textPaint.setColor(Ui.dark ? lighten(color) : color);
        textPaint.setTextSize(r * (initials.length() > 1 ? 0.72f : 0.86f));
        float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        c.drawText(initials, cx, ty, textPaint);
    }

    static int lighten(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        r += (255 - r) * 0.35f;
        g += (255 - g) * 0.35f;
        b += (255 - b) * 0.35f;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
