package com.example.filerenamer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG              = "FileRenamer";
    private static final String XYZ_SUFFIX       = ".xyz";
    private static final int    REQ_LEGACY_PERM  = 100;

    /**
     * Well-known config file location users can edit with any file manager or PC.
     * Format: one line containing the absolute folder path, e.g.
     *   /sdcard/DCIM/Camera
     */
    private static final String CONFIG_FILE_PATH =
            Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/filerenamer.config";

    // UI components
    private MaterialButton            btnSelectFolder;
    private TextView                  tvStatus;
    private LinearLayout              statusContainer;
    private ImageView                 statusIcon;
    private LinearProgressIndicator   progressIndicator;

    // Background thread executor
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // -------------------------------------------------------------------------
    // Launcher: system "All Files Access" settings screen (API 30+)
    // -------------------------------------------------------------------------
    private final ActivityResultLauncher<Intent> manageStorageLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // User returned from settings — check again
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                && Environment.isExternalStorageManager()) {
                            readConfigAndStart();
                        } else {
                            showStatus(
                                    "\"All Files Access\" permission is required to read "
                                            + CONFIG_FILE_PATH + ".\n"
                                            + "Grant it in Settings and tap the button again.",
                                    StatusType.ERROR);
                        }
                    });

    // -------------------------------------------------------------------------
    // Launcher: SAF folder picker (fallback)
    // -------------------------------------------------------------------------
    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            if (result.getResultCode() == RESULT_OK
                                    && result.getData() != null) {
                                Uri treeUri = result.getData().getData();
                                if (treeUri != null) {
                                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                                    getContentResolver()
                                            .takePersistableUriPermission(treeUri, flags);
                                    startRenaming(treeUri);
                                }
                            }
                        }
                    });

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSelectFolder   = findViewById(R.id.btnSelectFolder);
        tvStatus          = findViewById(R.id.tvStatus);
        statusContainer   = findViewById(R.id.statusContainer);
        statusIcon        = findViewById(R.id.statusIcon);
        progressIndicator = findViewById(R.id.progressIndicator);

        btnSelectFolder.setOnClickListener(v -> onSelectFolderClicked());

        // Auto-read config on launch
        checkPermissionsAndReadConfig();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!executor.isShutdown()) executor.shutdown();
    }

    // -------------------------------------------------------------------------
    // Entry point: permission gating → read config → rename
    // -------------------------------------------------------------------------

    /**
     * Called on launch (and again when the button is tapped).
     * Routes through permission checks before reading the config file.
     */
    private void checkPermissionsAndReadConfig() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: need "All Files Access"
            if (!Environment.isExternalStorageManager()) {
                showStatus(
                        "\"All Files Access\" permission is needed to read\n"
                                + CONFIG_FILE_PATH + ".\n\n"
                                + "Tap the button to grant it in Settings.",
                        StatusType.INFO);
                // Don't auto-redirect; wait for the user to tap the button
                // so they understand why the settings screen opens.
                return;
            }
        } else {
            // API 21–29: need READ/WRITE_EXTERNAL_STORAGE
            boolean readGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            boolean writeGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!readGranted || !writeGranted) {
                // Will continue in onRequestPermissionsResult
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        REQ_LEGACY_PERM);
                return;
            }
        }

        readConfigAndStart();
    }

    /** Read the config file and kick off renaming. */
    private void readConfigAndStart() {
        File configFile = new File(CONFIG_FILE_PATH);

        if (!configFile.exists()) {
            showStatus(
                    "Config file not found.\n\n"
                            + "Create \"filerenamer.config\" in the root of your storage "
                            + "(" + CONFIG_FILE_PATH + ") "
                            + "and put the target folder path on the first line.\n\n"
                            + "Example:\n/sdcard/Downloads",
                    StatusType.INFO);
            return;
        }

        String folderPath;
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            folderPath = reader.readLine();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read config file", e);
            showStatus("Could not read " + CONFIG_FILE_PATH + ":\n" + e.getMessage(),
                    StatusType.ERROR);
            return;
        }

        if (folderPath == null || folderPath.trim().isEmpty()) {
            showStatus("Config file is empty.\n\nAdd the target folder path on the first line.",
                    StatusType.ERROR);
            return;
        }

        folderPath = folderPath.trim();
        File targetDir = new File(folderPath);

        if (!targetDir.exists() || !targetDir.isDirectory()) {
            showStatus("Folder not found:\n" + folderPath
                    + "\n\nCheck the path in " + CONFIG_FILE_PATH,
                    StatusType.ERROR);
            return;
        }

        // All good — convert to a DocumentFile and start renaming
        Uri folderUri = Uri.fromFile(targetDir);
        DocumentFile docDir = DocumentFile.fromFile(targetDir);
        startRenaming(docDir);
    }

    // -------------------------------------------------------------------------
    // Button click
    // -------------------------------------------------------------------------

    private void onSelectFolderClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Open the system "All Files Access" settings page
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                manageStorageLauncher.launch(intent);
                return;
            }
        }
        // If permission is already granted (or below API 30) try config again
        checkPermissionsAndReadConfig();
    }

    // -------------------------------------------------------------------------
    // Runtime permission result (API < 30)
    // -------------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LEGACY_PERM) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                readConfigAndStart();
            } else {
                showStatus("Storage permission denied. Cannot read " + CONFIG_FILE_PATH,
                        StatusType.ERROR);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rename logic — runs on a background thread via ExecutorService
    // -------------------------------------------------------------------------

    /** Overload accepting a DocumentFile directory directly (from config path). */
    private void startRenaming(DocumentFile directory) {
        runOnUiThread(() -> {
            btnSelectFolder.setEnabled(false);
            progressIndicator.setVisibility(View.VISIBLE);
            statusContainer.setVisibility(View.GONE);
            showStatus("Renaming files…", StatusType.INFO);
        });

        executor.execute(() -> {
            int successCount = 0;
            int failCount    = 0;
            int skipCount    = 0;

            try {
                if (directory == null || !directory.isDirectory()) {
                    postStatus("Unable to access the selected folder.", StatusType.ERROR);
                    return;
                }

                DocumentFile[] files = directory.listFiles();

                if (files == null || files.length == 0) {
                    postStatus("No files found in the folder.", StatusType.INFO);
                    return;
                }

                for (DocumentFile file : files) {
                    if (!file.isFile()) continue;

                    String originalName = file.getName();
                    if (originalName == null) {
                        Log.w(TAG, "Skipping file with null name.");
                        failCount++;
                        continue;
                    }

                    // Skip files already having .xyz extension
                    if (originalName.endsWith(XYZ_SUFFIX)) {
                        Log.d(TAG, "Already has .xyz, skipping: " + originalName);
                        skipCount++;
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
                    message = "Renamed " + successCount + "/" + total + " file(s) successfully ✓";
                    if (skipCount > 0) {
                        message += "\n" + skipCount + " file(s) skipped (already have .xyz).";
                    }
                    type = StatusType.SUCCESS;
                } else {
                    message = "Renamed " + successCount + "/" + total + " file(s).\n"
                            + failCount + " file(s) could not be renamed.";
                    if (skipCount > 0) {
                        message += "\n" + skipCount + " file(s) skipped (already have .xyz).";
                    }
                    type = StatusType.WARNING;
                }

                postStatus(message, type);

            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during renaming", e);
                postStatus("An unexpected error occurred: " + e.getMessage(), StatusType.ERROR);
            }
        });
    }

    /** Overload accepting a SAF URI (kept as fallback). */
    private void startRenaming(Uri treeUri) {
        DocumentFile directory = DocumentFile.fromTreeUri(this, treeUri);
        startRenaming(directory);
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void postStatus(String message, StatusType type) {
        runOnUiThread(() -> {
            progressIndicator.setVisibility(View.GONE);
            btnSelectFolder.setEnabled(true);
            showStatus(message, type);
        });
    }

    private void showStatus(String message, StatusType type) {
        tvStatus.setText(message);
        statusContainer.setVisibility(View.VISIBLE);

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
