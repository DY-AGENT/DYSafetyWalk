package com.dysafety.walk;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class OverlayService extends Service {

    public static final String ACTION_START = "com.dysafety.walk.START";
    public static final String ACTION_STOP = "com.dysafety.walk.STOP";
    public static final String ACTION_UPDATE = "com.dysafety.walk.UPDATE";
    public static final String EXTRA_MP_RESULT = "mp_result";
    public static final String EXTRA_MP_DATA = "mp_data";

    private static final String CHANNEL_ID = "dysafety_overlay";
    private static final int NOTI_ID = 4711;
    private static final int DETECT_INTERVAL = 400;
    private static final int CLIP_INTERVAL = 1500;
    private static final long CROSSWALK_HOLD = 2600;
    private static final long SOUND_HOLD = 2600;
    private static final long WARN_HOLD_MS = 1400;
    private static final long VIBRATE_COOLDOWN = 3500;

    private static boolean running = false;
    private static boolean analyzing = false;
    private static boolean detecting = false;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isAnalyzing() {
        return analyzing;
    }

    public static boolean isDetecting() {
        return detecting;
    }

    private WindowManager wm;
    private FrameLayout overlayRoot;
    private ScrimView scrim;
    private TextureView textureView;
    private WarnView warnView;
    private LinearLayout panel;
    private TextView alphaText;

    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams panelParams;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private HandlerThread detThread;
    private Handler detHandler;
    private HandlerThread clipThread;
    private Handler clipHandler;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ScreenAnalyzer analyzer;
    private Detector detector;
    private Bitmap detBmp;
    private final AtomicBoolean detBusy = new AtomicBoolean(false);

    private SoundDetector sound;
    private String soundName = null;
    private long soundUntil = 0;

    private ClipCrosswalk clip;
    private Bitmap clipBmp;
    private final AtomicBoolean clipBusy = new AtomicBoolean(false);
    private long crosswalkUntil = 0;

    private long lastWarnAt = 0;
    private long lastVibrateAt = 0;
    private String lastWarnText = null;
    private String lastWarnSub = null;
    private int lastWarnLevel = 0;
    private List<Detector.Det> lastDets = new ArrayList<>();

    private final Paint layerPaint = new Paint();
    private Size previewSize;

    // settings
    private float baseAlpha = 0.35f;
    private float contrast = 0.45f;
    private float maxDim = 0.35f;
    private boolean adaptive = true;
    private boolean autoAlpha = true;
    private boolean autoTone = true;
    private int scaleMode = 0;
    private boolean showBar = false;
    private boolean detectEnabled = true;
    private float detThreshold = 0.44f;
    private boolean vibrateOn = true;
    private boolean crosswalkEnabled = true;
    private float cwThreshold = 0.235f;
    private float warnSize = 1.0f;
    private boolean soundEnabled = true;
    private float soundSens = 0.5f;

    private float bgLum = 0.5f;
    private boolean viewsAdded = false;
    private long lastToneUpdate = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        loadPrefs();

        if (ACTION_UPDATE.equals(action) || running) {
            applySettings();
            return START_STICKY;
        }

        int mpResult = intent == null ? 0 : intent.getIntExtra(EXTRA_MP_RESULT, 0);
        Intent mpData = intent == null ? null
                : (Intent) intent.getParcelableExtra(EXTRA_MP_DATA);
        boolean wantProjection = adaptive && mpData != null && mpResult != 0;

        startForegroundSafely(wantProjection);

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
            stopEverything();
            return START_NOT_STICKY;
        }

        running = true;
        startThreads();
        addViews();

        if (wantProjection) startAnalyzer(mpResult, mpData);
        if (detectEnabled) initDetector();
        if (crosswalkEnabled) initClip();
        if (soundEnabled) initSound();

        return START_STICKY;
    }

    private void loadPrefs() {
        baseAlpha = Prefs.alpha(this);
        contrast = Prefs.contrast(this);
        maxDim = Prefs.dim(this);
        adaptive = Prefs.adaptive(this);
        autoAlpha = Prefs.autoAlpha(this);
        autoTone = Prefs.autoTone(this);
        scaleMode = Prefs.scaleMode(this);
        showBar = Prefs.showBar(this);
        detectEnabled = Prefs.detect(this);
        detThreshold = Prefs.threshold(this);
        vibrateOn = Prefs.vibrate(this);
        crosswalkEnabled = Prefs.crosswalk(this);
        cwThreshold = Prefs.crosswalkThreshold(this);
        warnSize = Prefs.warnSize(this);
        soundEnabled = Prefs.sound(this);
        soundSens = Prefs.soundSens(this);
    }

    private void applySettings() {
        if (!viewsAdded) return;
        applyTransform();
        applyTone();
        if (scrim != null && !analyzing) scrim.updateUniform(maxDim);
        if (warnView != null) warnView.setSizeScale(warnSize);

        if (showBar && panel == null) {
            addPanel();
        } else if (!showBar && panel != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) { }
            panel = null;
            alphaText = null;
        }
        updateAlphaLabel();

        if (detectEnabled && detector == null) {
            initDetector();
        } else if (!detectEnabled && detector != null) {
            stopDetector();
        }

        if (crosswalkEnabled && clip == null) {
            initClip();
        } else if (!crosswalkEnabled && clip != null) {
            stopClip();
        }

        if (soundEnabled && sound == null) {
            initSound();
        } else if (!soundEnabled && sound != null) {
            stopSound();
        } else if (sound != null) {
            sound.setSensitivity(soundSens);
        }
    }

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------- foreground notification ----------------

    private void startForegroundSafely(boolean withProjection) {
        boolean withMic = soundEnabled && micGranted();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "DY Safety Walk running",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, fl);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, fl);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("DY Safety Walk running")
                .setContentText("Tap to open settings. Stop when you are done.")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi);

        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (withProjection) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (withMic && Build.VERSION.SDK_INT >= 30) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTI_ID, n, types);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    // ---------------- views ----------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private int windowType() {
        if (Build.VERSION.SDK_INT >= 26) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void addViews() {
        if (viewsAdded) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayRoot = new FrameLayout(this);
        overlayRoot.setBackgroundColor(Color.TRANSPARENT);

        scrim = new ScrimView(this, ScreenAnalyzer.COLS, ScreenAnalyzer.ROWS);
        overlayRoot.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        scrim.updateUniform(adaptive ? 0f : maxDim);

        textureView = new TextureView(this);
        textureView.setOpaque(false);
        textureView.setAlpha(baseAlpha);
        overlayRoot.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        warnView = new WarnView(this);
        warnView.setSizeScale(warnSize);
        overlayRoot.addView(warnView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            overlayParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                openCamera();
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyTransform();
            }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        wm.addView(overlayRoot, overlayParams);
        viewsAdded = true;
        applyTone();

        if (showBar) addPanel();
    }

    private void addPanel() {
        if (panel != null || wm == null) return;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC101418"));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Color.parseColor("#66FFFFFF"));
        panel.setBackground(bg);
        panel.setPadding(dp(6), dp(4), dp(6), dp(4));

        panel.addView(makeChip("−", new Runnable() {
            public void run() { nudgeAlpha(-0.05f); }
        }));

        alphaText = new TextView(this);
        alphaText.setTextColor(Color.WHITE);
        alphaText.setTextSize(13);
        alphaText.setGravity(Gravity.CENTER);
        alphaText.setMinWidth(dp(44));
        panel.addView(alphaText);

        panel.addView(makeChip("+", new Runnable() {
            public void run() { nudgeAlpha(0.05f); }
        }));

        panel.addView(makeChip("✕", new Runnable() {
            public void run() { stopEverything(); }
        }));

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(12);
        panelParams.y = dp(140);
        if (Build.VERSION.SDK_INT >= 28) {
            panelParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        panel.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = panelParams.x;
                        startY = panelParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = startX + (int) (e.getRawX() - touchX);
                        panelParams.y = startY + (int) (e.getRawY() - touchY);
                        try {
                            wm.updateViewLayout(panel, panelParams);
                        } catch (Exception ignored) { }
                        return false;
                }
                return false;
            }
        });

        wm.addView(panel, panelParams);
        updateAlphaLabel();
    }

    private TextView makeChip(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#33FFFFFF"));
        g.setCornerRadius(dp(16));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return t;
    }

    private void nudgeAlpha(float d) {
        baseAlpha = clamp(baseAlpha + d, 0.05f, 1.0f);
        Prefs.putFloat(this, Prefs.ALPHA, baseAlpha);
        applyTone();
        updateAlphaLabel();
    }

    private void updateAlphaLabel() {
        if (alphaText != null) alphaText.setText(Math.round(baseAlpha * 100) + "%");
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------- hazard detection ----------------

    private void initDetector() {
        if (detector != null || detHandler == null) return;
        detHandler.post(new Runnable() {
            public void run() {
                final Detector d = new Detector();
                boolean ok;
                try {
                    d.init(OverlayService.this);
                    ok = true;
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean success = ok;
                ui.post(new Runnable() {
                    public void run() {
                        if (!running) {
                            d.close();
                            return;
                        }
                        if (success) {
                            detector = d;
                            detecting = true;
                            if (detBmp == null) {
                                detBmp = Bitmap.createBitmap(Detector.SIZE, Detector.SIZE,
                                        Bitmap.Config.ARGB_8888);
                            }
                            ui.postDelayed(detectTick, DETECT_INTERVAL);
                        } else {
                            detecting = false;
                            Toast.makeText(OverlayService.this,
                                    "Could not load the hazard detection model.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private final Runnable detectTick = new Runnable() {
        public void run() {
            if (!running || detector == null) return;
            captureAndDetect();
            ui.postDelayed(this, DETECT_INTERVAL);
        }
    };

    private void captureAndDetect() {
        if (textureView == null || !textureView.isAvailable()) return;
        if (detBmp == null || detBusy.get()) return;
        try {
            textureView.getBitmap(detBmp);
        } catch (Exception e) {
            return;
        }
        detBusy.set(true);
        detHandler.post(new Runnable() {
            public void run() {
                List<Detector.Det> list;
                try {
                    list = detector.detect(detBmp, detThreshold);
                } catch (Throwable t) {
                    list = new ArrayList<>();
                }
                final List<Detector.Det> result = list;
                ui.post(new Runnable() {
                    public void run() {
                        detBusy.set(false);
                        showDetections(result);
                    }
                });
            }
        });
    }

    private void stopDetector() {
        detecting = false;
        ui.removeCallbacks(detectTick);
        if (detector != null) {
            final Detector d = detector;
            detector = null;
            if (detHandler != null) {
                detHandler.post(new Runnable() {
                    public void run() { d.close(); }
                });
            } else {
                d.close();
            }
        }
        lastDets = new ArrayList<>();
        if (warnView != null) warnView.clear();
    }

    // ---------------- sound detection ----------------

    private void initSound() {
        if (sound != null) return;
        if (!micGranted()) {
            Toast.makeText(this, "Microphone permission is needed for sound detection.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final SoundDetector sd = new SoundDetector();
        boolean ok = sd.start(this, soundSens, new SoundDetector.Listener() {
            public void onSound(final String name, final float score) {
                if (name == null) return;
                ui.post(new Runnable() {
                    public void run() {
                        if (!running) return;
                        soundName = name;
                        soundUntil = System.currentTimeMillis() + SOUND_HOLD;
                        renderWarning();
                    }
                });
            }
        });
        if (ok) {
            sound = sd;
        } else {
            sd.stop();
            Toast.makeText(this, "Could not open the microphone. Sound detection is off.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopSound() {
        soundUntil = 0;
        soundName = null;
        if (sound != null) {
            final SoundDetector sd = sound;
            sound = null;
            new Thread(new Runnable() {
                public void run() { sd.stop(); }
            }).start();
        }
    }

    // ---------------- crosswalk detection ----------------

    private void initClip() {
        if (clip != null || clipHandler == null) return;
        clipHandler.post(new Runnable() {
            public void run() {
                final ClipCrosswalk c = new ClipCrosswalk();
                boolean ok;
                try {
                    c.init(OverlayService.this);
                    ok = true;
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean success = ok;
                ui.post(new Runnable() {
                    public void run() {
                        if (!running) {
                            c.close();
                            return;
                        }
                        if (success) {
                            clip = c;
                            if (clipBmp == null) {
                                clipBmp = Bitmap.createBitmap(ClipCrosswalk.SIZE,
                                        ClipCrosswalk.SIZE, Bitmap.Config.ARGB_8888);
                            }
                            ui.postDelayed(clipTick, CLIP_INTERVAL);
                        } else {
                            Toast.makeText(OverlayService.this,
                                    "Could not load the crosswalk model (possibly low memory).",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private final Runnable clipTick = new Runnable() {
        public void run() {
            if (!running || clip == null) return;
            checkCrosswalk();
            ui.postDelayed(this, CLIP_INTERVAL);
        }
    };

    private void checkCrosswalk() {
        if (textureView == null || !textureView.isAvailable()) return;
        if (clipBmp == null || clipBusy.get()) return;
        try {
            textureView.getBitmap(clipBmp);
        } catch (Exception e) {
            return;
        }
        clipBusy.set(true);
        clipHandler.post(new Runnable() {
            public void run() {
                final float sc = clip == null ? -1f : clip.score(clipBmp);
                ui.post(new Runnable() {
                    public void run() {
                        clipBusy.set(false);
                        if (sc >= cwThreshold) {
                            crosswalkUntil = System.currentTimeMillis() + CROSSWALK_HOLD;
                        }
                        renderWarning();
                    }
                });
            }
        });
    }

    private void stopClip() {
        ui.removeCallbacks(clipTick);
        crosswalkUntil = 0;
        if (clip != null) {
            final ClipCrosswalk c = clip;
            clip = null;
            if (clipHandler != null) {
                clipHandler.post(new Runnable() {
                    public void run() { c.close(); }
                });
            } else {
                c.close();
            }
        }
    }

    // ---------------- warning ----------------

    private void showDetections(List<Detector.Det> list) {
        lastDets = list == null ? new ArrayList<Detector.Det>() : list;
        renderWarning();
    }

    private void renderWarning() {
        if (warnView == null || !running) return;

        long now = System.currentTimeMillis();
        int level = 0;
        Set<String> names = new LinkedHashSet<>();

        boolean crosswalk = now < crosswalkUntil;
        if (crosswalk) {
            names.add("Crosswalk");
            level = 3;
        }
        for (Detector.Det d : lastDets) {
            if (d.level >= 2 && d.risk >= 0.30f) {
                if (d.level > level) level = d.level;
                if (names.size() < 2) names.add(d.name);
            }
        }
        String heard = (now < soundUntil) ? soundName : null;

        String msg = null;
        String subMsg = null;

        if (!names.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String n : names) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(n);
            }
            msg = sb + (crosswalk && names.size() == 1 ? " ahead" : " nearby")
                    + " — stay alert";
            if (heard != null) subMsg = heard + " detected";
            level = Math.max(level, 3);
        } else if (heard != null) {
            msg = heard + " heard — stay alert";
            level = 3;
        }

        if (msg != null) {
            lastWarnAt = now;
            lastWarnText = msg;
            lastWarnSub = subMsg;
            lastWarnLevel = level;

            if (vibrateOn && level >= 3 && now - lastVibrateAt > VIBRATE_COOLDOWN) {
                lastVibrateAt = now;
                buzz();
            }
        } else if (now - lastWarnAt < WARN_HOLD_MS) {
            msg = lastWarnText;
            subMsg = lastWarnSub;
        }

        warnView.show(msg, msg == null ? null : subMsg);
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 60, 90, 60}, -1));
            } else {
                v.vibrate(new long[]{0, 60, 90, 60}, -1);
            }
        } catch (Exception ignored) { }
    }

    // ---------------- screen analysis ----------------

    private void startAnalyzer(int resultCode, Intent data) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            MediaProjection mp = mpm.getMediaProjection(resultCode, data);
            if (mp == null) return;

            if (Build.VERSION.SDK_INT >= 34) {
                mp.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        analyzing = false;
                    }
                }, ui);
            }

            Point size = new Point();
            wm.getDefaultDisplay().getRealSize(size);
            DisplayMetrics dm = getResources().getDisplayMetrics();

            analyzer = new ScreenAnalyzer();
            boolean ok = analyzer.start(mp, size.x, size.y, dm.densityDpi, bgHandler,
                    new ScreenAnalyzer.Listener() {
                        public void onLuminance(final float[] grid, final float mean) {
                            ui.post(new Runnable() {
                                public void run() { onScreenLuminance(grid, mean); }
                            });
                        }
                    });
            analyzing = ok;
            if (!ok) {
                Toast.makeText(this, "Screen analysis could not start. Running without it.",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            analyzing = false;
        }
    }

    private void onScreenLuminance(float[] grid, float mean) {
        if (!running || scrim == null) return;
        bgLum = mean;
        scrim.updateAdaptive(grid, maxDim);
        long now = System.currentTimeMillis();
        if (now - lastToneUpdate > 300) {
            lastToneUpdate = now;
            applyTone();
        }
    }

    private void applyTone() {
        if (textureView == null) return;

        float c = 1f + contrast * 1.5f;
        float t = (1f - c) * 128f;
        float shift = 0f;
        if (autoTone && analyzing) shift = (0.5f - bgLum) * 110f;
        float off = t + shift;

        ColorMatrix cm = new ColorMatrix(new float[]{
                c, 0, 0, 0, off,
                0, c, 0, 0, off,
                0, 0, c, 0, off,
                0, 0, 0, 1, 0
        });
        layerPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        textureView.setLayerPaint(layerPaint);

        float a = baseAlpha;
        if (autoAlpha && analyzing) a = baseAlpha * (0.8f + 0.55f * bgLum);
        textureView.setAlpha(clamp(a, 0.05f, 1f));
    }

    // ---------------- camera ----------------

    private void startThreads() {
        bgThread = new HandlerThread("dysafety_cam");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        detThread = new HandlerThread("dysafety_det");
        detThread.start();
        detHandler = new Handler(detThread.getLooper());

        clipThread = new HandlerThread("dysafety_clip");
        clipThread.start();
        clipHandler = new Handler(clipThread.getLooper());
    }

    private void stopThreads() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try {
                bgThread.join(500);
            } catch (InterruptedException ignored) { }
            bgThread = null;
            bgHandler = null;
        }
        if (detThread != null) {
            detThread.quitSafely();
            try {
                detThread.join(800);
            } catch (InterruptedException ignored) { }
            detThread = null;
            detHandler = null;
        }
        if (clipThread != null) {
            clipThread.quitSafely();
            try {
                clipThread.join(1200);
            } catch (InterruptedException ignored) { }
            clipThread = null;
            clipHandler = null;
        }
    }

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            stopEverything();
            return;
        }
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String targetId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetId = id;
                    break;
                }
            }
            if (targetId == null && cm.getCameraIdList().length > 0) {
                targetId = cm.getCameraIdList()[0];
            }
            if (targetId == null) {
                Toast.makeText(this, "No usable camera found.", Toast.LENGTH_LONG).show();
                stopEverything();
                return;
            }

            CameraCharacteristics cc = cm.getCameraCharacteristics(targetId);
            StreamConfigurationMap map = cc.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = chooseSize(map == null ? null
                    : map.getOutputSizes(SurfaceTexture.class));

            cm.openCamera(targetId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    ui.post(new Runnable() {
                        public void run() {
                            Toast.makeText(OverlayService.this,
                                    "Could not open the camera. Another app may be using it.",
                                    Toast.LENGTH_LONG).show();
                            stopEverything();
                        }
                    });
                }
            }, bgHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopEverything();
        }
    }

    private Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            long score = Math.abs((long) s.getWidth() * s.getHeight() - 1280L * 720L);
            float ratio = (float) s.getWidth() / s.getHeight();
            if (Math.abs(ratio - 16f / 9f) > 0.2f) score += 500000L;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best == null) best = sizes[0];
        return best;
    }

    private void createSession() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null || cameraDevice == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface surface = new Surface(st);

            final CaptureRequest.Builder rb =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(surface);
            rb.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(rb.build(), null, bgHandler);
                            } catch (CameraAccessException | IllegalStateException ignored) { }
                            ui.post(new Runnable() {
                                public void run() {
                                    applyTransform();
                                    applyTone();
                                }
                            });
                        }
                        public void onConfigureFailed(CameraCaptureSession session) {
                            ui.post(new Runnable() {
                                public void run() {
                                    Toast.makeText(OverlayService.this,
                                            "Preview setup failed.", Toast.LENGTH_LONG).show();
                                    stopEverything();
                                }
                            });
                        }
                    }, bgHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            stopEverything();
        }
    }

    private void applyTransform() {
        if (textureView == null || previewSize == null || wm == null) return;
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw == 0 || vh == 0) return;

        Matrix m = new Matrix();
        if (scaleMode == 2) {
            textureView.setTransform(m);
            return;
        }

        int rotation = wm.getDefaultDisplay().getRotation();
        boolean portrait = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180);
        float dw = portrait ? previewSize.getHeight() : previewSize.getWidth();
        float dh = portrait ? previewSize.getWidth() : previewSize.getHeight();

        float s = (scaleMode == 0)
                ? Math.max(vw / dw, vh / dh)
                : Math.min(vw / dw, vh / dh);

        m.setScale((dw * s) / vw, (dh * s) / vh, vw / 2f, vh / 2f);
        textureView.setTransform(m);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui.postDelayed(new Runnable() {
            public void run() { applyTransform(); }
        }, 300);
    }

    // ---------------- teardown ----------------

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception ignored) { }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception ignored) { }
    }

    private void removeViews() {
        if (wm != null) {
            try {
                if (overlayRoot != null) wm.removeView(overlayRoot);
            } catch (Exception ignored) { }
            try {
                if (panel != null) wm.removeView(panel);
            } catch (Exception ignored) { }
        }
        overlayRoot = null;
        panel = null;
        alphaText = null;
        textureView = null;
        scrim = null;
        warnView = null;
        viewsAdded = false;
    }

    private void stopAnalyzer() {
        analyzing = false;
        if (analyzer != null) {
            analyzer.stop();
            analyzer = null;
        }
    }

    private void stopEverything() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                public void run() { stopEverything(); }
            });
            return;
        }
        running = false;
        stopDetector();
        stopClip();
        stopSound();
        stopAnalyzer();
        closeCamera();
        removeViews();
        stopThreads();
        if (detBmp != null) {
            detBmp.recycle();
            detBmp = null;
        }
        if (clipBmp != null) {
            clipBmp.recycle();
            clipBmp = null;
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        detecting = false;
        analyzing = false;
        ui.removeCallbacks(detectTick);
        ui.removeCallbacks(clipTick);
        if (detector != null) {
            detector.close();
            detector = null;
        }
        if (clip != null) {
            clip.close();
            clip = null;
        }
        if (sound != null) {
            sound.stop();
            sound = null;
        }
        stopAnalyzer();
        closeCamera();
        removeViews();
        stopThreads();
        super.onDestroy();
    }
}
