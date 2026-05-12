package com.smbfilebrowser;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

import jcifs.CIFSContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

/**
 * ExoPlayer DataSource - 直接从 SMB 共享读取视频流
 *
 * 原理：实现 ExoPlayer 的 DataSource 接口，让播放器直接从 SMB 网络流读取数据
 * 就像播放 HTTP 视频一样，不需要先下载到本地，真正做到秒开秒播
 */
@OptIn(markerClass = UnstableApi.class)
public class SmbDataSource extends BaseDataSource {

    private final String host;
    private final String share;
    private final String username;
    private final String password;
    private final CIFSContext baseContext;

    @Nullable
    private SmbFileInputStream inputStream;
    @Nullable
    private SmbFile smbFile;
    private long bytesRemaining;
    private boolean opened = false;

    public SmbDataSource(String host, String share, String username, String password, CIFSContext baseContext) {
        super(/* isNetwork= */ true);
        this.host = host;
        this.share = share;
        this.username = username;
        this.password = password;
        this.baseContext = baseContext;
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);

        try {
            // 从 URI 的 path 部分提取文件路径
            String filePath = dataSpec.uri.getPath();
            // 去掉开头的斜杠
            if (filePath.startsWith("/")) {
                filePath = filePath.substring(1);
            }

            String smbPath = "smb://" + host + "/" + share + "/" + filePath;

            CIFSContext authContext;
            if (username != null && !username.isEmpty()) {
                authContext = baseContext.withCredentials(
                        new NtlmPasswordAuthenticator(null, username, password));
            } else {
                authContext = baseContext.withCredentials(
                        new NtlmPasswordAuthenticator(null, "Guest", ""));
            }

            smbFile = new SmbFile(smbPath, authContext);
            if (!smbFile.exists()) {
                throw new IOException("SMB文件不存在: " + smbPath);
            }

            long fileSize = smbFile.length();
            inputStream = new SmbFileInputStream(smbFile);

            // 支持断点续传/seek（拖动进度条）
            if (dataSpec.position > 0 && dataSpec.position < fileSize) {
                inputStream.skip(dataSpec.position);
            }

            long resolvedPosition = dataSpec.position;
            bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                    ? fileSize - resolvedPosition
                    : Math.min(dataSpec.length, fileSize - resolvedPosition);

            opened = true;
            transferStarted(dataSpec);

            return bytesRemaining;

        } catch (SmbException e) {
            throw new IOException("SMB连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (bytesRemaining == 0) {
            return -1; // End of stream
        }

        int bytesRead;
        try {
            bytesRead = inputStream.read(buffer, offset, (int) Math.min(length, bytesRemaining));
        } catch (SmbException e) {
            throw new IOException("SMB读取失败: " + e.getMessage(), e);
        }

        if (bytesRead == -1) {
            return -1; // End of stream
        }

        bytesRemaining -= bytesRead;
        bytesTransferred(bytesRead);
        return bytesRead;
    }

    @Override
    @Nullable
    public Uri getUri() {
        return smbFile != null ? Uri.parse(smbFile.getPath()) : null;
    }

    @Override
    public void close() throws IOException {
        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
        } finally {
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    /**
     * DataSource 工厂类 — 为 ExoPlayer 创建 SmbDataSource 实例
     */
    public static class Factory implements DataSource.Factory {

        private final String host;
        private final String share;
        private final String username;
        private final String password;
        private final CIFSContext baseContext;

        public Factory(String host, String share, String username, String password, CIFSContext baseContext) {
            this.host = host;
            this.share = share;
            this.username = username;
            this.password = password;
            this.baseContext = baseContext;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new SmbDataSource(host, share, username, password, baseContext);
        }
    }
}
