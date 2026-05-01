package com.jrgames.audiorecorder.viewmodel;

import android.app.Application;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.jrgames.audiorecorder.data.Recording;
import com.jrgames.audiorecorder.repository.RecordingRepository;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainViewModel extends AndroidViewModel {

    private final RecordingRepository repository;

    // Recording state
    private final MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);
    private MediaRecorder mediaRecorder;
    private String currentFilePath;
    private long recordingStartTime;

    // Playback state
    private final MutableLiveData<Long> playingRecordingId = new MutableLiveData<>(-1L);
    private final MutableLiveData<Integer> playbackProgress = new MutableLiveData<>(0);
    private MediaPlayer mediaPlayer;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    // Status messages
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new RecordingRepository(application);
    }

    public LiveData<List<Recording>> getAllRecordings() {
        return repository.getAllRecordings();
    }

    public LiveData<Boolean> getIsRecording() {
        return isRecording;
    }

    public LiveData<Long> getPlayingRecordingId() {
        return playingRecordingId;
    }

    public LiveData<Integer> getPlaybackProgress() {
        return playbackProgress;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    // ── Recording ──────────────────────────────────────────────────────────────

    public void startRecording() {
        File dir = getApplication().getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) {
            errorMessage.setValue("Speicherpfad nicht verfügbar");
            return;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        currentFilePath = new File(dir, "REC_" + timestamp + ".m4a").getAbsolutePath();

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioSamplingRate(44100);
        mediaRecorder.setAudioEncodingBitRate(128000);
        mediaRecorder.setOutputFile(currentFilePath);

        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            recordingStartTime = System.currentTimeMillis();
            isRecording.setValue(true);
        } catch (IOException e) {
            errorMessage.setValue("Aufnahme konnte nicht gestartet werden: " + e.getMessage());
            releaseRecorder();
        }
    }

    public void stopRecording() {
        if (mediaRecorder == null) return;
        try {
            mediaRecorder.stop();
        } catch (RuntimeException e) {
            // Recording too short / error
            new File(currentFilePath).delete();
            errorMessage.setValue("Aufnahme zu kurz oder fehlerhaft");
            releaseRecorder();
            isRecording.setValue(false);
            return;
        }
        long duration = System.currentTimeMillis() - recordingStartTime;
        releaseRecorder();
        isRecording.setValue(false);

        String name = "Aufnahme " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date());
        executor.execute(() -> {
            int count = repository.getCount();
            Recording recording = new Recording(name, currentFilePath, duration, count, System.currentTimeMillis());
            repository.insert(recording, null);
        });
    }

    private void releaseRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    // ── Playback ───────────────────────────────────────────────────────────────

    public void playRecording(Recording recording) {
        // If already playing this one → stop
        Long currentId = playingRecordingId.getValue();
        if (currentId != null && currentId == recording.id) {
            stopPlayback();
            return;
        }
        stopPlayback();

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(recording.filePath);
            mediaPlayer.prepare();
            mediaPlayer.start();
            playingRecordingId.setValue(recording.id);
            startProgressUpdates(recording.durationMs);

            mediaPlayer.setOnCompletionListener(mp -> {
                stopPlayback();
            });
        } catch (IOException e) {
            errorMessage.setValue("Wiedergabe fehlgeschlagen: " + e.getMessage());
            releasePlayer();
        }
    }

    public void stopPlayback() {
        stopProgressUpdates();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
        releasePlayer();
        playingRecordingId.setValue(-1L);
        playbackProgress.setValue(0);
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void startProgressUpdates(long durationMs) {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    int pos = mediaPlayer.getCurrentPosition();
                    int progress = durationMs > 0 ? (int) (pos * 100 / durationMs) : 0;
                    playbackProgress.setValue(progress);
                    progressHandler.postDelayed(this, 200);
                }
            }
        };
        progressHandler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    // ── CRUD ───────────────────────────────────────────────────────────────────

    public void renameRecording(Recording recording, String newName) {
        recording.displayName = newName;
        repository.update(recording);
    }

    public void deleteRecording(Recording recording) {
        Long currentId = playingRecordingId.getValue();
        if (currentId != null && currentId == recording.id) {
            stopPlayback();
        }
        repository.delete(recording);
    }

    public void reorderRecordings(List<Recording> reorderedList) {
        for (int i = 0; i < reorderedList.size(); i++) {
            reorderedList.get(i).sortOrder = i;
        }
        repository.updateAll(reorderedList);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopRecording();
        stopPlayback();
        executor.shutdown();
    }
}

