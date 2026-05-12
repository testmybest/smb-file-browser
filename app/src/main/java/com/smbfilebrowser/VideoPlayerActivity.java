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
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Locale;

/**
 * 视频/音频播放器 - 直接从 SMB 流播放（真正秒开）
 *
 * 原理：通过自定义 SmbDataSource 让 ExoPlayer 直接读取 SMB 网络流
 * 就像播放 HTTP 网络视频一样，不需要先下载到本地
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
    private boolean playerStarted = false;

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

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(fileName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (remoteFilePath == null || remoteFilePath.isEmpty()) {
            Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 直接从 SMB 流播放
        startDirectPlayback();
    }

    /**
     * 直接从 SMB 流播放 — 不需要下载，像看网络视频一样
     */
    @OptIn(markerClass = UnstableApi.class)
    private void startDirectPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");

        new Thread(() -> {
            try {
                // 创建 SMB DataSource 工厂
                SmbDataSource.Factory dataSourceFactory = new SmbDataSource.Factory(
                        smbManager.getCurrentHost(),
                        smbManager.getCurrentShare(),
                        smbManager.getCurrentUsername(),
                        smbManager.getCurrentPassword(),
                        smbManager.getBaseContext()
                );

                // 构建 SMB 文件的 URI（用 smb:// 前缀让 ExoPlayer 识别）
                String smbUri = "smb://" + smbManager.getCurrentHost()
                        + "/" + smbManager.getCurrentShare()
                        + "/" + remoteFilePath;

                handler.post(() -> {
                    try {
                        player = new ExoPlayer.Builder(VideoPlayerActivity.this).build();
                        playerView.setPlayer(player);

                        MediaItem mediaItem = new MediaItem.Builder()
                                .setUri(Uri.parse(smbUri))
                                .build();

                        ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(mediaItem);

                        player.setMediaSource(mediaSource);
                        player.prepare();
                        player.setPlayWhenReady(true);

                        player.addListener(new Player.Listener() {
                            @Override
                            public void onPlaybackStateChanged(int playbackState) {
                                if (playbackState == Player.STATE_READY) {
                                    loadingOverlay.setVisibility(View.GONE);
                                    playerStarted = true;
                                } else if (playbackState == Player.STATE_BUFFERING) {
                                    if (playerStarted) {
                                        // 短暂缓冲，ExoPlayer 自身有缓冲指示器
                                    }
                                } else if (playbackState == Player.STATE_ENDED) {
                                    finish();
                                }
                            }

                            @Override
                            public void onPlayerError(PlaybackException error) {
                                loadingOverlay.setVisibility(View.GONE);
                                String errorMsg = error.getMessage();
                                if (errorMsg != null && errorMsg.contains("SMB")) {
                                    Toast.makeText(VideoPlayerActivity.this,
                                            "连接失败: " + errorMsg, Toast.LENGTH_LONG).show();
                                } else {
                                    Toast.makeText(VideoPlayerActivity.this,
                                            "播放出错: " + errorMsg, Toast.LENGTH_LONG).show();
                                }
                            }
                        });

                    } catch (Exception e) {
                        loadingOverlay.setVisibility(View.GONE);
                        Toast.makeText(VideoPlayerActivity.this,
                                "播放失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                handler.post(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(VideoPlayerActivity.this,
                            "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
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
