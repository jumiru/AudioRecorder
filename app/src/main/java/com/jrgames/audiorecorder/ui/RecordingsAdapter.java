package com.jrgames.audiorecorder.ui;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.jrgames.audiorecorder.R;
import com.jrgames.audiorecorder.data.Recording;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class RecordingsAdapter extends ListAdapter<Recording, RecordingsAdapter.ViewHolder> {

    public interface Listener {
        void onPlayClicked(Recording recording);
        void onUploadClicked(Recording recording);
        void onTrimClicked(Recording recording);
        void onRenameClicked(Recording recording);
        void onDeleteClicked(Recording recording);
        void onReordered(List<Recording> reorderedList);
    }

    private final Listener listener;
    private long playingId = -1;
    private int playbackProgress = 0;
    private ItemTouchHelper touchHelper;

    private static final DiffUtil.ItemCallback<Recording> DIFF = new DiffUtil.ItemCallback<Recording>() {
        @Override
        public boolean areItemsTheSame(@NonNull Recording a, @NonNull Recording b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Recording a, @NonNull Recording b) {
            return a.displayName.equals(b.displayName) && a.sortOrder == b.sortOrder;
        }
    };

    public RecordingsAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    public void setTouchHelper(ItemTouchHelper helper) {
        this.touchHelper = helper;
    }

    public void setPlayingId(long id) {
        this.playingId = id;
        notifyDataSetChanged();
    }

    public void setPlaybackProgress(int progress) {
        this.playbackProgress = progress;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recording, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Recording recording = getItem(position);
        holder.bind(recording, playingId == recording.id, playbackProgress);

        holder.btnPlay.setOnClickListener(v -> listener.onPlayClicked(recording));
        holder.btnUpload.setOnClickListener(v -> listener.onUploadClicked(recording));
        holder.btnTrim.setOnClickListener(v -> listener.onTrimClicked(recording));
        holder.btnRename.setOnClickListener(v -> listener.onRenameClicked(recording));
        holder.btnDelete.setOnClickListener(v -> listener.onDeleteClicked(recording));

        holder.dragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                touchHelper.startDrag(holder);
            }
            return false;
        });
    }

    // Called by ItemTouchHelper during drag
    public void onItemMoved(int from, int to) {
        List<Recording> list = new ArrayList<>(getCurrentList());
        Collections.swap(list, from, to);
        submitList(new ArrayList<>(list));
    }

    public void onDragFinished() {
        listener.onReordered(new ArrayList<>(getCurrentList()));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvDuration;
        ImageButton btnPlay, btnUpload, btnTrim, btnRename, btnDelete;
        View dragHandle;
        ProgressBar progressBar;

        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvDuration = v.findViewById(R.id.tv_duration);
            btnPlay = v.findViewById(R.id.btn_play);
            btnUpload = v.findViewById(R.id.btn_upload);
            btnTrim = v.findViewById(R.id.btn_trim);
            btnRename = v.findViewById(R.id.btn_rename);
            btnDelete = v.findViewById(R.id.btn_delete);
            dragHandle = v.findViewById(R.id.drag_handle);
            progressBar = v.findViewById(R.id.progress_bar);
        }

        void bind(Recording r, boolean isPlaying, int progress) {
            tvName.setText(r.displayName);
            tvDuration.setText(formatDuration(r.durationMs));
            btnPlay.setImageResource(isPlaying
                    ? R.drawable.ic_stop
                    : R.drawable.ic_play);
            progressBar.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
            progressBar.setProgress(progress);
        }

        private String formatDuration(long ms) {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(ms);
            long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
        }
    }
}

