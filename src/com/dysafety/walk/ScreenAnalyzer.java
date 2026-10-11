package com.dysafety.walk;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;

import java.nio.ByteBuffer;

/**
 * Captures the screen at a very small size and computes average brightness per region.
 * Pixel data is never stored or sent anywhere.
 */
public class ScreenAnalyzer {

    public static final int COLS = 4;
    public static final int ROWS = 8;

    public interface Listener {
        void onLuminance(float[] grid, float mean);
    }

    private static final int INTERVAL_MS = 200;

    private ImageReader reader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection projection;
    private Handler handler;
    private Listener listener;
    private boolean active = false;

    private final float[] smooth = new float[COLS * ROWS];
    private boolean primed = false;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!active) return;
            sample();
            if (handler != null) handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public boolean start(MediaProjection mp, int screenW, int screenH, int dpi,
                         Handler bgHandler, Listener l) {
        try {
            projection = mp;
            handler = bgHandler;
            listener = l;

            int w = Math.max(40, screenW / 10);
            int h = Math.max(40, screenH / 10);
            w = w - (w % 2);
            h = h - (h % 2);

            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "dysafety_probe", w, h, Math.max(80, dpi / 10),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, bgHandler);

            if (virtualDisplay == null) {
                stop();
                return false;
            }
            active = true;
            handler.postDelayed(tick, 400);
            return true;
        } catch (Exception e) {
            stop();
            return false;
        }
    }

    private void sample() {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;

            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixStride = plane.getPixelStride();
            int w = img.getWidth();
            int h = img.getHeight();

            float[] sum = new float[COLS * ROWS];
            int[] cnt = new int[COLS * ROWS];

            for (int y = 0; y < h; y += 2) {
                int base = y * rowStride;
                int gy = y * ROWS / h;
                for (int x = 0; x < w; x += 2) {
                    int off = base + x * pixStride;
                    if (off + 2 >= buf.capacity()) continue;
                    int r = buf.get(off) & 0xFF;
                    int g = buf.get(off + 1) & 0xFF;
                    int b = buf.get(off + 2) & 0xFF;
                    float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                    int gx = x * COLS / w;
                    int idx = gy * COLS + gx;
                    sum[idx] += lum;
                    cnt[idx]++;
                }
            }

            float total = 0;
            int totalCells = 0;
            for (int i = 0; i < sum.length; i++) {
                float v = cnt[i] > 0 ? sum[i] / cnt[i] : 0.5f;
                if (!primed) {
                    smooth[i] = v;
                } else {
                    // slow smoothing keeps the feedback loop from oscillating
                    smooth[i] = smooth[i] * 0.75f + v * 0.25f;
                }
                total += smooth[i];
                totalCells++;
            }
            primed = true;

            if (listener != null && totalCells > 0) {
                listener.onLuminance(smooth.clone(), total / totalCells);
            }
        } catch (Exception ignored) {
        } finally {
            if (img != null) {
                try {
                    img.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void stop() {
        active = false;
        if (handler != null) handler.removeCallbacks(tick);
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        virtualDisplay = null;
        reader = null;
        projection = null;
        listener = null;
        primed = false;
    }
}
