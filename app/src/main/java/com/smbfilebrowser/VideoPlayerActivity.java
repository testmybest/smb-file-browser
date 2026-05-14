package com.smbfilebrowser;

import android.content.Intent;
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
 * 视频/音频播放器 - 支持多种播放方式
 *
 * 1. 优先尝试调用第三方播放器（MX Player、VLC等）直接播放SMB
 * 2. 如果第三方播放器不可用，使用内置播放器边下载边播放
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
        
        // 尝试调用第三方播放器直接播放SMB
        if (tryExternalPlayer()) {
            return;
        }
        
        // 如果没有第三方播放器，使用内置播放器
        startStreamPlayback();
    }

    /**
     * 尝试调用第三方播放器直接播放SMB
     * 
     * MX Player、VLC 等播放器原生支持 SMB 协议
     */
    private boolean tryExternalPlayer() {
        try {
            // 构建 SMB URI（需要对路径进行编码）
            String encodedPath = remoteFilePath.replace(" ", "%20")
                    .replace("&", "%26")
                    .replace("[", "%5B")
                    .replace("]", "%5D")
                    .replace("#", "%23");
            
            String smbUri = "smb://" + smbManager.getCurrentHost() 
                    + "/" + smbManager.getCurrentShare() 
                    + "/" + encodedPath;
            
            Log.d(TAG, "Trying external player with: " + smbUri);

            // 尝试 MX Player（免费版）
            Intent mxIntent = new Intent(Intent.ACTION_VIEW);
            mxIntent.setDataAndType(Uri.parse(smbUri), getMimeType(fileName));
            mxIntent.setPackage("com.mxtech.videoplayer.ad");
            if (mxIntent.resolveActivity(getPackageManager()) != null) {
                Log.d(TAG, "Using MX Player");
                startActivity(mxIntent);
                finish();
                return true;
            }
            
            // 尝试 MX Player Pro
            Intent mxProIntent = new Intent(Intent.ACTION_VIEW);
            mxProIntent.setDataAndType(Uri.parse(smbUri), getMimeType(fileName));
            mxProIntent.setPackage("com.mxtech.videoplayer.pro");
            if (mxProIntent.resolveActivity(getPackageManager()) != null) {
                Log.d(TAG, "Using MX Player Pro");
                startActivity(mxProIntent);
                finish();
                return true;
            }
            
            // 尝试 VLC
            Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
            vlcIntent.setDataAndType(Uri.parse(smbUri), getMimeType(fileName));
            vlcIntent.setPackage("org.videolan.vlc");
            if (vlcIntent.resolveActivity(getPackageManager()) != null) {
                Log.d(TAG, "Using VLC");
                startActivity(vlcIntent);
                finish();
                return true;
            }
            
            // 尝试 nPlayer
            Intent nplayerIntent = new Intent(Intent.ACTION_VIEW);
            nplayerIntent.setDataAndType(Uri.parse(smbUri), getMimeType(fileName));
            nplayerIntent.setPackage("com.nplayer");
            if (nplayerIntent.resolveActivity(getPackageManager()) != null) {
                Log.d(TAG, "Using nPlayer");
                startActivity(nplayerIntent);
                finish();
                return true;
            }
            
            // 尝试 OPlayer
            Intent oplayerIntent = new Intent(Intent.ACTION_VIEW);
            oplayerIntent.setDataAndType(Uri.parse(smbUri), getMimeType(fileName));
            oplayerIntent.setPackage("com.olimsoft.android.oplayer");
            if (oplayerIntent.resolveActivity(getPackageManager()) != null) {
                Log.d(TAG, "Using OPlayer");
                startActivity(oplayerIntent);
                finish();
                return true;
            }
            
            // 尝试其他支持 SMB 的播放器
            Intent genericIntent = new Intent(Intent.ACTION_VIEW);
            genericIntent.setDataAndType(Uri.parse(smbUri), getMimeType(fileName));
            genericIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // 检查是否有应用可以处理这个 Intent
            if (genericIntent.resolveActivity(getPackageManager()) != null) {
                Log.d(TAG, "Using generic player");
                Intent chooser = Intent.createChooser(genericIntent, "选择播放器");
                startActivity(chooser);
                finish();
                return true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "External player failed: " + e.getMessage(), e);
        }
        
        Log.d(TAG, "No external player found");
        return false;
    }

    private String getMimeType(String fileName) {
        if (fileName == null) return "video/*";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".m3u8")) return "application/x-mpegURL";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        return "video/*";
    }

    /**
     * 边下载边播放（内置播放器）
     */
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
