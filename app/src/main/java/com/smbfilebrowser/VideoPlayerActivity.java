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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;

/**
 * 视频/音频播放器页面 - 支持流式播放
 *
 * 工作流程：
 * 1. 接收 SMB 文件路径
 * 2. 后台开始流式下载到缓存
 * 3. 缓冲足够数据后（~2MB）开始播放
 * 4. 后台继续下载，播放器读取不断增长的本地文件
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

        // 开始流式下载
        startStreaming();
    }

    /**
     * 开始流式下载并播放
     */
    private void startStreaming() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在缓冲...");

        smbManager.streamFile(remoteFilePath, this, new SMBManager.StreamListener() {
            @Override
            public void onReady(String localPath, long totalSize) {
                localCachePath = localPath;
                // 在主线程开始播放
                handler.post(() -> startPlayback(localPath));
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
     * 开始播放本地缓存文件
     */
    @OptIn(markerClass = UnstableApi.class)
    private void startPlayback(String localPath) {
        try {
            File file = new File(localPath);
            if (!file.exists()) {
                Toast.makeText(this, "缓存文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }

            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            Uri uri = Uri.fromFile(file);
            MediaItem mediaItem = new MediaItem.fromUri(uri);
            player.setMediaItem(mediaItem);
            player.prepare();
            player.playWhenReady = true;

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        // 播放器准备好了，隐藏加载遮罩
                        loadingOverlay.setVisibility(View.GONE);
                        playerStarted = true;
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        // 如果是短暂的缓冲，不显示全屏遮罩
                        // ExoPlayer 自身有缓冲指示器
                    } else if (playbackState == Player.STATE_ENDED) {
                        // 播放结束
                        finish();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Toast.makeText(VideoPlayerActivity.this,
                            "播放出错: " + error.getMessage(), Toast.LENGTH_LONG).show();
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
