package com.smbfilebrowser;

import android.net.Uri;
import android.os.Bundle;
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
import androidx.media3.ui.PlayerView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 视频/音频播放器 - 通过本地HTTP代理实现秒开
 */
@OptIn(markerClass = UnstableApi.class)
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
            getSupportActionBar().setTitle(fileName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (remoteFilePath == null || remoteFilePath.isEmpty()) {
            Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Playing: " + remoteFilePath);
        startHttpProxyPlayback();
    }

    /**
     * 通过本地HTTP代理播放（秒开）
     */
    private void startHttpProxyPlayback() {
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("正在启动播放服务...");

        new Thread(() -> {
            try {
                // 启动HTTP代理服务器
                SmbHttpServer server = SmbHttpServer.getInstance(smbManager.getBaseContext());
                server.startServer();
                Log.d(TAG, "HTTP server is running");

                // 构建SMB流路径
                // 格式: host/share/path/to/file.mp4
                String streamPath = smbManager.getCurrentHost() + "/"
                        + smbManager.getCurrentShare() + "/"
                        + remoteFilePath;

                Log.d(TAG, "Stream path: " + streamPath);

                // 对路径中的每一部分分别编码（处理中文、空格等）
                String[] parts = streamPath.split("/");
                StringBuilder encodedPath = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) encodedPath.append("/");
                    encodedPath.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8));
                }

                String httpUrl = "http://127.0.0.1:" + SmbHttpServer.PORT
                        + SmbHttpServer.URI_PREFIX + encodedPath;

                Log.d(TAG, "HTTP URL: " + httpUrl);

                runOnUiThread(() -> {
                    tvLoadingStatus.setText("正在播放...");
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
                    Log.d(TAG, "State: " + playbackState);
                    if (playbackState == Player.STATE_READY) {
                        loadingOverlay.setVisibility(View.GONE);
                        Log.d(TAG, "Playback started!");
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
    }
}
