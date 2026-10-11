package com.dysafety.walk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1001;
    private static final int REQ_NOTI = 1002;
    private static final int REQ_OVERLAY = 1003;
    private static final int REQ_PROJECTION = 1004;
    private static final int REQ_MIC = 1005;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F6F8"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(40));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("DY Safety Walk");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#111318"));
        root.addView(title);

        TextView ver = new TextView(this);
        ver.setText("Version 6.0");
        ver.setTextSize(12);
        ver.setTextColor(Color.parseColor("#1F8A5F"));
        ver.setPadding(0, dp(4), 0, 0);
        root.addView(ver);

        TextView desc = new TextView(this);
        desc.setText("Overlays the rear camera on top of your screen so you can see what is "
                + "in front of you while looking at your phone. Everything is adjusted here.");
        desc.setTextSize(13);
        desc.setTextColor(Color.parseColor("#5A6070"));
        desc.setPadding(0, dp(8), 0, dp(20));
        root.addView(desc);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(Color.parseColor("#33415C"));
        statusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.parseColor("#E8ECF4"));
        box.setCornerRadius(dp(12));
        statusView.setBackground(box);
        root.addView(statusView);

        // -- Permissions --
        root.addView(section("Permissions"));

        Button btnCam = makeButton("1. Allow camera", "#3A6EA5");
        btnCam.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestCamera(); }
        });
        root.addView(btnCam);
        root.addView(space(dp(8)));

        Button btnOverlay = makeButton("2. Allow display over other apps", "#3A6EA5");
        btnOverlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestOverlay(); }
        });
        root.addView(btnOverlay);
        root.addView(space(dp(8)));

        Button btnMic = makeButton("3. Allow microphone (for sound detection)", "#3A6EA5");
        btnMic.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestMic(); }
        });
        root.addView(btnMic);

        // -- Display --
        root.addView(section("Display"));

        addSlider(root, "Camera opacity", Prefs.ALPHA,
                Prefs.alpha(this), 0.05f, 1.0f,
                "Lower shows the app better, higher shows the road better");

        addSlider(root, "Contrast boost", Prefs.CONTRAST,
                Prefs.contrast(this), 0f, 1.0f,
                "Sharpens the outlines of what is ahead");

        addSlider(root, "Background dimming", Prefs.DIM,
                Prefs.dim(this), 0f, 0.7f,
                "Pushes the app underneath down so the camera is not washed out");

        // -- Screen analysis --
        root.addView(section("Screen pixel analysis"));

        TextView explain = new TextView(this);
        explain.setText("Reads the brightness of the app you are looking at in real time, "
                + "then renders the camera darker over white screens and brighter over dark "
                + "ones. Only the bright regions get dimmed, so the camera stays visible even "
                + "on messaging apps and web pages.\n"
                + "Analysis happens entirely on the device and nothing is stored or sent. "
                + "Turning this on shows a screen capture prompt once at start.");
        explain.setTextSize(12);
        explain.setLineSpacing(dp(3), 1f);
        explain.setTextColor(Color.parseColor("#5A6070"));
        explain.setPadding(0, 0, 0, dp(10));
        root.addView(explain);

        addSwitch(root, "Use screen analysis", Prefs.ADAPTIVE, Prefs.adaptive(this));
        addSwitch(root, "Auto opacity by brightness", Prefs.AUTO_ALPHA, Prefs.autoAlpha(this));
        addSwitch(root, "Auto tone inversion", Prefs.AUTO_TONE, Prefs.autoTone(this));

        // -- Hazard detection --
        root.addView(section("Hazard detection"));

        TextView dexp = new TextView(this);
        dexp.setText("A lightweight model (EfficientDet-Lite0) checks the camera 2-3 times a "
                + "second for bicycles, cars, buses and scooters. The larger something appears, "
                + "the closer it is and the higher the risk score.\n"
                + "Crosswalk detection is optional and not included by default.");
        dexp.setTextSize(12);
        dexp.setLineSpacing(dp(3), 1f);
        dexp.setTextColor(Color.parseColor("#5A6070"));
        dexp.setPadding(0, 0, 0, dp(10));
        root.addView(dexp);

        addSwitch(root, "Use hazard detection", Prefs.DETECT, Prefs.detect(this));

        addSlider(root, "Detection sensitivity", Prefs.SENS,
                Prefs.sens(this), 0f, 1.0f,
                "Higher catches distant objects but raises false alarms");

        addSlider(root, "Warning sign size", Prefs.WARN_SIZE,
                Prefs.warnSize(this), 0.6f, 1.6f,
                "100% is the default. Changes apply live while running");

        addSwitch(root, "Vibrate on warning", Prefs.VIBRATE, Prefs.vibrate(this));

        // -- Sound detection --
        root.addView(section("Hazard sound detection"));

        TextView sexp = new TextView(this);
        sexp.setText("YAMNet (521-class audio classifier) judges the surrounding sound every "
                + "0.5 seconds and looks for car horns, sirens, motorcycles, bicycle bells, "
                + "screeching tires and traffic noise. Sound covers what the camera cannot "
                + "see: behind you and to the sides.\n"
                + "Direction is not available. Two phone microphones cannot resolve front from "
                + "back, so this reports what the sound is, not where it came from.");
        sexp.setTextSize(12);
        sexp.setLineSpacing(dp(3), 1f);
        sexp.setTextColor(Color.parseColor("#5A6070"));
        sexp.setPadding(0, 0, 0, dp(10));
        root.addView(sexp);

        addSwitch(root, "Use sound detection", Prefs.SOUND, Prefs.sound(this));

        addSlider(root, "Sound sensitivity", Prefs.SOUND_SENS,
                Prefs.soundSens(this), 0f, 1.0f,
                "Higher catches quieter sounds but raises false alarms");

        // -- Other --
        root.addView(section("Other"));

        TextView ratioLabel = new TextView(this);
        ratioLabel.setText("Aspect handling");
        ratioLabel.setTextSize(14);
        ratioLabel.setTextColor(Color.parseColor("#111318"));
        root.addView(ratioLabel);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] modes = {"Fill", "Fit", "Stretch"};
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(modes[i]);
            rb.setId(2000 + i);
            rb.setTextSize(14);
            rg.addView(rb);
        }
        rg.check(2000 + Prefs.scaleMode(this));
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Prefs.putInt(MainActivity.this, Prefs.SCALE_MODE, checkedId - 2000);
                pushUpdate();
            }
        });
        root.addView(rg);

        addSwitch(root, "Show floating control bar", Prefs.SHOW_BAR, Prefs.showBar(this));

        // -- Run --
        root.addView(space(dp(24)));

        Button btnStart = makeButton("Start", "#1F8A5F");
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { start(); }
        });
        root.addView(btnStart);
        root.addView(space(dp(8)));

        Button btnStop = makeButton("Stop", "#8A3A3A");
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stop(); }
        });
        root.addView(btnStop);

        root.addView(space(dp(26)));

        TextView warn = new TextView(this);
        warn.setText("Safety notice\n"
                + "· This is an aid, not a substitute for looking up. Anything below your "
                + "feet or approaching from the side will not be in frame.\n"
                + "· At crossings and roadways, take your eyes off the screen.\n"
                + "· The camera and the models run continuously, which drains the battery "
                + "and heats the phone. Stop it when you are done.");
        warn.setTextSize(12);
        warn.setLineSpacing(dp(3), 1f);
        warn.setTextColor(Color.parseColor("#8A4B00"));
        warn.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable wbox = new GradientDrawable();
        wbox.setColor(Color.parseColor("#FFF3E0"));
        wbox.setCornerRadius(dp(12));
        warn.setBackground(wbox);
        root.addView(warn);

        return scroll;
    }

    private View section(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(26), 0, dp(10));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(Color.parseColor("#1F8A5F"));
        box.addView(t);
        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#D5DAE3"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(6);
        line.setLayoutParams(lp);
        box.addView(line);
        return box;
    }

    private void addSlider(LinearLayout parent, final String label, final String key,
                           float current, final float min, final float max,
                           String hint) {
        final TextView t = new TextView(this);
        t.setTextSize(14);
        t.setTextColor(Color.parseColor("#111318"));
        t.setPadding(0, dp(10), 0, 0);
        parent.addView(t);

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round((current - min) / (max - min) * 100f));
        t.setText(label + ": " + Math.round(current * 100) + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float v = min + (max - min) * (p / 100f);
                t.setText(label + ": " + Math.round(v * 100) + "%");
                Prefs.putFloat(MainActivity.this, key, v);
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { pushUpdate(); }
        });
        parent.addView(bar);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextSize(11);
        h.setTextColor(Color.parseColor("#8A90A0"));
        parent.addView(h);
    }

    private void addSwitch(LinearLayout parent, String label, final String key, boolean current) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(14);
        sw.setTextColor(Color.parseColor("#111318"));
        sw.setChecked(current);
        sw.setPadding(0, dp(12), 0, dp(4));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.putBool(MainActivity.this, key, checked);
                if (Prefs.ADAPTIVE.equals(key) && OverlayService.isRunning()) {
                    Toast.makeText(MainActivity.this,
                            "Screen analysis changes take effect after a restart.",
                            Toast.LENGTH_LONG).show();
                }
                pushUpdate();
            }
        });
        parent.addView(sw);
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private Button makeButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        return b;
    }

    // ---------------- actions ----------------

    private void pushUpdate() {
        if (!OverlayService.isRunning()) return;
        Intent i = new Intent(this, OverlayService.class);
        i.setAction(OverlayService.ACTION_UPDATE);
        startService(i);
    }

    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private void requestCamera() {
        if (hasCamera()) {
            Toast.makeText(this, "Camera permission is already granted.",
                    Toast.LENGTH_SHORT).show();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void requestMic() {
        if (hasMic()) {
            Toast.makeText(this, "Microphone permission is already granted.",
                    Toast.LENGTH_SHORT).show();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private void requestOverlay() {
        if (hasOverlay()) {
            Toast.makeText(this, "Display permission is already granted.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(i, REQ_OVERLAY);
    }

    private void requestNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
        }
    }

    private void start() {
        if (!hasCamera()) {
            Toast.makeText(this, "Allow the camera first (step 1).", Toast.LENGTH_LONG).show();
            requestCamera();
            return;
        }
        if (!hasOverlay()) {
            Toast.makeText(this, "Allow display over other apps first (step 2).",
                    Toast.LENGTH_LONG).show();
            requestOverlay();
            return;
        }
        requestNotification();
        if (Prefs.sound(this) && !hasMic()) {
            Toast.makeText(this, "Sound detection needs the microphone (step 3).",
                    Toast.LENGTH_LONG).show();
        }

        if (Prefs.adaptive(this)) {
            try {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
                return;
            } catch (Exception e) {
                Toast.makeText(this, "Screen analysis is unavailable. Starting without it.",
                        Toast.LENGTH_LONG).show();
            }
        }
        launchService(0, null);
    }

    private void launchService(int mpResult, Intent mpData) {
        Intent i = new Intent(this, OverlayService.class);
        i.setAction(OverlayService.ACTION_START);
        i.putExtra(OverlayService.EXTRA_MP_RESULT, mpResult);
        if (mpData != null) i.putExtra(OverlayService.EXTRA_MP_DATA, mpData);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        refreshStatus();
        Toast.makeText(this, "Started. It keeps running when you leave the app.",
                Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void stop() {
        Intent i = new Intent(this, OverlayService.class);
        i.setAction(OverlayService.ACTION_STOP);
        startService(i);
        statusView.postDelayed(new Runnable() {
            public void run() { refreshStatus(); }
        }, 300);
    }

    private void refreshStatus() {
        if (statusView == null) return;
        String s = "Camera: " + (hasCamera() ? "granted" : "needed")
                + "\nDisplay over apps: " + (hasOverlay() ? "granted" : "needed")
                + "\nMicrophone: " + (hasMic() ? "granted" : "needed")
                + "\nState: " + (OverlayService.isRunning() ? "running" : "stopped")
                + (OverlayService.isRunning() && OverlayService.isAnalyzing()
                    ? " · screen analysis" : "")
                + (OverlayService.isRunning() && OverlayService.isDetecting()
                    ? " · hazard detection" : "");
        statusView.setText(s);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJECTION) {
            if (res == RESULT_OK && data != null) {
                launchService(res, data);
            } else {
                Toast.makeText(this, "Starting without screen analysis.",
                        Toast.LENGTH_SHORT).show();
                launchService(0, null);
            }
            return;
        }
        refreshStatus();
    }
}
