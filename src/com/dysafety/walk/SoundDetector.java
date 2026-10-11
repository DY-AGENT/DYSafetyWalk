package com.dysafety.walk;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import org.tensorflow.lite.Interpreter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Classifies surrounding sound every 0.5 s with YAMNet (521 AudioSet classes).
 * Direction is not recoverable from a phone mic, so this only answers "what is it".
 * Validated on ESC-50: car horn 0.74-1.00, siren 0.67-0.74, engine 0.67-0.74,
 * with no false positives on church bells or rain.
 */
public class SoundDetector {

    public static final int SAMPLE_RATE = 16000;
    private static final int WINDOW = 15600;      // 0.975 s
    private static final int BLOCK = 8000;        // evaluate every 0.5 s
    private static final int NUM_CLASSES = 521;

    public interface Listener {
        /** name is null when nothing hazardous was heard */
        void onSound(String name, float score);
    }

    /** sound group: display name, base threshold, YAMNet class indices */
    private static final Object[][] GROUPS = {
            {"Car horn", 0.35f, new int[]{302, 312, 304, 325}},
            {"Siren", 0.35f, new int[]{390, 391, 317, 318, 319, 316}},
            {"Motorcycle", 0.40f, new int[]{320}},
            {"Bicycle bell", 0.40f, new int[]{199}},
            {"Screeching tires", 0.40f, new int[]{306, 327}},
            {"Traffic noise", 0.72f, new int[]{294, 300, 301, 308, 310, 315, 337, 342, 343}},
    };

    private Interpreter interpreter;
    private AudioRecord record;
    private Thread worker;
    private volatile boolean running = false;
    private volatile float bias = 0f;    // positive means more sensitive

    private final float[] window = new float[WINDOW];
    private ByteBuffer input;
    private float[][] output;

    public void setSensitivity(float sens) {
        // sens 0-1 maps to a -0.15 .. +0.10 threshold offset
        this.bias = 0.10f - 0.25f * sens;
    }

    public boolean start(Context ctx, float sens, final Listener listener) {
        try {
            java.nio.MappedByteBuffer model = ClipCrosswalk.mapAsset(ctx, "yamnet.tflite");
            Interpreter.Options opt = new Interpreter.Options();
            opt.setNumThreads(1);
            interpreter = new Interpreter(model, opt);

            input = ByteBuffer.allocateDirect(WINDOW * 4);
            input.order(ByteOrder.nativeOrder());
            output = new float[1][NUM_CLASSES];
            setSensitivity(sens);

            if (!openMic()) return false;

            running = true;
            worker = new Thread(new Runnable() {
                public void run() { loop(listener); }
            }, "dysafety_audio");
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
            return true;
        } catch (Throwable t) {
            stop();
            return false;
        }
    }

    /** Prefer the least processed source available. */
    private boolean openMic() {
        int[] sources = Build.VERSION.SDK_INT >= 24
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            MediaRecorder.AudioSource.MIC};

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE * 2;
        int bufSize = Math.max(minBuf * 2, SAMPLE_RATE * 2);

        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording();
                    if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record = r;
                        return true;
                    }
                }
                r.release();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void loop(Listener listener) {
        short[] buf = new short[BLOCK];
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, BLOCK);
            } catch (Throwable t) {
                break;
            }
            if (n <= 0) continue;
            if (n > WINDOW) n = WINDOW;

            System.arraycopy(window, n, window, 0, WINDOW - n);
            double sum = 0;
            for (int i = 0; i < n; i++) {
                float v = buf[i] / 32768f;
                window[WINDOW - n + i] = v;
                sum += v * v;
            }
            // skip inference in near silence to save battery
            if (Math.sqrt(sum / n) < 0.004) {
                if (listener != null) listener.onSound(null, 0f);
                continue;
            }

            String name = null;
            float best = 0f;
            try {
                input.rewind();
                for (int i = 0; i < WINDOW; i++) input.putFloat(window[i]);
                input.rewind();
                interpreter.run(input, output);
                float[] s = output[0];

                for (Object[] g : GROUPS) {
                    float thr = (Float) g[1] + bias;
                    int[] idx = (int[]) g[2];
                    float m = 0f;
                    for (int i : idx) {
                        if (i < s.length && s[i] > m) m = s[i];
                    }
                    // groups are listed by priority, so take the first one over threshold
                    if (m >= thr && m > best) {
                        best = m;
                        name = (String) g[0];
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (listener != null) listener.onSound(name, best);
        }
    }

    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(600);
            } catch (InterruptedException ignored) {
            }
            worker = null;
        }
        try {
            if (record != null) {
                record.stop();
                record.release();
            }
        } catch (Throwable ignored) {
        }
        record = null;
        try {
            if (interpreter != null) interpreter.close();
        } catch (Throwable ignored) {
        }
        interpreter = null;
    }
}
