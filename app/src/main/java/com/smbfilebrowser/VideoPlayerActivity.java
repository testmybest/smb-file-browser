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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;

/**
 * 视频/音频播放器 - 支持真正的流式播放
 *
 * 优先使用 SmbDataSource 直接流式播放（秒开）
 * 如果失败，回退到边下载边播放模式
 */
@OptIn(markerClass = UnstableApi.class)
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
    private String fileName;
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
        fileName = getIntent().getStringExtra("file_name");

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
        
        // 优先尝试直接流式播放（秒开）
        startStreamingPlayback();
    }

    /**
     * 使用 SmbDataSource 直接流式播放（秒开）
     */
    private void startStreamingPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");
        tvLoadingDetail.setText("");

        try {
            // 创建 SmbDataSource 工厂
            SmbDataSource.Factory dataSourceFactory = new SmbDataSource.Factory(
                    smbManager.getCurrentHost(),
                    smbManager.getCurrentShare(),
                    smbManager.getCurrentUsername(),
                    smbManager.getCurrentPassword(),
                    smbManager.getBaseContext()
            );

            // 创建 ExoPlayer，使用自定义 DataSource
            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                    .build();
            
            playerView.setPlayer(player);

            // 构建 SMB URI
            String smbUri = "smb://" + smbManager.getCurrentHost() 
                    + "/" + smbManager.getCurrentShare() 
                    + "/" + remoteFilePath;
            
            Log.d(TAG, "Streaming from: " + smbUri);

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(smbUri));
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Log.d(TAG, "State: " + playbackState);
                    if (playbackState == Player.STATE_READY) {
                        loadingOverlay.setVisibility(View.GONE);
                        playerStarted = true;
                        Log.d(TAG, "Playback started successfully");
                    } else if (playbackState == Player.STATE_ENDED) {
                        finish();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Player error: " + error.getMessage(), error);
                    
                    // 如果直接流式播放失败，回退到边下载边播放
                    if (!playerStarted) {
                        Log.d(TAG, "Streaming failed, fallback to download mode");
                        player.release();
                        player = null;
                        handler.post(() -> {
                            Toast.makeText(VideoPlayerActivity.this, 
                                    "流式播放失败，切换到下载模式", Toast.LENGTH_SHORT).show();
                            startDownloadPlayback();
                        });
                    } else {
                        loadingOverlay.setVisibility(View.GONE);
                        Toast.makeText(VideoPlayerActivity.this,
                                "播放出错: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "startStreamingPlayback error: " + e.getMessage(), e);
            // 如果初始化失败，回退到边下载边播放
            handler.post(() -> {
                Toast.makeText(this, "流式播放初始化失败，切换到下载模式", Toast.LENGTH_SHORT).show();
                startDownloadPlayback();
            });
        }
    }

    /**
     * 边下载边播放（备用方案）
     */
    private void startDownloadPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");
        tvLoadingDetail.setText("");

        smbManager.streamFile(remoteFilePath, this, new SMBManager.StreamListener() {
            @Override
            public void onReady(String localPath, long totalSize) {
                Log.d(TAG, "onReady: " + localPath + ", size: " + totalSize);
                handler.post(() -> {
                    tvLoadingStatus.setText("正在播放...");
                    startPlayerWithFile(localPath);
                });
            }

            @Override
            public void onProgress(int percent, long downloaded, long total, long speed) {
                handler.post(() -> {
                    if (!playerStarted) {
                        tvLoadingStatus.setText("缓冲中 " + percent + "%");
                        tvLoadingDetail.setText(formatSize(downloaded) + " / " + formatSize(total)
                                + "  " + formatSize(speed) + "/s");
                    }
                    if (playerStarted && getSupportActionBar() != null) {
                        getSupportActionBar().setSubtitle("缓存 " + percent + "%");
                    }
                });
            }

            @Override
            public void onComplete(String localPath) {
                Log.d(TAG, "onComplete: " + localPath);
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

    private void startPlayerWithFile(String localPath) {
        try {
            File file = new File(localPath);
            if (!file.exists()) {
                Toast.makeText(this, "缓存文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }

            if (player != null) {
                player.release();
                player = null;
            }

            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            Uri uri = Uri.fromFile(file);
            MediaItem mediaItem = MediaItem.fromUri(uri);

            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Log.d(TAG, "State: " + playbackState);
                    if (playbackState == Player.STATE_READY) {
                        loadingOverlay.setVisibility(View.GONE);
                        playerStarted = true;
                    } else if (playbackState == Player.STATE_ENDED) {
                        finish();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Error: " + error.getMessage(), error);
                    loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(VideoPlayerActivity.this,
                            "播放出错: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

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
