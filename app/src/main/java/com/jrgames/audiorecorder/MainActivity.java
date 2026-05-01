package com.jrgames.audiorecorder;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.jrgames.audiorecorder.data.Recording;
import com.jrgames.audiorecorder.ui.RecordingsAdapter;
import com.jrgames.audiorecorder.ui.RenameDialogFragment;
import com.jrgames.audiorecorder.viewmodel.MainViewModel;

import java.util.List;

public class MainActivity extends AppCompatActivity
        implements RecordingsAdapter.Listener, RenameDialogFragment.RenameListener {

    private MainViewModel viewModel;
    private RecordingsAdapter adapter;
    private FloatingActionButton fab;

    private Recording pendingRenameRecording;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean granted = result.get(Manifest.permission.RECORD_AUDIO);
                if (Boolean.TRUE.equals(granted)) {
                    viewModel.startRecording();
                } else {
                    Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        fab = findViewById(R.id.fab_record);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);

        // Adapter setup
        adapter = new RecordingsAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Drag & Drop
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public boolean onMove(@androidx.annotation.NonNull RecyclerView rv,
                                  @androidx.annotation.NonNull RecyclerView.ViewHolder from,
                                  @androidx.annotation.NonNull RecyclerView.ViewHolder to) {
                adapter.onItemMoved(from.getAdapterPosition(), to.getAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(@androidx.annotation.NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

            @Override
            public void clearView(@androidx.annotation.NonNull RecyclerView rv,
                                  @androidx.annotation.NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(rv, viewHolder);
                adapter.onDragFinished();
            }
        });
        touchHelper.attachToRecyclerView(recyclerView);
        adapter.setTouchHelper(touchHelper);

        // Observe recordings list
        viewModel.getAllRecordings().observe(this, recordings -> {
            adapter.submitList(recordings);
            View emptyView = findViewById(R.id.tv_empty);
            if (emptyView != null) {
                emptyView.setVisibility(recordings.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        // Observe recording state
        viewModel.getIsRecording().observe(this, recording -> {
            fab.setImageResource(Boolean.TRUE.equals(recording)
                    ? R.drawable.ic_stop
                    : R.drawable.ic_microphone);
        });

        // Observe playback
        viewModel.getPlayingRecordingId().observe(this, id -> adapter.setPlayingId(id));
        viewModel.getPlaybackProgress().observe(this, progress -> adapter.setPlaybackProgress(progress));

        // Observe errors
        viewModel.getErrorMessage().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
        });

        // FAB click
        fab.setOnClickListener(v -> {
            Boolean isRecording = viewModel.getIsRecording().getValue();
            if (Boolean.TRUE.equals(isRecording)) {
                viewModel.stopRecording();
            } else {
                checkPermissionAndRecord();
            }
        });
    }

    private void checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            viewModel.startRecording();
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
        }
    }

    @Override
    public void onPlayClicked(Recording recording) {
        viewModel.playRecording(recording);
    }

    @Override
    public void onRenameClicked(Recording recording) {
        pendingRenameRecording = recording;
        RenameDialogFragment dialog = RenameDialogFragment.newInstance(recording.displayName);
        dialog.setRenameListener(this);
        dialog.show(getSupportFragmentManager(), "rename");
    }

    @Override
    public void onDeleteClicked(Recording recording) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, recording.displayName))
                .setPositiveButton(R.string.delete_ok, (d, w) -> viewModel.deleteRecording(recording))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onReordered(List<Recording> reorderedList) {
        viewModel.reorderRecordings(reorderedList);
    }

    @Override
    public void onRenamed(String newName) {
        if (pendingRenameRecording != null) {
            viewModel.renameRecording(pendingRenameRecording, newName);
            pendingRenameRecording = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stop playback when leaving app
        viewModel.stopPlayback();
    }
}

