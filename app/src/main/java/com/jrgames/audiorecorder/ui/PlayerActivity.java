package com.jrgames.audiorecorder.ui;

import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jrgames.audiorecorder.R;
import com.jrgames.audiorecorder.data.Recording;
import com.jrgames.audiorecorder.viewmodel.MainViewModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends AppCompatActivity implements RenameDialogFragment.RenameListener {

    private static final int REVERSE_CHUNK_FRAMES = 2048;

    public static final String EXTRA_RECORDING_ID = "extra_recording_id";
    public static final String EXTRA_DISPLAY_NAME = "extra_display_name";
    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";
    public static final String EXTRA_SORT_ORDER = "extra_sort_order";
    public static final String EXTRA_CREATED_AT = "extra_created_at";

    private MainViewModel viewModel;
    private Recording recording;

    private TextView tvName;
    private TextView tvCurrent;
    private TextView tvTotal;
    private SeekBar seekBar;
    private WaveformView waveformView;
    private ImageButton btnRewind;
    private ImageButton btnPlay;
    private ImageButton btnRepeat;

    private int activeIconColor;
    private int inactiveIconColor;

    private MediaPlayer mediaPlayer;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private boolean isUserSeeking;
    private volatile boolean forwardPlaying;
    private boolean loopFadeActive;
    private int loopFadeTargetMs;
    private int loopFadeStep;
    private static final int LOOP_FADE_STEPS = 4;
    private static final long LOOP_FADE_STEP_DELAY_MS = 12L;
    private final Runnable loopFadeOutRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer == null || !loopFadeActive) {
                loopFadeActive = false;
                return;
            }

            float volume = Math.max(0f, 1f - ((loopFadeStep + 1f) / LOOP_FADE_STEPS));
            setPlayerVolume(volume);
            loopFadeStep++;

            if (loopFadeStep < LOOP_FADE_STEPS) {
                progressHandler.postDelayed(this, LOOP_FADE_STEP_DELAY_MS);
                return;
            }

            try {
                mediaPlayer.seekTo(loopFadeTargetMs);
            } catch (Exception ignored) {
            }

            loopFadeStep = 0;
            progressHandler.postDelayed(loopFadeInRunnable, LOOP_FADE_STEP_DELAY_MS);
        }
    };
    private final Runnable loopFadeInRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer == null || !loopFadeActive) {
                loopFadeActive = false;
                return;
            }

            float volume = Math.min(1f, (loopFadeStep + 1f) / LOOP_FADE_STEPS);
            setPlayerVolume(volume);
            loopFadeStep++;

            if (loopFadeStep < LOOP_FADE_STEPS) {
                progressHandler.postDelayed(this, LOOP_FADE_STEP_DELAY_MS);
                return;
            }

            loopFadeActive = false;
            loopFadeStep = 0;
        }
    };

    private final ExecutorService reverseExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean reversePlaying;
    private volatile boolean reverseRequested;
    private volatile boolean repeatEnabled;
    private Thread reverseThread;
    private AudioTrack reverseAudioTrack;
    private PcmAudioData reversePcmData;

    private final ActivityResultLauncher<Intent> trimLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String newPath = result.getData().getStringExtra(TrimActivity.RESULT_NEW_FILE_PATH);
                    long newDuration = result.getData().getLongExtra(TrimActivity.RESULT_NEW_DURATION_MS, 0);
                    if (newPath != null && !newPath.isEmpty()) {
                        viewModel.applyTrim(recording, newPath, newDuration);
                        recording.filePath = newPath;
                        recording.durationMs = newDuration;
                        waveformView.loadAudio(recording.filePath);
                        preparePlayer();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        if (!readRecordingFromIntent()) {
            Toast.makeText(this, R.string.player_load_error, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupToolbar();
        bindViews();
        bindPlayerControls();
        bindActionControls();
        preparePlayer();
    }

    private boolean readRecordingFromIntent() {
        Intent intent = getIntent();
        String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }

        long id = intent.getLongExtra(EXTRA_RECORDING_ID, -1L);
        String displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        long durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L);
        int sortOrder = intent.getIntExtra(EXTRA_SORT_ORDER, 0);
        long createdAt = intent.getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis());

        recording = new Recording(displayName != null ? displayName : getString(R.string.player_default_name), filePath,
                durationMs, sortOrder, createdAt);
        recording.id = id;
        return true;
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar_player);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.player_title);
        }
    }

    private void bindViews() {
        tvName = findViewById(R.id.tv_player_name);
        waveformView = findViewById(R.id.waveform_player);
        tvCurrent = findViewById(R.id.tv_player_current);
        tvTotal = findViewById(R.id.tv_player_total);
        seekBar = findViewById(R.id.seek_player);

        tvName.setText(recording.displayName);
        tvCurrent.setText(formatTime(0));
        tvTotal.setText(formatTime(recording.durationMs));
        seekBar.setMax((int) Math.max(0, recording.durationMs));
        waveformView.loadAudio(recording.filePath);
    }

    private void bindPlayerControls() {
        btnRewind = findViewById(R.id.btn_player_rewind);
        ImageButton btnStart = findViewById(R.id.btn_player_start);
        btnPlay = findViewById(R.id.btn_player_play);
        ImageButton btnPause = findViewById(R.id.btn_player_pause);
        ImageButton btnStop = findViewById(R.id.btn_player_stop);
        btnRepeat = findViewById(R.id.btn_player_repeat);
        ImageButton btnJumpEnd = findViewById(R.id.btn_player_jump_end);
        ImageButton btnJumpGreen = findViewById(R.id.btn_player_jump_green);
        ImageButton btnJumpRed = findViewById(R.id.btn_player_jump_red);
        ImageButton btnSetStartMarker = findViewById(R.id.btn_player_set_start_marker);
        ImageButton btnSetEndMarker = findViewById(R.id.btn_player_set_end_marker);

        activeIconColor = ContextCompat.getColor(this, R.color.colorPrimary);
        inactiveIconColor = resolveThemeColor(android.R.attr.textColorSecondary, android.R.color.darker_gray);

        btnRewind.setOnClickListener(v -> playReverse());
        btnStart.setOnClickListener(v -> seekToStart());
        btnPlay.setOnClickListener(v -> play());
        btnPause.setOnClickListener(v -> pause());
        btnStop.setOnClickListener(v -> stop());
        btnRepeat.setOnClickListener(v -> {
            repeatEnabled = !repeatEnabled;
            updateDirectionButtonsUi();
        });
        btnJumpEnd.setOnClickListener(v -> jumpToEnd());
        btnJumpGreen.setOnClickListener(v -> jumpToGreenMarker());
        btnJumpRed.setOnClickListener(v -> jumpToRedMarker());
        btnSetStartMarker.setOnClickListener(v -> setStartMarkerToCurrentPosition());
        btnSetEndMarker.setOnClickListener(v -> setEndMarkerToCurrentPosition());
        updateDirectionButtonsUi();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrent.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
                stopReversePlayback();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                if (mediaPlayer != null) {
                    mediaPlayer.seekTo(seekBar.getProgress());
                    tvCurrent.setText(formatTime(mediaPlayer.getCurrentPosition()));
                }
            }
        });
    }

    private void bindActionControls() {
        ImageButton btnUpload = findViewById(R.id.btn_player_upload);
        ImageButton btnTrim = findViewById(R.id.btn_player_trim);
        ImageButton btnRename = findViewById(R.id.btn_player_rename);
        ImageButton btnDelete = findViewById(R.id.btn_player_delete);

        btnUpload.setOnClickListener(v -> WebDavUploadDialogFragment.newInstance(recording)
                .show(getSupportFragmentManager(), "webdav_upload"));

        btnTrim.setOnClickListener(v -> {
            Intent intent = new Intent(this, TrimActivity.class);
            intent.putExtra(TrimActivity.EXTRA_FILE_PATH, recording.filePath);
            intent.putExtra(TrimActivity.EXTRA_DURATION_MS, recording.durationMs);
            intent.putExtra(TrimActivity.EXTRA_RECORDING_ID, recording.id);
            trimLauncher.launch(intent);
        });

        btnRename.setOnClickListener(v -> {
            RenameDialogFragment dialog = RenameDialogFragment.newInstance(recording.displayName);
            dialog.setRenameListener(this);
            dialog.show(getSupportFragmentManager(), "rename");
        });

        btnDelete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, recording.displayName))
                .setPositiveButton(R.string.delete_ok, (dialog, which) -> {
                    stop();
                    viewModel.deleteRecording(recording);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    private void preparePlayer() {
        releasePlayer();
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(recording.filePath);
            mediaPlayer.prepare();
            int duration = mediaPlayer.getDuration();
            seekBar.setMax(Math.max(0, duration));
            tvTotal.setText(formatTime(duration));
            seekBar.setProgress(0);
            tvCurrent.setText(formatTime(0));

            mediaPlayer.setOnCompletionListener(mp -> stop());
        } catch (IOException e) {
            Toast.makeText(this, R.string.player_play_error, Toast.LENGTH_SHORT).show();
            releasePlayer();
        }
    }

    private void play() {
        stopReversePlayback();
        if (mediaPlayer == null) {
            preparePlayer();
        }
        syncMediaPlayerToSeekbar();
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            forwardPlaying = true;
            mediaPlayer.start();
            startProgressUpdates();
        }
        updateDirectionButtonsUi();
    }

    private void pause() {
        stopReversePlayback();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        forwardPlaying = false;
        stopLoopFade();
        stopProgressUpdates();
        updateDirectionButtonsUi();
    }

    private void stop() {
        stopReversePlayback();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        forwardPlaying = false;
        stopLoopFade();
        stopProgressUpdates();
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(0);
        }
        seekBar.setProgress(0);
        tvCurrent.setText(formatTime(0));
        updateDirectionButtonsUi();
    }

    private void seekToStart() {
        stopReversePlayback();
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(0);
        }
        seekBar.setProgress(0);
        tvCurrent.setText(formatTime(0));
        updateDirectionButtonsUi();
    }

    private void jumpToEnd() {
        jumpToPosition(Math.max(0, seekBar.getMax()));
    }

    private void jumpToGreenMarker() {
        jumpToPosition(getStartMarkerMs());
    }

    private void jumpToRedMarker() {
        jumpToPosition(getEndMarkerMs());
    }

    private void jumpToPosition(int positionMs) {
        int clamped = Math.max(0, Math.min(seekBar.getMax(), positionMs));
        boolean continueForwardPlayback = forwardPlaying && mediaPlayer != null && mediaPlayer.isPlaying();

        if (reversePlaying || reverseRequested) {
            stopReversePlayback();
        }

        stopLoopFade();

        if (mediaPlayer != null) {
            mediaPlayer.seekTo(clamped);
        }
        seekBar.setProgress(clamped);
        tvCurrent.setText(formatTime(clamped));

        forwardPlaying = continueForwardPlayback;
        if (continueForwardPlayback) {
            startProgressUpdates();
        }
        updateDirectionButtonsUi();
    }

    private void playReverse() {
        if (recording == null || recording.filePath == null || recording.filePath.isEmpty()) {
            return;
        }

        if (reversePlaying) {
            stopReversePlayback();
            updateDirectionButtonsUi();
            return;
        }

        reverseRequested = true;
        updateDirectionButtonsUi();

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        stopProgressUpdates();

        if (reversePcmData == null) {
            reverseExecutor.execute(() -> {
                try {
                    PcmAudioData decoded = decodeAudioToPcm(recording.filePath);
                    runOnUiThread(() -> {
                        reversePcmData = decoded;
                        if (reverseRequested) {
                            startReverseThread();
                        } else {
                            updateDirectionButtonsUi();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.player_reverse_error, Toast.LENGTH_SHORT).show());
                }
            });
            return;
        }

        startReverseThread();
    }

    private void startReverseThread() {
        if (reversePcmData == null || reversePcmData.sampleRate <= 0 || reversePcmData.channelCount <= 0
                || reversePcmData.samples.length == 0) {
            Toast.makeText(this, R.string.player_reverse_error, Toast.LENGTH_SHORT).show();
            return;
        }

        stopReversePlayback();
        reverseRequested = true;
        reversePlaying = true;
        runOnUiThread(this::updateDirectionButtonsUi);
        reverseThread = new Thread(() -> {
            AudioTrack track = null;
            int lastProgressMs = seekBar.getProgress();
            try {
                int channelConfig = reversePcmData.channelCount == 1
                        ? AudioFormat.CHANNEL_OUT_MONO
                        : AudioFormat.CHANNEL_OUT_STEREO;
                int minBufferBytes = AudioTrack.getMinBufferSize(
                        reversePcmData.sampleRate,
                        channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT);
                int desiredBuffer = Math.max(minBufferBytes, REVERSE_CHUNK_FRAMES * reversePcmData.channelCount * 2);

                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(reversePcmData.sampleRate)
                                .setChannelMask(channelConfig)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(desiredBuffer)
                        .build();

                reverseAudioTrack = track;
                track.play();

                final short[] pcm = reversePcmData.samples;
                final int channels = reversePcmData.channelCount;
                final int totalFrames = pcm.length / channels;
                int framePos = Math.min(totalFrames, Math.max(0, (seekBar.getProgress() * reversePcmData.sampleRate) / 1000));
                if (framePos <= 0) {
                    framePos = totalFrames;
                }

                short[] outBuffer = new short[REVERSE_CHUNK_FRAMES * channels];
                while (reversePlaying) {
                    int markerStartFrame = markerFractionToFrame(waveformView.getStartMarker(), totalFrames);
                    int markerEndFrame = markerFractionToFrame(waveformView.getEndMarker(), totalFrames);
                    if (markerEndFrame <= markerStartFrame) {
                        markerEndFrame = Math.min(totalFrames, markerStartFrame + 1);
                    }

                    if (repeatEnabled) {
                        if (framePos > markerEndFrame || framePos < markerStartFrame) {
                            framePos = markerEndFrame;
                        }
                    } else if (framePos <= 0) {
                        break;
                    }

                    int framesToWrite;
                    if (repeatEnabled) {
                        int toLoopBoundary = framePos - markerStartFrame;
                        if (toLoopBoundary <= 0) {
                            framePos = markerEndFrame;
                            continue;
                        }
                        framesToWrite = Math.min(REVERSE_CHUNK_FRAMES, toLoopBoundary);
                    } else {
                        framesToWrite = Math.min(REVERSE_CHUNK_FRAMES, framePos);
                    }

                    int outIdx = 0;
                    for (int i = 0; i < framesToWrite; i++) {
                        int srcFrame = framePos - 1 - i;
                        int srcBase = srcFrame * channels;
                        for (int c = 0; c < channels; c++) {
                            outBuffer[outIdx++] = pcm[srcBase + c];
                        }
                    }

                    int written = track.write(outBuffer, 0, outIdx, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) {
                        break;
                    }

                    framePos -= framesToWrite;
                    if (repeatEnabled && framePos <= markerStartFrame) {
                        framePos = markerEndFrame;
                    }
                    int progressMs = (framePos * 1000) / reversePcmData.sampleRate;
                    lastProgressMs = progressMs;
                    runOnUiThread(() -> {
                        if (!isUserSeeking) {
                            seekBar.setProgress(progressMs);
                            tvCurrent.setText(formatTime(progressMs));
                        }
                    });
                }
            } catch (Exception ignored) {
                runOnUiThread(() -> Toast.makeText(this, R.string.player_reverse_error, Toast.LENGTH_SHORT).show());
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (Exception ignored) {
                    }
                    track.release();
                }
                reverseAudioTrack = null;
                reverseThread = null;
                boolean wasPlaying = reversePlaying;
                reversePlaying = false;
                int finalProgressMs = Math.max(0, Math.min(seekBar.getMax(), lastProgressMs));
                runOnUiThread(() -> {
                    if (wasPlaying) {
                        seekBar.setProgress(finalProgressMs);
                        tvCurrent.setText(formatTime(finalProgressMs));
                        syncMediaPlayerToSeekbar();
                    }
                    updateDirectionButtonsUi();
                });
            }
        }, "reverse-playback-thread");
        reverseThread.start();
    }

    private void stopReversePlayback() {
        reverseRequested = false;
        reversePlaying = false;
        if (reverseAudioTrack != null) {
            try {
                reverseAudioTrack.pause();
                reverseAudioTrack.flush();
            } catch (Exception ignored) {
            }
        }
        forwardPlaying = false;
        updateDirectionButtonsUi();
    }

    private void setStartMarkerToCurrentPosition() {
        int totalMs = Math.max(1, seekBar.getMax());
        float fraction = Math.max(0f, Math.min(1f, seekBar.getProgress() / (float) totalMs));
        waveformView.setStartMarker(fraction);
    }

    private void setEndMarkerToCurrentPosition() {
        int totalMs = Math.max(1, seekBar.getMax());
        float fraction = Math.max(0f, Math.min(1f, seekBar.getProgress() / (float) totalMs));
        waveformView.setEndMarker(fraction);
    }

    private void syncMediaPlayerToSeekbar() {
        if (mediaPlayer == null) {
            return;
        }
        int targetMs = Math.max(0, Math.min(seekBar.getProgress(), seekBar.getMax()));
        mediaPlayer.seekTo(targetMs);
    }

    private void updateDirectionButtonsUi() {
        if (btnRewind == null || btnPlay == null || btnRepeat == null) {
            return;
        }
        boolean forwardActive = mediaPlayer != null && mediaPlayer.isPlaying() && !reversePlaying;
        boolean reverseActive = reversePlaying || reverseRequested;
        boolean repeatActive = repeatEnabled;
        btnPlay.setColorFilter(forwardActive ? activeIconColor : inactiveIconColor);
        btnRewind.setColorFilter(reverseActive ? activeIconColor : inactiveIconColor);
        btnRepeat.setColorFilter(repeatActive ? activeIconColor : inactiveIconColor);
    }

    private int markerFractionToFrame(float fraction, int totalFrames) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        return Math.max(0, Math.min(totalFrames, Math.round(clamped * totalFrames)));
    }

    private int resolveThemeColor(int attr, int fallbackColorRes) {
        TypedValue typedValue = new TypedValue();
        if (getTheme().resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                return ContextCompat.getColor(this, typedValue.resourceId);
            }
            return typedValue.data;
        }
        return ContextCompat.getColor(this, fallbackColorRes);
    }

    private PcmAudioData decodeAudioToPcm(String filePath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(filePath);

        int audioTrackIndex = -1;
        MediaFormat selectedFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                selectedFormat = fmt;
                break;
            }
        }
        if (audioTrackIndex < 0) {
            extractor.release();
            throw new IOException("No audio track found");
        }

        extractor.selectTrack(audioTrackIndex);
        String mime = selectedFormat.getString(MediaFormat.KEY_MIME);
        if (mime == null || mime.isEmpty()) {
            extractor.release();
            throw new IOException("No audio mime found");
        }
        int sampleRate = selectedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                : 44100;
        int channelCount = selectedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                : 1;

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(selectedFormat, null, null, 0);
        codec.start();

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone) {
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(5000);
                if (inIndex >= 0) {
                    ByteBuffer inBuffer = codec.getInputBuffer(inIndex);
                    if (inBuffer != null) {
                        inBuffer.clear();
                        int size = extractor.readSampleData(inBuffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
            }

            int outIndex = codec.dequeueOutputBuffer(info, 5000);
            if (outIndex >= 0) {
                ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                if (outBuffer != null && info.size > 0) {
                    byte[] chunk = new byte[info.size];
                    outBuffer.get(chunk);
                    pcmOut.write(chunk, 0, chunk.length);
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        byte[] pcmBytes = pcmOut.toByteArray();
        ShortBuffer shortBuffer = ByteBuffer.wrap(pcmBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer();
        short[] samples = new short[shortBuffer.remaining()];
        shortBuffer.get(samples);

        return new PcmAudioData(samples, sampleRate, channelCount);
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null) {
                    int position = mediaPlayer.getCurrentPosition();
                    if (repeatEnabled) {
                        int startMs = getStartMarkerMs();
                        int endMs = getEndMarkerMs();
                        if (endMs > startMs && position >= endMs) {
                            triggerLoopJump(startMs);
                            position = startMs;
                        }
                    }
                    if (!isUserSeeking) {
                        seekBar.setProgress(position);
                        tvCurrent.setText(formatTime(position));
                    }
                    if (forwardPlaying) {
                        progressHandler.postDelayed(this, 150);
                    }
                }
            }
        };
        progressHandler.post(progressRunnable);
    }

    private int getStartMarkerMs() {
        int total = Math.max(1, seekBar.getMax());
        return Math.max(0, Math.min(total, Math.round(waveformView.getStartMarker() * total)));
    }

    private int getEndMarkerMs() {
        int total = Math.max(1, seekBar.getMax());
        return Math.max(0, Math.min(total, Math.round(waveformView.getEndMarker() * total)));
    }

    private void stopProgressUpdates() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void triggerLoopJump(int targetMs) {
        if (mediaPlayer == null || loopFadeActive) {
            return;
        }
        loopFadeActive = true;
        loopFadeTargetMs = Math.max(0, targetMs);
        loopFadeStep = 0;
        progressHandler.removeCallbacks(loopFadeOutRunnable);
        progressHandler.removeCallbacks(loopFadeInRunnable);
        setPlayerVolume(1f);
        progressHandler.post(loopFadeOutRunnable);
    }

    private void stopLoopFade() {
        loopFadeActive = false;
        loopFadeStep = 0;
        progressHandler.removeCallbacks(loopFadeOutRunnable);
        progressHandler.removeCallbacks(loopFadeInRunnable);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setVolume(1f, 1f);
            } catch (Exception ignored) {
            }
        }
    }

    private void setPlayerVolume(float volume) {
        if (mediaPlayer == null) {
            return;
        }
        try {
            mediaPlayer.setVolume(volume, volume);
        } catch (Exception ignored) {
        }
    }

    private void releasePlayer() {
        stopReversePlayback();
        forwardPlaying = false;
        stopLoopFade();
        stopProgressUpdates();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }

    @Override
    public void onRenamed(String newName) {
        viewModel.renameRecording(recording, newName);
        recording.displayName = newName;
        tvName.setText(newName);
    }

    @Override
    protected void onStop() {
        super.onStop();
        pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        reverseExecutor.shutdownNow();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final class PcmAudioData {
        final short[] samples;
        final int sampleRate;
        final int channelCount;

        PcmAudioData(short[] samples, int sampleRate, int channelCount) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channelCount = Math.max(1, channelCount);
        }
    }
}


















