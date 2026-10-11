package com.dysafety.walk;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {

    public static final String NAME = "dysafety_prefs";

    public static final String ALPHA = "alpha";           // 0.05 - 1.0
    public static final String CONTRAST = "contrast";     // 0 - 1
    public static final String DIM = "dim";               // 0 - 0.7, max background dimming
    public static final String ADAPTIVE = "adaptive";     // screen pixel analysis on/off
    public static final String AUTO_ALPHA = "auto_alpha"; // auto opacity by screen brightness
    public static final String AUTO_TONE = "auto_tone";   // auto tone inversion
    public static final String SCALE_MODE = "scale_mode"; // 0 fill / 1 fit / 2 stretch
    public static final String SHOW_BAR = "show_bar";     // floating control bar
    public static final String DETECT = "detect";         // hazard detection on/off
    public static final String SENS = "sens";             // detection sensitivity 0-1
    public static final String VIBRATE = "vibrate";       // vibrate on warning
    public static final String CROSSWALK = "crosswalk";   // crosswalk detection
    public static final String WARN_SIZE = "warn_size";   // warning sign size scale
    public static final String SOUND = "sound";           // sound detection on/off
    public static final String SOUND_SENS = "sound_sens"; // sound sensitivity

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static float alpha(Context c) {
        return get(c).getFloat(ALPHA, 0.35f);
    }

    public static float contrast(Context c) {
        return get(c).getFloat(CONTRAST, 0.45f);
    }

    public static float dim(Context c) {
        return get(c).getFloat(DIM, 0.35f);
    }

    public static boolean adaptive(Context c) {
        return get(c).getBoolean(ADAPTIVE, true);
    }

    public static boolean autoAlpha(Context c) {
        return get(c).getBoolean(AUTO_ALPHA, true);
    }

    public static boolean autoTone(Context c) {
        return get(c).getBoolean(AUTO_TONE, true);
    }

    public static int scaleMode(Context c) {
        return get(c).getInt(SCALE_MODE, 0);
    }

    public static boolean showBar(Context c) {
        return get(c).getBoolean(SHOW_BAR, false);
    }

    public static boolean detect(Context c) {
        return get(c).getBoolean(DETECT, true);
    }

    public static float sens(Context c) {
        return get(c).getFloat(SENS, 0.5f);
    }

    /** Higher sensitivity means a lower score threshold. */
    public static float threshold(Context c) {
        return 0.62f - 0.37f * sens(c);
    }

    public static boolean vibrate(Context c) {
        return get(c).getBoolean(VIBRATE, true);
    }

    public static boolean crosswalk(Context c) {
        return get(c).getBoolean(CROSSWALK, false);
    }

    /** CLIP cosine similarity threshold. Validation put the split around 0.20-0.25. */
    public static float crosswalkThreshold(Context c) {
        return 0.27f - 0.07f * sens(c);
    }

    public static float warnSize(Context c) {
        return get(c).getFloat(WARN_SIZE, 1.0f);
    }

    public static boolean sound(Context c) {
        return get(c).getBoolean(SOUND, true);
    }

    public static float soundSens(Context c) {
        return get(c).getFloat(SOUND_SENS, 0.5f);
    }

    public static void putFloat(Context c, String k, float v) {
        get(c).edit().putFloat(k, v).apply();
    }

    public static void putBool(Context c, String k, boolean v) {
        get(c).edit().putBoolean(k, v).apply();
    }

    public static void putInt(Context c, String k, int v) {
        get(c).edit().putInt(k, v).apply();
    }
}
