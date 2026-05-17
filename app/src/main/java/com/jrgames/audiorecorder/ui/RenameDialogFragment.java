package com.jrgames.audiorecorder.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jrgames.audiorecorder.R;

import java.util.Locale;

public class RenameDialogFragment extends DialogFragment {

    public interface RenameListener {
        void onRenamed(String newName);
    }

    private static final String ARG_CURRENT_NAME = "current_name";
    private RenameListener renameListener;

    public static RenameDialogFragment newInstance(String currentName) {
        RenameDialogFragment f = new RenameDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CURRENT_NAME, currentName);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof RenameListener) {
            renameListener = (RenameListener) context;
        }
    }

    public void setRenameListener(RenameListener listener) {
        this.renameListener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String currentName = getArguments() != null ? getArguments().getString(ARG_CURRENT_NAME, "") : "";

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(currentName);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rename_title)
                .setView(input)
                .setPositiveButton(R.string.rename_ok, (dialogInterface, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        name = name.substring(0, 1).toUpperCase(Locale.getDefault()) + name.substring(1);
                    }
                    if (!name.isEmpty() && renameListener != null) {
                        renameListener.onRenamed(name);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            input.requestFocus();
            input.selectAll();
        });
        return dialog;
    }
}

