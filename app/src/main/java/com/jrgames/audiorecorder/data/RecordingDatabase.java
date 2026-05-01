package com.jrgames.audiorecorder.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Recording.class}, version = 1, exportSchema = false)
public abstract class RecordingDatabase extends RoomDatabase {

    private static volatile RecordingDatabase INSTANCE;

    public abstract RecordingDao recordingDao();

    public static RecordingDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (RecordingDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            RecordingDatabase.class,
                            "recordings_db"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}

