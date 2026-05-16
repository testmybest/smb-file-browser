package com.smbfilebrowser;

import android.net.Uri;
import android.os.Bundle;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 视频/音频播放器 - 通过本地HTTP代理实现秒开
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayer";

    private PlayerView playerView;
    private LinearLayout loadingOverlay;
    private ProgressBar progressBar;
    private TextView tvLoadingStatus;
    private ExoPlayer player;
    private SMBManager smbManager;

    private String remoteFilePath;
    private String fileName;
    private boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        playerView = findViewById(R.id.player_view);
        loadingOverlay = findViewById(R.id.loading_overlay);
        progressBar = findViewById(R.id.progress_bar);
        tvLoadingStatus = findViewById(R.id.tv_loading_status);

        smbManager = SMBManager.getInstance();

        remoteFilePath = getIntent().getStringExtra("file_path");
        fileName = getIntent().getStringExtra("file_name");

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(fileName != null ? fileName : "播放");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (remoteFilePath == null || remoteFilePath.isEmpty()) {
            Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "onCreate - Playing: " + remoteFilePath);
        startHttpProxyPlayback();
    }

    /**
     * 通过本地HTTP代理播放（秒开）
     */
    private void startHttpProxyPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在启动播放服务...");

        // 检查 SMB 连接状态
        if (!smbManager.isConnected()) {
            Log.e(TAG, "SMB not connected");
            Toast.makeText(this, "未连接到SMB服务器", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        new Thread(() -> {
            try {
                // 启动HTTP代理服务器
                SmbHttpServer server = SmbHttpServer.getInstance(smbManager.getBaseContext());
                server.startServer();
                Log.d(TAG, "HTTP server is running on port " + SmbHttpServer.PORT);

                // 构建SMB流路径: host/share/path/to/file.mp4
                String host = smbManager.getCurrentHost();
                String share = smbManager.getCurrentShare();
                
                if (host == null || share == null) {
                    throw new Exception("SMB连接信息不完整");
                }

                String streamPath = host + "/" + share + "/" + remoteFilePath;
                Log.d(TAG, "Stream path: " + streamPath);

                // 对路径中的每一部分分别编码（处理中文、空格等），保留斜杠
                String[] parts = streamPath.split("/");
                StringBuilder encodedPath = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) encodedPath.append("/");
                    encodedPath.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8.toString()));
                }

                String httpUrl = "http://127.0.0.1:" + SmbHttpServer.PORT
                        + SmbHttpServer.URI_PREFIX + encodedPath;

                Log.d(TAG, "HTTP URL: " + httpUrl);

                runOnUiThread(() -> {
                    tvLoadingStatus.setText("正在加载视频...");
                    startPlayer(httpUrl);
                });

            } catch (Exception e) {
                Log.e(TAG, "HTTP proxy failed: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startPlayer(String httpUrl) {
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(httpUrl));
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Log.d(TAG, "Playback state: " + playbackState);
                    switch (playbackState) {
                        case Player.STATE_READY:
                            loadingOverlay.setVisibility(View.GONE);
                            isPlaying = true;
                            Log.d(TAG, "Playback started successfully!");
                            break;
                        case Player.STATE_ENDED:
                            Log.d(TAG, "Playback ended");
                            // 播放结束不自动关闭，让用户可以选择重播
                            isPlaying = false;
                            break;
                        case Player.STATE_BUFFERING:
                            Log.d(TAG, "Buffering...");
                            break;
                        case Player.STATE_IDLE:
                            Log.d(TAG, "Player idle");
                            break;
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Player error: " + error.getMessage(), error);
                    isPlaying = false;
                    runOnUiThread(() -> {
                        loadingOverlay.setVisibility(View.GONE);
                        String errorMsg = "播放出错";
                        if (error.getMessage() != null) {
                            errorMsg += ": " + error.getMessage();
                        }
                        Toast.makeText(VideoPlayerActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                        // 出错不自动关闭页面，让用户可以返回
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "startPlayer error: " + e.getMessage(), e);
            loadingOverlay.setVisibility(View.GONE);
            Toast.makeText(this, "播放初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        Log.d(TAG, "onSupportNavigateUp - finishing activity");
        finish();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        if (player != null && isPlaying) {
            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        if (player != null && isPlaying) {
            player.play();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy - releasing player");
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
