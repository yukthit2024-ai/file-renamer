package com.example.filerenamer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FileRenamer";
    private static final int REQUEST_STORAGE_PERMISSION = 100;
    private static final String XYZ_SUFFIX = ".xyz";

    // UI components
    private MaterialButton btnSelectFolder;
    private TextView tvStatus;
    private LinearLayout statusContainer;
    private ImageView statusIcon;
    private LinearProgressIndicator progressIndicator;

    // Background thread executor — single thread to serialize rename operations
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Activity Result launcher for the folder picker (SAF)
    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                                Uri treeUri = result.getData().getData();
                                if (treeUri != null) {
                                    // Persist read + write access so we can use it in future sessions
                                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                                    getContentResolver().takePersistableUriPermission(treeUri, flags);

                                    // Start renaming on a background thread
                                    startRenaming(treeUri);
                                }
                            }
                            // If result is RESULT_CANCELED or data is null the user cancelled — do nothing
                        }
                    }
            );

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views
        btnSelectFolder    = findViewById(R.id.btnSelectFolder);
        tvStatus           = findViewById(R.id.tvStatus);
        statusContainer    = findViewById(R.id.statusContainer);
        statusIcon         = findViewById(R.id.statusIcon);
        progressIndicator  = findViewById(R.id.progressIndicator);

        btnSelectFolder.setOnClickListener(v -> onSelectFolderClicked());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Shut down the executor when the activity is destroyed to avoid leaks
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Button click — permission check then open folder picker
    // -------------------------------------------------------------------------

    private void onSelectFolderClicked() {
        // On API 26–32 we need runtime READ/WRITE_EXTERNAL_STORAGE.
        // On API 33+ those permissions are replaced by more granular ones;
        // ACTION_OPEN_DOCUMENT_TREE works without them via SAF, so we only
        // request legacy permissions on the older range.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            boolean readGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean writeGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

            if (!readGranted || !writeGranted) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        REQUEST_STORAGE_PERMISSION
                );
                return; // Wait for the callback before opening the picker
            }
        }

        openFolderPicker();
    }

    /** Launch the system folder picker (Storage Access Framework). */
    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // Optional: let the SAF show all storage roots
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    // -------------------------------------------------------------------------
    // Runtime permission result
    // -------------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                openFolderPicker();
            } else {
                showStatus("Storage permission denied. Cannot access files.", StatusType.ERROR);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rename logic — runs on background thread via ExecutorService
    // -------------------------------------------------------------------------

    private void startRenaming(Uri treeUri) {
        // Show progress UI on the main thread
        runOnUiThread(() -> {
            btnSelectFolder.setEnabled(false);
            progressIndicator.setVisibility(View.VISIBLE);
            statusContainer.setVisibility(View.GONE);
            showStatus("Renaming files…", StatusType.INFO);
        });

        // Kick off the heavy work on a background thread
        executor.execute(() -> {
            int successCount = 0;
            int failCount    = 0;

            try {
                DocumentFile directory = DocumentFile.fromTreeUri(this, treeUri);

                if (directory == null || !directory.isDirectory()) {
                    postStatus("Unable to access the selected folder.", StatusType.ERROR);
                    return;
                }

                DocumentFile[] files = directory.listFiles();

                // Guard: empty folder
                if (files == null || files.length == 0) {
                    postStatus("No files found in the selected folder.", StatusType.INFO);
                    return;
                }

                for (DocumentFile file : files) {
                    // Skip sub-directories — only rename actual files
                    if (!file.isFile()) {
                        continue;
                    }

                    String originalName = file.getName();
                    if (originalName == null) {
                        Log.w(TAG, "Skipping file with null name.");
                        failCount++;
                        continue;
                    }

                    // Skip if already suffixed (idempotent re-runs)
                    if (originalName.endsWith(XYZ_SUFFIX)) {
                        Log.d(TAG, "Already renamed, skipping: " + originalName);
                        successCount++;
                        continue;
                    }

                    String newName = originalName + XYZ_SUFFIX;
                    boolean renamed = file.renameTo(newName);

                    if (renamed) {
                        successCount++;
                        Log.d(TAG, "Renamed: " + originalName + " → " + newName);
                    } else {
                        failCount++;
                        Log.w(TAG, "Failed to rename: " + originalName);
                    }
                }

                // Build result message
                int total = successCount + failCount;
                String message;
                StatusType type;

                if (failCount == 0) {
                    message = "Renamed " + successCount + "/" + total + " files successfully ✓";
                    type    = StatusType.SUCCESS;
                } else {
                    message = "Renamed " + successCount + "/" + total + " files successfully\n"
                            + failCount + " file(s) could not be renamed.";
                    type    = StatusType.WARNING;
                }

                postStatus(message, type);

            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during renaming", e);
                postStatus("An unexpected error occurred: " + e.getMessage(), StatusType.ERROR);
            }
        });
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    /** Post a status update from a background thread to the UI thread. */
    private void postStatus(String message, StatusType type) {
        runOnUiThread(() -> {
            progressIndicator.setVisibility(View.GONE);
            btnSelectFolder.setEnabled(true);
            showStatus(message, type);
        });
    }

    /** Update the status UI (must be called on the main thread). */
    private void showStatus(String message, StatusType type) {
        tvStatus.setText(message);
        statusContainer.setVisibility(View.VISIBLE);

        // Update the status icon tint and drawable based on the type
        int iconRes;
        int tintColor;

        switch (type) {
            case SUCCESS:
                iconRes   = R.drawable.ic_check_circle;
                tintColor = ContextCompat.getColor(this, R.color.status_success);
                break;
            case WARNING:
                iconRes   = R.drawable.ic_warning;
                tintColor = ContextCompat.getColor(this, R.color.status_warning);
                break;
            case ERROR:
                iconRes   = R.drawable.ic_error;
                tintColor = ContextCompat.getColor(this, R.color.status_error);
                break;
            default: // INFO
                iconRes   = R.drawable.ic_info;
                tintColor = ContextCompat.getColor(this, R.color.primary);
                break;
        }

        statusIcon.setImageResource(iconRes);
        statusIcon.setColorFilter(tintColor);
        tvStatus.setTextColor(tintColor);
    }

    // -------------------------------------------------------------------------
    // Status type enum
    // -------------------------------------------------------------------------

    private enum StatusType {
        SUCCESS, WARNING, ERROR, INFO
    }
}
