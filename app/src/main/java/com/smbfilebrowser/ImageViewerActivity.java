package com.smbfilebrowser;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;

/**
 * 图片查看器页面
 */
public class ImageViewerActivity extends AppCompatActivity {

    private SMBManager smbManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        smbManager = SMBManager.getInstance();

        String filePath = getIntent().getStringExtra("file_path");
        String fileName = getIntent().getStringExtra("file_name");

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(fileName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        loadImage(filePath);
    }

    private void loadImage(String filePath) {
        new Thread(() -> {
            try {
                InputStream inputStream = smbManager.getInputStream(filePath);
                // 这里可以使用Glide或其他图片加载库加载图片
                runOnUiThread(() -> {
                    // 加载图片
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "加载图片失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
