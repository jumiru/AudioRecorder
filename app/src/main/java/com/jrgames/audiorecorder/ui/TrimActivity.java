package com.jrgames.audiorecorder.ui;

import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.jrgames.audiorecorder.R;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrimActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH   = "extra_file_path";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";
    public static final String EXTRA_RECORDING_ID = "extra_recording_id";

    public static final String RESULT_NEW_FILE_PATH  = "result_new_file_path";
    public static final String RESULT_NEW_DURATION_MS = "result_new_duration_ms";

    private WaveformView waveformView;
    private TextView tvStartTime, tvEndTime;
    private Button btnPreview, btnCut;

    private String filePath;
    private long durationMs;

    private float startFraction = 0.1f;
    private float endFraction   = 0.9f;

    private MediaPlayer previewPlayer;
    private boolean isPreviewing = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable previewStopRunnable;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trim);

        Toolbar toolbar = findViewById(R.id.toolbar_trim);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.trim_title);
        }

        filePath   = getIntent().getStringExtra(EXTRA_FILE_PATH);
        durationMs = getIntent().getLongExtra(EXTRA_DURATION_MS, 0);

        waveformView = findViewById(R.id.waveform_view);
        tvStartTime  = findViewById(R.id.tv_start_time);
        tvEndTime    = findViewById(R.id.tv_end_time);
        btnPreview   = findViewById(R.id.btn_preview);
        btnCut       = findViewById(R.id.btn_cut);

        waveformView.setOnMarkerChangedListener((start, end) -> {
            startFraction = start;
            endFraction   = end;
            updateTimeLabels();
        });

        // Init markers to match view's default
        startFraction = waveformView.getStartMarker();
        endFraction   = waveformView.getEndMarker();
        updateTimeLabels();

        if (filePath != null) waveformView.loadAudio(filePath);

        btnPreview.setOnClickListener(v -> togglePreview());
        btnCut.setOnClickListener(v -> confirmAndTrim());
    }

    private void updateTimeLabels() {
        tvStartTime.setText(formatTime((long) (startFraction * durationMs)));
        tvEndTime.setText(formatTime((long) (endFraction * durationMs)));
    }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        long centis = (ms % 1000) / 10;
        return String.format(Locale.getDefault(), "%d:%02d.%02d", min, sec, centis);
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    private void togglePreview() {
        if (isPreviewing) {
            stopPreview();
        } else {
            startPreview();
        }
    }

    private void startPreview() {
        stopPreview();
        isPreviewing = true;
        btnPreview.setText(R.string.trim_stop_preview);

        previewPlayer = new MediaPlayer();
        try {
            previewPlayer.setDataSource(filePath);
            previewPlayer.prepare();
            long startMs = (long) (startFraction * durationMs);
            long endMs   = (long) (endFraction   * durationMs);
            previewPlayer.seekTo((int) startMs);
            previewPlayer.start();

            long duration = endMs - startMs;
            previewStopRunnable = this::stopPreview;
            handler.postDelayed(previewStopRunnable, duration);

            previewPlayer.setOnCompletionListener(mp -> stopPreview());
        } catch (IOException e) {
            Toast.makeText(this, "Vorschau fehlgeschlagen", Toast.LENGTH_SHORT).show();
            stopPreview();
        }
    }

    private void stopPreview() {
        isPreviewing = false;
        btnPreview.setText(R.string.trim_preview);
        if (previewStopRunnable != null) {
            handler.removeCallbacks(previewStopRunnable);
            previewStopRunnable = null;
        }
        if (previewPlayer != null) {
            if (previewPlayer.isPlaying()) previewPlayer.stop();
            previewPlayer.release();
            previewPlayer = null;
        }
    }

    // ── Trim ─────────────────────────────────────────────────────────────────

    private void confirmAndTrim() {
        long startMs = (long) (startFraction * durationMs);
        long endMs   = (long) (endFraction   * durationMs);
        String msg   = getString(R.string.trim_confirm_message,
                formatTime(startMs), formatTime(endMs));

        new AlertDialog.Builder(this)
                .setTitle(R.string.trim_confirm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.trim_cut, (d, w) -> performTrim(startMs, endMs))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void performTrim(long startMs, long endMs) {
        stopPreview();
        btnCut.setEnabled(false);
        btnPreview.setEnabled(false);
        View progressOverlay = findViewById(R.id.progress_overlay);
        if (progressOverlay != null) progressOverlay.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                String outPath = filePath.replace(".m4a", "_trim.m4a");
                // Ensure unique filename
                java.io.File outFile = new java.io.File(outPath);
                int counter = 1;
                while (outFile.exists()) {
                    outPath = filePath.replace(".m4a", "_trim" + counter + ".m4a");
                    outFile = new java.io.File(outPath);
                    counter++;
                }

                trimAudio(filePath, outPath, startMs, endMs);
                long newDuration = endMs - startMs;
                String finalOutPath = outPath;

                runOnUiThread(() -> {
                    Intent result = new Intent();
                    result.putExtra(RESULT_NEW_FILE_PATH, finalOutPath);
                    result.putExtra(RESULT_NEW_DURATION_MS, newDuration);
                    setResult(RESULT_OK, result);
                    Toast.makeText(this, R.string.trim_success, Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnCut.setEnabled(true);
                    btnPreview.setEnabled(true);
                    View overlay = findViewById(R.id.progress_overlay);
                    if (overlay != null) overlay.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.trim_error, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void trimAudio(String inPath, String outPath, long startMs, long endMs)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inPath);

        int audioTrack = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            if (fmt.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                audioTrack = i;
                break;
            }
        }
        if (audioTrack < 0) throw new IOException("No audio track");

        extractor.selectTrack(audioTrack);
        MediaFormat format = extractor.getTrackFormat(audioTrack);

        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxerTrack = muxer.addTrack(format);
        muxer.start();

        long startUs = startMs * 1000L;
        long endUs   = endMs   * 1000L;
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            buf.clear();
            int size = extractor.readSampleData(buf, 0);
            if (size < 0) break;
            long sampleTime = extractor.getSampleTime();
            if (sampleTime > endUs) break;

            info.offset = 0;
            info.size   = size;
            info.presentationTimeUs = sampleTime - startUs;
            // Convert MediaExtractor sample flags to MediaCodec buffer flags
            int sampleFlags = extractor.getSampleFlags();
            info.flags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(muxerTrack, buf, info);
            extractor.advance();
        }

        muxer.stop();
        muxer.release();
        extractor.release();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreview();
        executor.shutdownNow();
    }
}


