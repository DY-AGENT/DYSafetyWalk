package com.dysafety.walk;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs Google MediaPipe's EfficientDet-Lite0 (int8, COCO) object detector.
 * That model ships raw anchor outputs with no built-in NMS, so anchor generation,
 * box decoding and NMS all happen here. Scores already have sigmoid applied,
 * so no extra activation is used.
 */
public class Detector {

    public static final int SIZE = 320;
    private static final int NUM_ANCHORS = 19206;
    private static final int NUM_CLASSES = 90;
    private static final float ANCHOR_SCALE = 3.0f;
    private static final int[] LEVELS = {3, 4, 5, 6, 7};
    private static final float[][] ASPECTS = {{1.0f, 1.0f}, {1.4f, 0.7f}, {0.7f, 1.4f}};
    private static final int NUM_OCTAVES = 3;

    public static class Det {
        public String label;      // raw COCO label
        public String name;       // display name
        public int level;         // 3 danger / 2 caution / 1 obstacle
        public float score;
        public float x1, y1, x2, y2;   // normalized 0-1
        public float area;
        public float risk;
    }

    /** label -> {display name, danger level} */
    private static final Map<String, Object[]> DANGER = new HashMap<>();

    static {
        DANGER.put("car", new Object[]{"Car", 3});
        DANGER.put("truck", new Object[]{"Car", 3});
        DANGER.put("bus", new Object[]{"Bus", 3});
        DANGER.put("bicycle", new Object[]{"Bicycle", 3});
        DANGER.put("motorcycle", new Object[]{"Scooter", 3});
    }

    private Interpreter interpreter;
    private String[] labels;
    private int[] watchIdx;          // class indices we care about
    private final float[] ya = new float[NUM_ANCHORS];
    private final float[] xa = new float[NUM_ANCHORS];
    private final float[] ha = new float[NUM_ANCHORS];
    private final float[] wa = new float[NUM_ANCHORS];

    private ByteBuffer input;
    private float[][][] outScores;
    private float[][][] outBoxes;
    private int scoreOutIdx = 0;
    private int boxOutIdx = 1;
    private final int[] pixelBuf = new int[SIZE * SIZE];
    private boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    public void init(Context ctx) throws Exception {
        AssetManager am = ctx.getAssets();

        labels = readLines(am.open("coco_labels.txt"));

        List<Integer> watch = new ArrayList<>();
        for (int i = 0; i < labels.length && i < NUM_CLASSES; i++) {
            if (DANGER.containsKey(labels[i])) watch.add(i);
        }
        watchIdx = new int[watch.size()];
        for (int i = 0; i < watchIdx.length; i++) watchIdx[i] = watch.get(i);

        java.nio.MappedByteBuffer mb =
                ClipCrosswalk.mapAsset(ctx, "efficientdet_lite0.tflite");

        Interpreter.Options opt = new Interpreter.Options();
        opt.setNumThreads(2);
        interpreter = new Interpreter(mb, opt);

        // Output order can vary, so identify by shape.
        int[] s0 = interpreter.getOutputTensor(0).shape();
        if (s0.length == 3 && s0[2] == 4) {
            boxOutIdx = 0;
            scoreOutIdx = 1;
        } else {
            scoreOutIdx = 0;
            boxOutIdx = 1;
        }

        input = ByteBuffer.allocateDirect(SIZE * SIZE * 3);
        input.order(ByteOrder.nativeOrder());
        outScores = new float[1][NUM_ANCHORS][NUM_CLASSES];
        outBoxes = new float[1][NUM_ANCHORS][4];

        buildAnchors();
        ready = true;
    }

    private void buildAnchors() {
        int[] featSizes = new int[8];
        featSizes[0] = SIZE;
        for (int i = 1; i <= 7; i++) {
            featSizes[i] = (int) Math.ceil(featSizes[i - 1] / 2.0);
        }
        int p = 0;
        for (int level : LEVELS) {
            int feat = featSizes[level];
            float stride = SIZE / (float) feat;
            // layout: (y, x) location outer, (octave, aspect) inner
            for (int yi = 0; yi < feat; yi++) {
                float cy = stride / 2f + yi * stride;
                for (int xi = 0; xi < feat; xi++) {
                    float cx = stride / 2f + xi * stride;
                    for (int oct = 0; oct < NUM_OCTAVES; oct++) {
                        float base = ANCHOR_SCALE * stride
                                * (float) Math.pow(2.0, oct / (double) NUM_OCTAVES);
                        for (float[] asp : ASPECTS) {
                            ya[p] = cy;
                            xa[p] = cx;
                            ha[p] = base * asp[1];
                            wa[p] = base * asp[0];
                            p++;
                        }
                    }
                }
            }
        }
    }

