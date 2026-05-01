package com.jrgames.audiorecorder.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.jrgames.audiorecorder.data.Recording;
import com.jrgames.audiorecorder.data.RecordingDao;
import com.jrgames.audiorecorder.data.RecordingDatabase;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingRepository {

    private final RecordingDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public RecordingRepository(Context context) {
        dao = RecordingDatabase.getInstance(context).recordingDao();
    }

    public LiveData<List<Recording>> getAllRecordings() {
        return dao.getAll();
    }

    public void insert(Recording recording, InsertCallback callback) {
        executor.execute(() -> {
            long id = dao.insert(recording);
            if (callback != null) callback.onInserted(id);
        });
    }

    public void update(Recording recording) {
        executor.execute(() -> dao.update(recording));
    }

    public void delete(Recording recording) {
        executor.execute(() -> {
            // Delete physical file
            File file = new File(recording.filePath);
            if (file.exists()) file.delete();
            dao.delete(recording);
        });
    }

    public void updateAll(List<Recording> recordings) {
        executor.execute(() -> {
            for (Recording r : recordings) {
                dao.update(r);
            }
        });
    }

    public int getCount() {
        // Must be called off main thread
        return dao.getCount();
    }

    public interface InsertCallback {
        void onInserted(long id);
    }
}

