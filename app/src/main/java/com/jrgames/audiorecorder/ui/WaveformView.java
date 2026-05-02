package com.jrgames.audiorecorder.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WaveformView extends View {

    public interface OnMarkerChangedListener {
        void onMarkersChanged(float startFraction, float endFraction);
    }

    // ── State ────────────────────────────────────────────────────────────────
    private short[] samples;          // downsampled PCM
    private boolean isLoading = false;
    private String loadError = null;

    // Zoom: how many dp per sample
    private float zoom = 1f;           // 1 = fit all, >1 = zoomed in
    private float scrollX = 0f;        // pixels scrolled from left

    // Markers (0..1 fractions of total duration)
    private float startMarker = 0.1f;
    private float endMarker   = 0.9f;

    // Touch handling
    private static final int TOUCH_NONE  = 0;
    private static final int TOUCH_START = 1;
    private static final int TOUCH_END   = 2;
    private static final int TOUCH_SCROLL = 3;
    private int touchMode = TOUCH_NONE;
    private float lastTouchX;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller scroller;

    private OnMarkerChangedListener markerListener;

    // ── Paints ───────────────────────────────────────────────────────────────
    private final Paint waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endMarkerPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loadingPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Constructor ──────────────────────────────────────────────────────────
    public WaveformView(Context context) { this(context, null); }
    public WaveformView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public WaveformView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context ctx) {
        waveformPaint.setColor(0xFF4A90D9);
        waveformPaint.setStrokeWidth(dpToPx(1.5f));

        startMarkerPaint.setColor(0xFF4CAF50);
        startMarkerPaint.setStrokeWidth(dpToPx(2.5f));

        endMarkerPaint.setColor(0xFFFF5722);
        endMarkerPaint.setStrokeWidth(dpToPx(2.5f));

        regionPaint.setColor(0x334CAF50);  // translucent green
        regionPaint.setStyle(Paint.Style.FILL);

        handlePaint.setStyle(Paint.Style.FILL);

        textPaint.setTextSize(dpToPx(11));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        loadingPaint.setColor(Color.GRAY);
        loadingPaint.setTextSize(dpToPx(14));
        loadingPaint.setTextAlign(Paint.Align.CENTER);

        scroller = new OverScroller(ctx);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float focusX = detector.getFocusX(); // pixel pos on screen
                float oldZoom = zoom;
                zoom *= detector.getScaleFactor();
                zoom = Math.max(1f, Math.min(zoom, 80f));
                // Keep focus point stable
                scrollX = (scrollX + focusX) * (zoom / oldZoom) - focusX;
                clampScroll();
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                    float distanceX, float distanceY) {
                if (touchMode == TOUCH_SCROLL) {
                    scrollX += distanceX;
                    clampScroll();
                    invalidate();
                }
                return true;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                   float velX, float velY) {
                if (touchMode == TOUCH_SCROLL) {
                    scroller.fling((int) scrollX, 0, (int) -velX, 0,
                            0, (int) maxScrollX(), 0, 0);
                    postOnAnimation(WaveformView.this::flingStep);
                }
                return true;
            }
        });
    }

    private void flingStep() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            clampScroll();
            invalidate();
            postOnAnimation(this::flingStep);
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setOnMarkerChangedListener(OnMarkerChangedListener l) {
        this.markerListener = l;
    }

    public float getStartMarker() { return startMarker; }
    public float getEndMarker()   { return endMarker;   }

    public void loadAudio(String filePath) {
        samples = null;
        isLoading = true;
        loadError = null;
        zoom = 1f;
        scrollX = 0f;
        invalidate();

        executor.execute(() -> {
            try {
                short[] decoded = decodeAudio(filePath);
                mainHandler.post(() -> {
                    samples = decoded;
                    isLoading = false;
                    invalidate();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isLoading = false;
                    loadError = "Fehler beim Laden";
                    invalidate();
                });
            }
        });
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        canvas.drawColor(0xFF1A1A2E);

        if (isLoading) {
            canvas.drawText("Lade Waveform…", w / 2f, h / 2f, loadingPaint);
            return;
        }
        if (loadError != null) {
            canvas.drawText(loadError, w / 2f, h / 2f, loadingPaint);
            return;
        }
        if (samples == null || samples.length == 0) return;

        int n = samples.length;
        float totalWidth = w * zoom;  // total "virtual" canvas width

        // pixels per sample
        float pps = totalWidth / n;

        // Determine visible sample range
        int firstSample = Math.max(0, (int) (scrollX / pps));
        int lastSample  = Math.min(n - 1, (int) ((scrollX + w) / pps) + 1);

        // Center line
        float centerY = h / 2f;

        // Draw waveform bars
        for (int i = firstSample; i <= lastSample; i++) {
            float x = i * pps - scrollX;
            float amp = Math.abs(samples[i]) / 32768f;
            float barH = amp * centerY * 0.9f;
            canvas.drawLine(x, centerY - barH, x, centerY + barH, waveformPaint);
        }

        // Marker positions in pixels
        float startPx = startMarker * totalWidth - scrollX;
        float endPx   = endMarker   * totalWidth - scrollX;

        // Selected region overlay
        canvas.drawRect(startPx, 0, endPx, h, regionPaint);

        // Start marker line
        canvas.drawLine(startPx, 0, startPx, h, startMarkerPaint);
        drawHandle(canvas, startPx, true);

        // End marker line
        canvas.drawLine(endPx, 0, endPx, h, endMarkerPaint);
        drawHandle(canvas, endPx, false);
    }

    private void drawHandle(Canvas canvas, float x, boolean isStart) {
        float size = dpToPx(14);
        Path path = new Path();
        // Triangle at top
        if (isStart) {
            handlePaint.setColor(0xFF4CAF50);
            path.moveTo(x, size);
            path.lineTo(x, 0);
            path.lineTo(x + size, 0);
        } else {
            handlePaint.setColor(0xFFFF5722);
            path.moveTo(x, size);
            path.lineTo(x, 0);
            path.lineTo(x - size, 0);
        }
        path.close();
        canvas.drawPath(path, handlePaint);
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            touchMode = TOUCH_NONE;
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                scroller.abortAnimation();
                float tx = event.getX();
                float totalWidth = getWidth() * zoom;
                float startPx = startMarker * totalWidth - scrollX;
                float endPx   = endMarker   * totalWidth - scrollX;
                float hitRadius = dpToPx(20);
                if (Math.abs(tx - startPx) < hitRadius) {
                    touchMode = TOUCH_START;
                } else if (Math.abs(tx - endPx) < hitRadius) {
                    touchMode = TOUCH_END;
                } else {
                    touchMode = TOUCH_SCROLL;
                }
                lastTouchX = tx;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float tx = event.getX();
                if (touchMode == TOUCH_START || touchMode == TOUCH_END) {
                    float totalWidth = getWidth() * zoom;
                    float fraction = (tx + scrollX) / totalWidth;
                    fraction = Math.max(0f, Math.min(1f, fraction));
                    if (touchMode == TOUCH_START) {
                        startMarker = Math.min(fraction, endMarker - 0.01f);
                    } else {
                        endMarker = Math.max(fraction, startMarker + 0.01f);
                    }
                    notifyMarkerChanged();
                    invalidate();
                } else {
                    gestureDetector.onTouchEvent(event);
                }
                lastTouchX = tx;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touchMode == TOUCH_SCROLL) {
                    gestureDetector.onTouchEvent(event);
                }
                touchMode = TOUCH_NONE;
                return true;
        }
        gestureDetector.onTouchEvent(event);
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void notifyMarkerChanged() {
        if (markerListener != null) markerListener.onMarkersChanged(startMarker, endMarker);
    }

    private float maxScrollX() {
        if (getWidth() == 0) return 0;
        float totalWidth = getWidth() * zoom;
        return Math.max(0, totalWidth - getWidth());
    }

    private void clampScroll() {
        scrollX = Math.max(0f, Math.min(scrollX, maxScrollX()));
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    // ── Audio decoding ───────────────────────────────────────────────────────

    private short[] decodeAudio(String filePath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(filePath);

        int audioTrack = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            if (fmt.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                audioTrack = i;
                break;
            }
        }
        if (audioTrack < 0) throw new IOException("No audio track found");

        extractor.selectTrack(audioTrack);
        MediaFormat format = extractor.getTrackFormat(audioTrack);
        String mime = format.getString(MediaFormat.KEY_MIME);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        // Collect raw PCM
        java.io.ByteArrayOutputStream pcmOut = new java.io.ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(5000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    inBuf.clear();
                    int size = extractor.readSampleData(inBuf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 5000);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                byte[] chunk = new byte[info.size];
                outBuf.get(chunk);
                pcmOut.write(chunk);
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ignore
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        byte[] pcmBytes = pcmOut.toByteArray();
        ShortBuffer sb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] allSamples = new short[sb.remaining()];
        sb.get(allSamples);

        // Downsample to ~2000 points
        int targetPoints = 2000;
        if (allSamples.length <= targetPoints) return allSamples;
        int step = allSamples.length / targetPoints;
        short[] result = new short[targetPoints];
        for (int i = 0; i < targetPoints; i++) {
            long peak = 0;
            for (int j = 0; j < step; j++) {
                peak = Math.max(peak, Math.abs(allSamples[i * step + j]));
            }
            result[i] = (short) peak;
        }
        return result;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        executor.shutdownNow();
    }
}

