package com.smbfilebrowser;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;

/**
 * 视频/音频播放器 - 边下载边播放
 *
 * 工作原理：
 * 1. 下载到本地缓存文件
 * 2. 视频：缓冲 1MB 或 5% 后开始播放（确保文件头完整）
 * 3. 音频：缓冲 256KB 后开始播放
 * 4. 使用标准 ExoPlayer 播放本地文件
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayer";
    private static final long VIDEO_PREBUFFER_SIZE = 1024 * 1024; // 1MB 视频预缓冲
    private static final long AUDIO_PREBUFFER_SIZE = 256 * 1024;  // 256KB 音频预缓冲

    private PlayerView playerView;
    private LinearLayout loadingOverlay;
    private ProgressBar progressBar;
    private TextView tvLoadingStatus;
    private TextView tvLoadingDetail;
    private ExoPlayer player;
    private SMBManager smbManager;
    private Handler handler = new Handler(Looper.getMainLooper());

    private String remoteFilePath;
    private String fileName;
    private boolean playerStarted = false;
    private boolean isVideo = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        playerView = findViewById(R.id.player_view);
        loadingOverlay = findViewById(R.id.loading_overlay);
        progressBar = findViewById(R.id.progress_bar);
        tvLoadingStatus = findViewById(R.id.tv_loading_status);
        tvLoadingDetail = findViewById(R.id.tv_loading_detail);

        smbManager = SMBManager.getInstance();

        remoteFilePath = getIntent().getStringExtra("file_path");
        fileName = getIntent().getStringExtra("file_name");

        // 判断是视频还是音频
        isVideo = isVideoFile(fileName);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(fileName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (remoteFilePath == null || remoteFilePath.isEmpty()) {
            Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Playing: " + remoteFilePath + " (isVideo: " + isVideo + ")");
        startStreamPlayback();
    }

    private boolean isVideoFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".flv") || lower.endsWith(".wmv")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp") || lower.endsWith(".ts");
    }

    /**
     * 边下载边播放
     */
    private void startStreamPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");
        tvLoadingDetail.setText("");

        smbManager.streamFile(remoteFilePath, this, new SMBManager.StreamListener() {
            private String localPath;
            private long totalSize;
            private boolean readyNotified = false;

            @Override
            public void onReady(String path, long size) {
                Log.d(TAG, "onReady: " + path + ", size: " + size);
                this.localPath = path;
                this.totalSize = size;
                // 只是连接成功，等待足够数据缓冲
            }

            @Override
            public void onProgress(int percent, long downloaded, long total, long speed) {
                if (readyNotified) return;

                // 根据文件类型决定预缓冲大小
                long prebufferSize = isVideo ? VIDEO_PREBUFFER_SIZE : AUDIO_PREBUFFER_SIZE;
                // 对于大文件，预缓冲比例不超过 10%
                long minPrebuffer = Math.min(prebufferSize, total / 10);
                if (minPrebuffer < 64 * 1024) minPrebuffer = 64 * 1024; // 最小 64KB

                if (downloaded >= minPrebuffer) {
                    readyNotified = true;
                    Log.d(TAG, "Prebuffer complete: " + downloaded + " bytes, starting playback");
                    handler.post(() -> {
                        tvLoadingStatus.setText("正在播放...");
                        startPlayer(localPath);
                    });
                } else {
                    handler.post(() -> {
                        tvLoadingStatus.setText("缓冲中 " + percent + "%");
                        tvLoadingDetail.setText(formatSize(downloaded) + " / " + formatSize(minPrebuffer)
                                + "  " + formatSize(speed) + "/s");
                    });
                }
            }

            @Override
            public void onComplete(String path) {
                Log.d(TAG, "onComplete: " + path);
                handler.post(() -> {
                    if (getSupportActionBar() != null) {
                        getSupportActionBar().setSubtitle("缓存完成");
                    }
                });
            }

            @Override
            public void onFailed(String error) {
                Log.e(TAG, "onFailed: " + error);
                handler.post(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(VideoPlayerActivity.this,
                            "加载失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * 使用 ExoPlayer 播放本地文件
     */
    private void startPlayer(String localPath) {
        try {
            File file = new File(localPath);
            if (!file.exists()) {
                Toast.makeText(this, "缓存文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }

            // 对于视频文件，确保文件头已经下载（MP4 文件头通常在开头）
            if (isVideo && file.length() < 64 * 1024) {
                // 等待更多数据
                handler.postDelayed(() -> startPlayer(localPath), 500);
                return;
            }

            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            // 使用标准方式播放本地文件
            Uri uri = Uri.fromFile(file);
            MediaItem mediaItem = MediaItem.fromUri(uri);

            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Log.d(TAG, "Playback state: " + playbackState);
                    if (playbackState == Player.STATE_READY) {
                        if (!playerStarted) {
                            loadingOverlay.setVisibility(View.GONE);
                            playerStarted = true;
                            Log.d(TAG, "Playback started successfully");
                        }
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        Log.d(TAG, "Buffering...");
                    } else if (playbackState == Player.STATE_ENDED) {
                        finish();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Player error: " + error.getMessage(), error);
                    // 如果是文件不够长导致的错误，尝试等待后重试
                    if (!playerStarted && isVideo) {
                        Log.d(TAG, "Retrying in 1 second...");
                        handler.postDelayed(() -> {
                            if (!isFinishing()) {
                                player.release();
                                startPlayer(localPath);
                            }
                        }, 1000);
                    } else {
                        loadingOverlay.setVisibility(View.GONE);
                        Toast.makeText(VideoPlayerActivity.this,
                                "播放出错: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

            Log.d(TAG, "Player created and preparing...");

        } catch (Exception e) {
            Log.e(TAG, "startPlayer error: " + e.getMessage(), e);
            loadingOverlay.setVisibility(View.GONE);
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        handler.removeCallbacksAndMessages(null);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
