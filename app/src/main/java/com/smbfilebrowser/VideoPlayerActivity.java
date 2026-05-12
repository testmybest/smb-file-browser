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

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Locale;

/**
 * 视频/音频播放器 - 直接从 SMB 流播放
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayer";

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

        Log.d(TAG, "Playing file: " + remoteFilePath);
        Log.d(TAG, "Host: " + smbManager.getCurrentHost());
        Log.d(TAG, "Share: " + smbManager.getCurrentShare());

        startDirectPlayback();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startDirectPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");

        new Thread(() -> {
            try {
                // 构建正确的 SMB URI
                // 格式: smb://host/share/path/to/file.mp4
                StringBuilder uriBuilder = new StringBuilder();
                uriBuilder.append("smb://");
                uriBuilder.append(smbManager.getCurrentHost());
                uriBuilder.append("/");
                uriBuilder.append(smbManager.getCurrentShare());
                
                // 处理文件路径，确保以 / 开头但不含双斜杠
                String filePath = remoteFilePath;
                if (!filePath.startsWith("/")) {
                    filePath = "/" + filePath;
                }
                // 将路径中的反斜杠替换为正斜杠
                filePath = filePath.replace("\\", "/");
                // 移除双斜杠
                while (filePath.contains("//")) {
                    filePath = filePath.replace("//", "/");
                }
                uriBuilder.append(filePath);
                
                String smbUri = uriBuilder.toString();
                Log.d(TAG, "SMB URI: " + smbUri);

                // 创建 SMB DataSource 工厂
                SmbDataSource.Factory dataSourceFactory = new SmbDataSource.Factory(
                        smbManager.getCurrentHost(),
                        smbManager.getCurrentShare(),
                        smbManager.getCurrentUsername(),
                        smbManager.getCurrentPassword(),
                        smbManager.getBaseContext()
                );

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
                                    Log.d(TAG, "Player ready, playback started");
                                } else if (playbackState == Player.STATE_BUFFERING) {
                                    Log.d(TAG, "Player buffering...");
                                } else if (playbackState == Player.STATE_ENDED) {
                                    Log.d(TAG, "Playback ended");
                                    finish();
                                }
                            }

                            @Override
                            public void onPlayerError(PlaybackException error) {
                                Log.e(TAG, "Player error: " + error.getMessage(), error);
                                loadingOverlay.setVisibility(View.GONE);
                                
                                String errorMsg = error.getMessage();
                                if (errorMsg == null) errorMsg = "未知错误";
                                
                                Toast.makeText(VideoPlayerActivity.this,
                                        "播放失败: " + errorMsg, Toast.LENGTH_LONG).show();
                            }
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Setup error: " + e.getMessage(), e);
                        loadingOverlay.setVisibility(View.GONE);
                        Toast.makeText(VideoPlayerActivity.this,
                                "播放初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage(), e);
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
