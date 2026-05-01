package com.jrgames.audiorecorder.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY sortOrder ASC")
    LiveData<List<Recording>> getAll();

    @Insert
    long insert(Recording recording);

    @Update
    void update(Recording recording);

    @Delete
    void delete(Recording recording);

    @Query("SELECT COUNT(*) FROM recordings")
    int getCount();
}

