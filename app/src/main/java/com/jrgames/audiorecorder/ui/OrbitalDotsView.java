package com.jrgames.audiorecorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a ring of dots that rotate around the centre of the view.
 * Show it when recording starts, hide it when recording stops.
 */
public class OrbitalDotsView extends View {

    private static final int   DOT_COUNT   = 10;
    private static final float DOT_RADIUS  = 5f;
    private static final float ORBIT_RATIO = 0.50f; // knapp außerhalb des FAB-Randes
    private static final long  FRAME_MS    = 16L;
    private static final float SPEED_DEG   = 1.6f;  // langsamer

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float angleDeg = 0f;
    private final float dotRadiusPx;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            angleDeg = (angleDeg + SPEED_DEG) % 360f;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public OrbitalDotsView(Context context) {
        this(context, null);
    }

    public OrbitalDotsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        dotRadiusPx = DOT_RADIUS * density;
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth()  / 2f;
        float cy = getHeight() / 2f;
        float orbitR = Math.min(cx, cy) * ORBIT_RATIO;

        for (int i = 0; i < DOT_COUNT; i++) {
            float angle = (float) Math.toRadians(angleDeg + i * (360f / DOT_COUNT));
            float x = cx + orbitR * (float) Math.cos(angle);
            float y = cy + orbitR * (float) Math.sin(angle);

            // Stärkerer Schweif: führender Punkt voll sichtbar, letzter fast unsichtbar
            // Quadratische Kurve für weicheren Fade-Effekt
            float t = (float) i / DOT_COUNT;           // 0.0 (führend) → 1.0 (Ende)
            float alpha = (1f - t) * (1f - t);         // quadratischer Abfall: 1.0 → 0.0
            alpha = Math.max(alpha, 0.04f);             // Minimum damit der "Schwanz" nicht abrupt endet
            paint.setAlpha((int) (alpha * 255));
            canvas.drawCircle(x, y, dotRadiusPx, paint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    private void startAnimation() {
        removeCallbacks(ticker);
        post(ticker);
    }

    private void stopAnimation() {
        removeCallbacks(ticker);
    }
}



