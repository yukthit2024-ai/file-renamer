package com.example.filerenamer;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.widget.TextView;

public class AboutActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        String buildInfo = "Randomizer\n" +
                "Timestamp: " + BuildConfig.BUILD_TIMESTAMP + "\n" +
                "Commit: " + BuildConfig.GIT_SHA + "\n" +
                "Full SHA: " + BuildConfig.GIT_SHA_FULL + "\n" +
//                "Tag: " + BuildConfig.GIT_TAG + "\n\n" +
                getString(R.string.about_description);

        TextView tvBuildInfo = findViewById(R.id.tvBuildInfo);
        tvBuildInfo.setText(buildInfo);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_about);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
