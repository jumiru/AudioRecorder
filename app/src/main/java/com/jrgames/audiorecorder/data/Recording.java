package com.jrgames.audiorecorder.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "recordings")
public class Recording {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String displayName;
    public String filePath;
    public long durationMs;
    public int sortOrder;
    public long createdAt;

    public Recording(String displayName, String filePath, long durationMs, int sortOrder, long createdAt) {
        this.displayName = displayName;
        this.filePath = filePath;
        this.durationMs = durationMs;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }
}

