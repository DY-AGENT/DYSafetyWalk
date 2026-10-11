package com.dysafety.walk;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Embeds the current camera frame with the MobileCLIP-S2 image encoder and compares it
 * against pre-extracted "crosswalk" / "zebra crossing" text embeddings.
 * Semantic rather than pattern based, so it survives colored crossings and odd angles.
 */
public class ClipCrosswalk {

    public static final int SIZE = 256;
    private static final int DIM = 512;

    private Interpreter interpreter;
    private float[][] labelEmb;      // [2][512], already L2 normalized
    private ByteBuffer input;
    private float[][] output;
    private final int[] pixels = new int[SIZE * SIZE];
    private boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    public void init(Context ctx) throws Exception {
        MappedByteBuffer model = mapAsset(ctx, "mobileclip_s2_image.tflite");

        Interpreter.Options opt = new Interpreter.Options();
        opt.setNumThreads(2);
        interpreter = new Interpreter(model, opt);

        byte[] raw = readAll(ctx.getAssets().open("crosswalk_emb.f32"));
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = raw.length / 4 / DIM;
        labelEmb = new float[n][DIM];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < DIM; j++) labelEmb[i][j] = bb.getFloat();
        }

        input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4);
        input.order(ByteOrder.nativeOrder());
        output = new float[1][DIM];
        ready = true;
    }

    /** Highest cosine similarity between the frame and "crosswalk". -1 on failure. */
    public float score(Bitmap bmp) {
        if (!ready || bmp == null) return -1f;
        try {
            bmp.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
            input.rewind();
            // NCHW, scaled to 0-1 (MobileCLIP default preprocessing)
            for (int c = 0; c < 3; c++) {
                int shift = 16 - c * 8;   // R, G, B
                for (int i = 0; i < pixels.length; i++) {
                    input.putFloat(((pixels[i] >> shift) & 0xFF) / 255f);
                }
            }
            input.rewind();
            interpreter.run(input, output);

            float[] v = output[0];
            double norm = 0;
            for (float x : v) norm += x * x;
            norm = Math.sqrt(norm);
            if (norm <= 0) return -1f;

            float best = -1f;
            for (float[] le : labelEmb) {
                double dot = 0;
                for (int j = 0; j < DIM; j++) dot += v[j] * le[j];
                float sim = (float) (dot / norm);
                if (sim > best) best = sim;
            }
            return best;
        } catch (Throwable t) {
            return -1f;
        }
    }

    public void close() {
        ready = false;
        try {
            if (interpreter != null) interpreter.close();
        } catch (Exception ignored) {
        }
        interpreter = null;
    }

    static MappedByteBuffer mapAsset(Context ctx, String name) throws Exception {
        AssetFileDescriptor afd = ctx.getAssets().openFd(name);
        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        FileChannel ch = fis.getChannel();
        MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY,
                afd.getStartOffset(), afd.getDeclaredLength());
        try {
            fis.close();
            afd.close();
        } catch (Exception ignored) {
        }
        return mb;
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
