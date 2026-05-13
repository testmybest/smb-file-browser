package com.smbfilebrowser;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

/**
 * 视频/音频播放器 - 真正的流式播放（秒开）
 *
 * 原理：使用 SmbDataSource 让 ExoPlayer 直接从 SMB 网络流读取数据
 * 不需要等待下载完成，点击后立即开始播放
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayer";

    private PlayerView playerView;
    private LinearLayout loadingOverlay;
    private TextView tvLoadingStatus;
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
        tvLoadingStatus = findViewById(R.id.tv_loading_status);

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

        Log.d(TAG, "Playing: " + remoteFilePath);
        startDirectPlayback();
    }

    /**
     * 直接流式播放 - 秒开，无需等待下载
     */
    private void startDirectPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");

        new Thread(() -> {
            try {
                // 构建 SMB URI
                StringBuilder uriBuilder = new StringBuilder();
                uriBuilder.append("smb://");
                uriBuilder.append(smbManager.getCurrentHost());
                uriBuilder.append("/");
                uriBuilder.append(smbManager.getCurrentShare());
                
                // 处理文件路径
                String filePath = remoteFilePath;
                if (!filePath.startsWith("/")) {
                    filePath = "/" + filePath;
                }
                filePath = filePath.replace("\\", "/");
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
                                Log.d(TAG, "State: " + playbackState);
                                if (playbackState == Player.STATE_READY) {
                                    loadingOverlay.setVisibility(View.GONE);
                                    playerStarted = true;
                                    Log.d(TAG, "Playback started");
                                } else if (playbackState == Player.STATE_BUFFERING) {
                                    if (playerStarted) {
                                        tvLoadingStatus.setText("缓冲中...");
                                        loadingOverlay.setVisibility(View.VISIBLE);
                                    }
                                } else if (playbackState == Player.STATE_ENDED) {
                                    finish();
                                }
                            }

                            @Override
                            public void onPlayerError(PlaybackException error) {
                                Log.e(TAG, "Error: " + error.getMessage(), error);
                                loadingOverlay.setVisibility(View.GONE);
                                Toast.makeText(VideoPlayerActivity.this,
                                        "播放失败: " + error.getMessage(), Toast.LENGTH_LONG).show();
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
}
