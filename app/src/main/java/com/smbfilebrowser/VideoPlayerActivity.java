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

        Log.d(TAG, "Playing: " + remoteFilePath);
        startStreamPlayback();
    }

    private void startStreamPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在连接...");
        tvLoadingDetail.setText("");

        smbManager.streamFile(remoteFilePath, this, new SMBManager.StreamListener() {
            @Override
            public void onReady(String localPath, long totalSize) {
                Log.d(TAG, "onReady: " + localPath + ", size: " + totalSize);
                handler.post(() -> {
                    tvLoadingStatus.setText("正在播放...");
                    startPlayer(localPath);
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

    private void startPlayer(String localPath) {
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
                        if (!playerStarted) {
                            loadingOverlay.setVisibility(View.GONE);
                            playerStarted = true;
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        finish();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Error: " + error.getMessage(), error);
                    if (!playerStarted) {
                        // 文件头可能还没下载完，等待后重试
                        handler.postDelayed(() -> {
                            if (!isFinishing()) {
                                player.release();
                                player = null;
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
