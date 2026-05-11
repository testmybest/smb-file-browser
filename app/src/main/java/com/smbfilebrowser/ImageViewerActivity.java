package com.smbfilebrowser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * 图片查看器页面 - 先下载到缓存再显示
 */
public class ImageViewerActivity extends AppCompatActivity {

    private ImageView imageView;
    private ProgressBar progressBar;
    private SMBManager smbManager;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        imageView = findViewById(R.id.image_view);
        progressBar = findViewById(R.id.progress_bar);

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
        progressBar.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);

        smbManager.streamFile(filePath, this, new SMBManager.StreamListener() {
            @Override
            public void onReady(String localPath, long totalSize) {
                // 图片通常较小，等下载完成再显示
            }

            @Override
            public void onProgress(int percent, long downloaded, long total, long speed) {
                handler.post(() -> {
                    if (getSupportActionBar() != null) {
                        getSupportActionBar().setSubtitle("加载中 " + percent + "%");
                    }
                });
            }

            @Override
            public void onComplete(String localPath) {
                handler.post(() -> {
                    try {
                        File file = new File(localPath);
                        if (file.exists()) {
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            // 先获取图片尺寸
                            options.inJustDecodeBounds = true;
                            BitmapFactory.decodeFile(localPath, options);

                            // 计算采样率，避免OOM
                            options.inSampleSize = calculateInSampleSize(options, 1920, 1920);
                            options.inJustDecodeBounds = false;

                            Bitmap bitmap = BitmapFactory.decodeFile(localPath, options);
                            if (bitmap != null) {
                                imageView.setImageBitmap(bitmap);
                                imageView.setVisibility(View.VISIBLE);
                                progressBar.setVisibility(View.GONE);
                                if (getSupportActionBar() != null) {
                                    getSupportActionBar().setSubtitle(options.outWidth + " x " + options.outHeight);
                                }
                            } else {
                                Toast.makeText(ImageViewerActivity.this, "无法解码图片", Toast.LENGTH_SHORT).show();
                                progressBar.setVisibility(View.GONE);
                            }
                        }
                    } catch (Exception e) {
                        Toast.makeText(ImageViewerActivity.this, "显示图片失败", Toast.LENGTH_SHORT).show();
                        progressBar.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onFailed(String error) {
                handler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ImageViewerActivity.this,
                            "加载失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        // 回收Bitmap
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
    }
}
