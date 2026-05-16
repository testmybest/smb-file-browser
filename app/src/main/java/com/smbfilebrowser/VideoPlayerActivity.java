package com.smbfilebrowser;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

/**
 * 视频播放器 - 下载到本地缓存后用系统播放器打开
 * 
 * 参考 SambaLite 的实现：
 * 1. 从 SMB 下载文件到本地缓存目录
 * 2. 使用 FileProvider 生成 content:// URI
 * 3. 调用系统播放器 (Intent.ACTION_VIEW) 播放
 * 
 * 优点：简单可靠，支持所有视频格式，不需要 HTTP 代理服务器
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayer";
    private static final int BUFFER_SIZE = 8192;

    private SMBManager smbManager;
    private String remoteFilePath;
    private String fileName;
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        remoteFilePath = getIntent().getStringExtra("file_path");
        fileName = getIntent().getStringExtra("file_name");

        if (remoteFilePath == null || remoteFilePath.isEmpty()) {
            Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Playing: " + remoteFilePath);
        
        smbManager = SMBManager.getInstance();
        
        // 检查 SMB 连接
        if (!smbManager.isConnected()) {
            Toast.makeText(this, "未连接到 SMB 服务器", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 开始下载并播放
        downloadAndPlay();
    }

    /**
     * 下载文件到本地缓存并播放
     */
    private void downloadAndPlay() {
        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("准备播放");
        progressDialog.setMessage("正在下载文件到本地...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            File tempFile = null;
            try {
                // 构建 SMB 路径
                String smbUrl = "smb://" + smbManager.getCurrentHost() + "/" 
                        + smbManager.getCurrentShare() + "/" + remoteFilePath;
                Log.d(TAG, "SMB URL: " + smbUrl);

                // 打开 SMB 文件
                SmbFile smbFile = new SmbFile(smbUrl, smbManager.getBaseContext());
                long totalSize = smbFile.length();
                Log.d(TAG, "File size: " + totalSize);

                // 创建本地缓存文件
                File cacheDir = new File(getCacheDir(), "video_cache");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
                tempFile = new File(cacheDir, fileName);

                // 下载文件
                try (InputStream in = new SmbFileInputStream(smbFile);
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long downloaded = 0;
                    int read;
                    
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        
                        // 更新进度
                        if (totalSize > 0) {
                            int progress = (int) (downloaded * 100 / totalSize);
                            runOnUiThread(() -> {
                                if (progressDialog != null && progressDialog.isShowing()) {
                                    progressDialog.setProgress(progress);
                                }
                            });
                        }
                    }
                }

                Log.d(TAG, "Download complete: " + tempFile.getAbsolutePath());

                // 使用系统播放器打开
                final File finalTempFile = tempFile;
                runOnUiThread(() -> {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                        progressDialog = null;
                    }
                    openWithSystemPlayer(finalTempFile);
                });

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage(), e);
                // 清理临时文件
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                runOnUiThread(() -> {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                        progressDialog = null;
                    }
                    Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    /**
     * 使用系统播放器打开文件
     */
    private void openWithSystemPlayer(File file) {
        try {
            // 获取 MIME 类型
            String mimeType = getMimeType(fileName);
            Log.d(TAG, "MIME type: " + mimeType);

            // 使用 FileProvider 获取 content:// URI
            Uri contentUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    file);

            // 创建播放 Intent
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 启动播放器
            startActivity(intent);
            
            // 关闭当前 Activity，让用户在系统播放器中观看
            finish();

        } catch (Exception e) {
            Log.e(TAG, "Open player failed: " + e.getMessage(), e);
            Toast.makeText(this, "无法打开播放器: " + e.getMessage(), Toast.LENGTH_LONG).show();
            file.delete();
            finish();
        }
    }

    /**
     * 根据文件名获取 MIME 类型
     */
    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        return "video/*";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
}
