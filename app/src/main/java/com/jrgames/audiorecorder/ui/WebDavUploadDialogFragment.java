package com.jrgames.audiorecorder.ui;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jrgames.audiorecorder.R;
import com.jrgames.audiorecorder.data.Recording;
import com.jrgames.audiorecorder.webdav.WebDavConfig;
import com.jrgames.audiorecorder.webdav.WebDavUploader;
import java.io.File;
public class WebDavUploadDialogFragment extends DialogFragment {
    private static final String ARG_FILE_PATH    = "file_path";
    private static final String ARG_DISPLAY_NAME = "display_name";
    public static WebDavUploadDialogFragment newInstance(Recording recording) {
        WebDavUploadDialogFragment f = new WebDavUploadDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_FILE_PATH,    recording.filePath);
        args.putString(ARG_DISPLAY_NAME, recording.displayName);
        f.setArguments(args);
        return f;
    }
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_webdav_upload, null);
        EditText etServer   = view.findViewById(R.id.et_webdav_server);
        EditText etUser     = view.findViewById(R.id.et_webdav_user);
        EditText etPassword = view.findViewById(R.id.et_webdav_password);
        EditText etDir      = view.findViewById(R.id.et_webdav_dir);
        Button   btnUpload  = view.findViewById(R.id.btn_webdav_upload);
        ProgressBar progress = view.findViewById(R.id.webdav_progress);
        TextView tvStatus   = view.findViewById(R.id.tv_webdav_status);
        // Load saved config
        WebDavConfig cfg = WebDavConfig.load(requireContext());
        etServer.setText(cfg.server);
        etUser.setText(cfg.username);
        etPassword.setText(cfg.password);
        etDir.setText(cfg.directory);
        String filePath = getArguments() != null ? getArguments().getString(ARG_FILE_PATH) : "";
        btnUpload.setOnClickListener(v -> {
            String server = etServer.getText().toString().trim();
            if (server.isEmpty()) {
                etServer.setError(getString(R.string.webdav_error_server_empty));
                return;
            }
            cfg.server    = server;
            cfg.username  = etUser.getText().toString();
            cfg.password  = etPassword.getText().toString();
            cfg.directory = etDir.getText().toString().trim();
            cfg.save(requireContext());
            btnUpload.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.webdav_uploading);
            tvStatus.setVisibility(View.VISIBLE);
            File file = new File(filePath);
            new WebDavUploader().upload(cfg, file, new WebDavUploader.UploadCallback() {
                @Override
                public void onSuccess() {
                    requireActivity().runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        tvStatus.setText(R.string.webdav_upload_success);
                        btnUpload.setEnabled(true);
                        Toast.makeText(requireContext(), R.string.webdav_upload_success, Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
                }
                @Override
                public void onFailure(String error) {
                    requireActivity().runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        tvStatus.setText(getString(R.string.webdav_upload_error, error));
                        btnUpload.setEnabled(true);
                    });
                }
            });
        });
        String displayName = getArguments() != null ? getArguments().getString(ARG_DISPLAY_NAME) : "";
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.webdav_upload_title, displayName))
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create();
    }
}