package com.dysafety.walk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/**
 * Small red triangle with a black exclamation mark in the middle of the screen,
 * plus one line of text underneath. Size is adjustable from settings.
 * Does not receive touches.
 */
public class WarnView extends View {

    private final Paint sign = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path tri = new Path();
    private final Path triShadow = new Path();
    private final RectF r = new RectF();

    private String message = null;
    private String subMessage = null;
    private float scale = 1.0f;

    public WarnView(Context c) {
        super(c);

        sign.setStyle(Paint.Style.FILL);
        sign.setColor(Color.parseColor("#E02B25"));

        shadow.setStyle(Paint.Style.FILL);
        shadow.setColor(Color.parseColor("#38000000"));

        mark.setStyle(Paint.Style.FILL);
        mark.setColor(Color.parseColor("#141414"));

        pill.setStyle(Paint.Style.FILL);
        pill.setColor(Color.parseColor("#D90D1014"));

        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);

        sub.setColor(Color.parseColor("#C8D2DA"));
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sub.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    public void setSizeScale(float s) {
        this.scale = Math.max(0.5f, Math.min(1.8f, s));
        postInvalidateOnAnimation();
    }

    public void show(String message) {
        show(message, null);
    }

    public void show(String message, String sub) {
        this.message = message;
        this.subMessage = sub;
        postInvalidateOnAnimation();
    }

    public void clear() {
        this.message = null;
        this.subMessage = null;
        postInvalidateOnAnimation();
    }

    private static void buildTri(Path p, float cx, float top, float w, float h) {
        p.reset();
        p.moveTo(cx, top);
        p.lineTo(cx + w / 2f, top + h);
        p.lineTo(cx - w / 2f, top + h);
        p.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (message == null) return;

        float vw = getWidth();
        float vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        float s = scale;
        float triW = dp(78) * s;
        float triH = triW * 0.87f;
        float gap = dp(15) * s;

        float size = Math.max(sp(11), Math.min(sp(19), sp(14.5f) * s));
        text.setTextSize(size);
        float maxW = vw - dp(44);
        while (text.measureText(message) > maxW && size > sp(10)) {
            size -= sp(0.5f);
            text.setTextSize(size);
        }

        Paint.FontMetrics fm = text.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float padV = dp(9) * s;
        float padH = dp(15) * s;
        float tw = text.measureText(message);

        float subSize = size * 0.82f;
        float subH = 0f, subW = 0f;
        Paint.FontMetrics sfm = null;
        if (subMessage != null) {
            sub.setTextSize(subSize);
            sfm = sub.getFontMetrics();
            subH = sfm.descent - sfm.ascent;
            subW = sub.measureText(subMessage);
        }

        float gapLine = subMessage != null ? dp(2) * s : 0f;
        float pillH = lineH + subH + gapLine + padV * 2f;
        float contentW = Math.max(tw, subW);

        float totalH = triH + gap + pillH;
        float top = (vh - totalH) / 2f - dp(8);
        float cx = vw / 2f;

        float radius = dp(6) * s;
        sign.setPathEffect(new CornerPathEffect(radius));
        shadow.setPathEffect(new CornerPathEffect(radius * 1.1f));

        // soft shadow so the shape survives on bright backgrounds
        buildTri(triShadow, cx, top - triH * 0.02f, triW * 1.05f, triH * 1.05f);
        canvas.save();
        canvas.translate(0, dp(2.5f) * s);
        canvas.drawPath(triShadow, shadow);
        canvas.restore();

        buildTri(tri, cx, top, triW, triH);
        canvas.drawPath(tri, sign);

        float barW = triW * 0.058f;
        r.set(cx - barW, top + triH * 0.36f, cx + barW, top + triH * 0.655f);
        canvas.drawRoundRect(r, barW, barW, mark);
        canvas.drawCircle(cx, top + triH * 0.83f, barW * 1.08f, mark);

        float pillTop = top + triH + gap;
        float radiusPill = Math.min(pillH / 2f, dp(18) * s);
        r.set(cx - contentW / 2f - padH, pillTop, cx + contentW / 2f + padH, pillTop + pillH);
        canvas.drawRoundRect(r, radiusPill, radiusPill, pill);
        canvas.drawText(message, cx, pillTop + padV - fm.ascent, text);
        if (subMessage != null) {
            canvas.drawText(subMessage, cx,
                    pillTop + padV + lineH + gapLine - sfm.ascent, sub);
        }
    }
}
