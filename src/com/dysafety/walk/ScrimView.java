package com.dysafety.walk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * Darkens only the bright regions of the app underneath so the camera image
 * does not get washed out on white screens.
 */
public class ScrimView extends View {

    private final int cols;
    private final int rows;
    private final int[] pixels;
    private final Bitmap bitmap;
    private final Paint paint = new Paint();
    private final Rect src;
    private final Rect dst = new Rect();

    public ScrimView(Context c, int cols, int rows) {
        super(c);
        this.cols = cols;
        this.rows = rows;
        this.pixels = new int[cols * rows];
        this.bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        this.src = new Rect(0, 0, cols, rows);
        paint.setFilterBitmap(true);
        paint.setAntiAlias(true);
        paint.setDither(true);
    }

    /** Dimming amount follows per-region brightness (0-1). */
    public void updateAdaptive(float[] lum, float maxDim) {
        if (lum == null || lum.length != pixels.length) return;
        for (int i = 0; i < pixels.length; i++) {
            float t = (lum[i] - 0.30f) / 0.70f;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
            int a = (int) (maxDim * t * 255f);
            pixels[i] = a << 24;
        }
        pushAndDraw();
    }

    /** Uniform dimming, used when screen analysis is off. */
    public void updateUniform(float dim) {
        int a = (int) (dim * 255f);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = a << 24;
        }
        pushAndDraw();
    }

    private void pushAndDraw() {
        bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, src, dst, paint);
    }
}
