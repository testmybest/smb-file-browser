package com.smbfilebrowser;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;

/**
 * 视频/音频播放器页面 - 真正的流式播放
 *
 * 工作原理：
 * 1. 立即开始下载到缓存文件
 * 2. 下载 256KB 后立即开始播放（足够解码器读取文件头）
 * 3. 播放器读取不断增长的本地文件
 * 4. 如果播放追上下载，ExoPlayer 会自动缓冲等待
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private LinearLayout loadingOverlay;
    private ProgressBar progressBar;
    private TextView tvLoadingStatus;
    private TextView tvLoadingDetail;
    private ExoPlayer player;
    private SMBManager smbManager;
    private Handler handler = new Handler(Looper.getMainLooper());

    private String remoteFilePath;
    private String localCachePath;
    private boolean playerStarted = false;
    private boolean isAudio = false;

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
        String fileName = getIntent().getStringExtra("file_name");
        isAudio = getIntent().getBooleanExtra("is_audio", false);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(fileName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (remoteFilePath == null || remoteFilePath.isEmpty()) {
            Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 开始真正的流式播放
        startStreamingPlayback();
    }

    /**
     * 真正的流式播放 — 下载一点就立即开始播放
     */
    private void startStreamingPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");

        smbManager.streamFile(remoteFilePath, this, new SMBManager.StreamListener() {
            @Override
            public void onReady(String localPath, long totalSize) {
                localCachePath = localPath;
                // 已经缓冲了足够数据，立即开始播放
                handler.post(() -> startPlayback(localPath, totalSize));
            }

            @Override
            public void onProgress(int percent, long downloaded, long total, long speed) {
                handler.post(() -> {
                    if (!playerStarted) {
                        tvLoadingStatus.setText("缓冲中 " + percent + "%");
                        tvLoadingDetail.setText(formatSize(downloaded) + " / " + formatSize(total)
                                + "  " + formatSize(speed) + "/s");
                    }
                    // 播放中也在标题显示下载进度
                    if (playerStarted && getSupportActionBar() != null) {
                        getSupportActionBar().setSubtitle("缓存 " + percent + "%");
                    }
                });
            }

            @Override
            public void onComplete(String localPath) {
                handler.post(() -> {
                    if (getSupportActionBar() != null) {
                        getSupportActionBar().setSubtitle("缓存完成");
                    }
                });
            }

            @Override
            public void onFailed(String error) {
                handler.post(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(VideoPlayerActivity.this,
                            "加载失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * 开始播放 — 使用 FileDataSource 支持读取不断增长的文件
     */
    @OptIn(markerClass = UnstableApi.class)
    private void startPlayback(String localPath, long totalSize) {
        try {
            File file = new File(localPath);
            if (!file.exists()) {
                Toast.makeText(this, "缓存文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }

            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            // 使用 FileDataSource 支持读取不断增长的文件
            FileDataSource.Factory dataSourceFactory = new FileDataSource.Factory();
            
            Uri uri = Uri.fromFile(file);
            MediaItem mediaItem = MediaItem.fromUri(uri);
            
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem);
            
            player.setMediaSource(mediaSource);
            player.prepare();
            player.setPlayWhenReady(true);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        // 播放器准备好了，隐藏加载遮罩
                        if (!playerStarted) {
                            loadingOverlay.setVisibility(View.GONE);
                            playerStarted = true;
                        }
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        // 播放追上了下载进度，正在缓冲
                        if (playerStarted) {
                            tvLoadingStatus.setText("缓冲中...");
                            loadingOverlay.setVisibility(View.VISIBLE);
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        // 播放结束
                        finish();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    // 如果是由于文件不够长导致的错误，等待一下重试
                    if (!playerStarted) {
                        handler.postDelayed(() -> {
                            if (!playerStarted && !isFinishing()) {
                                // 重新尝试播放
                                player.release();
                                startPlayback(localPath, totalSize);
                            }
                        }, 500);
                    } else {
                        Toast.makeText(VideoPlayerActivity.this,
                                "播放出错: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            loadingOverlay.setVisibility(View.GONE);
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