    /** Runs one 320x320 frame. */
    public List<Det> detect(Bitmap bmp, float threshold) {
        List<Det> result = new ArrayList<>();
        if (!ready || bmp == null) return result;

        bmp.getPixels(pixelBuf, 0, SIZE, 0, 0, SIZE, SIZE);
        input.rewind();
        for (int i = 0; i < pixelBuf.length; i++) {
            int c = pixelBuf[i];
            input.put((byte) ((c >> 16) & 0xFF));
            input.put((byte) ((c >> 8) & 0xFF));
            input.put((byte) (c & 0xFF));
        }
        input.rewind();

        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(scoreOutIdx, outScores);
        outputs.put(boxOutIdx, outBoxes);
        try {
            interpreter.runForMultipleInputsOutputs(new Object[]{input}, outputs);
        } catch (Exception e) {
            return result;
        }

        float[][] scores = outScores[0];
        float[][] boxes = outBoxes[0];

        for (int ci : watchIdx) {
            List<Det> perClass = null;
            for (int a = 0; a < NUM_ANCHORS; a++) {
                float sc = scores[a][ci];
                if (sc < threshold) continue;

                float[] b = boxes[a];
                float cy = b[0] * ha[a] + ya[a];
                float cx = b[1] * wa[a] + xa[a];
                float h = (float) Math.exp(b[2]) * ha[a];
                float w = (float) Math.exp(b[3]) * wa[a];

                Det d = new Det();
                d.label = labels[ci];
                Object[] meta = DANGER.get(d.label);
                d.name = meta == null ? d.label : (String) meta[0];
                d.level = meta == null ? 1 : (Integer) meta[1];
                d.score = sc;
                d.y1 = clamp01((cy - h / 2f) / SIZE);
                d.x1 = clamp01((cx - w / 2f) / SIZE);
                d.y2 = clamp01((cy + h / 2f) / SIZE);
                d.x2 = clamp01((cx + w / 2f) / SIZE);
                d.area = Math.max(0f, (d.x2 - d.x1) * (d.y2 - d.y1));
                // bigger on screen means closer, so riskier
                d.risk = d.score * (0.45f + 0.55f * Math.min(1f, (float) Math.sqrt(d.area) * 2.2f));

                if (perClass == null) perClass = new ArrayList<>();
                perClass.add(d);
            }
            if (perClass != null) {
                nms(perClass, 0.5f);
                result.addAll(perClass);
            }
        }

        Collections.sort(result, new Comparator<Det>() {
            public int compare(Det a, Det b) {
                return Float.compare(b.risk, a.risk);
            }
        });
        if (result.size() > 12) {
            result = new ArrayList<>(result.subList(0, 12));
        }
        return result;
    }

    private static void nms(List<Det> list, float iouThr) {
        Collections.sort(list, new Comparator<Det>() {
            public int compare(Det a, Det b) {
                return Float.compare(b.score, a.score);
            }
        });
        for (int i = 0; i < list.size(); i++) {
            Det a = list.get(i);
            for (int j = list.size() - 1; j > i; j--) {
                if (iou(a, list.get(j)) > iouThr) list.remove(j);
            }
        }
    }

    private static float iou(Det a, Det b) {
        float ix1 = Math.max(a.x1, b.x1);
        float iy1 = Math.max(a.y1, b.y1);
        float ix2 = Math.min(a.x2, b.x2);
        float iy2 = Math.min(a.y2, b.y2);
        float iw = Math.max(0, ix2 - ix1);
        float ih = Math.max(0, iy2 - iy1);
        float inter = iw * ih;
        float ua = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter;
        return ua <= 0 ? 0 : inter / ua;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public void close() {
        ready = false;
        try {
            if (interpreter != null) interpreter.close();
        } catch (Exception ignored) {
        }
        interpreter = null;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4 << 20);
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static String[] readLines(InputStream in) throws Exception {
        String s = new String(readAll(in), "UTF-8");
        String[] raw = s.split("\n");
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = raw[i].trim();
        return out;
    }
}
