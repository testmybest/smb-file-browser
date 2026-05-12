package com.smbfilebrowser;

import android.net.Uri;
import android.util.Log;

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
 */
@OptIn(markerClass = UnstableApi.class)
public class SmbDataSource extends BaseDataSource {

    private static final String TAG = "SmbDataSource";

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
    private long fileSize;
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
            if (filePath == null) {
                throw new IOException("URI path is null");
            }
            // 去掉开头的斜杠
            if (filePath.startsWith("/")) {
                filePath = filePath.substring(1);
            }

            // 构建 SMB 路径
            String smbPath = "smb://" + host + "/" + share + "/" + filePath;
            Log.d(TAG, "Opening SMB path: " + smbPath);

            // 创建认证上下文
            CIFSContext authContext;
            if (username != null && !username.isEmpty()) {
                authContext = baseContext.withCredentials(
                        new NtlmPasswordAuthenticator(null, username, password));
                Log.d(TAG, "Using username authentication: " + username);
            } else {
                authContext = baseContext.withCredentials(
                        new NtlmPasswordAuthenticator(null, "Guest", ""));
                Log.d(TAG, "Using Guest authentication");
            }

            // 打开 SMB 文件
            smbFile = new SmbFile(smbPath, authContext);
            
            if (!smbFile.exists()) {
                throw new IOException("SMB文件不存在: " + smbPath);
            }

            fileSize = smbFile.length();
            Log.d(TAG, "File size: " + fileSize);

            // 创建输入流
            inputStream = new SmbFileInputStream(smbFile);

            // 支持 seek（拖动进度条）
            long skipBytes = dataSpec.position;
            if (skipBytes > 0) {
                if (skipBytes < fileSize) {
                    long skipped = inputStream.skip(skipBytes);
                    Log.d(TAG, "Skipped " + skipped + " bytes");
                } else {
                    throw new IOException("Invalid seek position: " + skipBytes + " >= fileSize: " + fileSize);
                }
            }

            // 计算剩余字节数
            if (dataSpec.length >= 0) {
                bytesRemaining = Math.min(dataSpec.length, fileSize - skipBytes);
            } else {
                bytesRemaining = fileSize - skipBytes;
            }

            Log.d(TAG, "Bytes remaining: " + bytesRemaining + ", position: " + skipBytes);

            opened = true;
            transferStarted(dataSpec);

            return bytesRemaining;

        } catch (SmbException e) {
            Log.e(TAG, "SMB error: " + e.getMessage(), e);
            throw new IOException("SMB连接失败: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
            throw new IOException("打开文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }

        if (bytesRemaining == 0) {
            return -1; // End of stream
        }

        if (inputStream == null) {
            throw new IOException("InputStream is null");
        }

        int bytesToRead = (int) Math.min(length, bytesRemaining);
        int bytesRead;
        
        try {
            bytesRead = inputStream.read(buffer, offset, bytesToRead);
        } catch (SmbException e) {
            Log.e(TAG, "SMB read error: " + e.getMessage(), e);
            throw new IOException("SMB读取失败: " + e.getMessage(), e);
        }

        if (bytesRead == -1) {
            // 流结束，但 bytesRemaining 还没归零（可能是网络问题）
            if (bytesRemaining > 0) {
                Log.w(TAG, "Unexpected end of stream, bytesRemaining: " + bytesRemaining);
            }
            return -1;
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
        } catch (Exception e) {
            Log.e(TAG, "Error closing stream: " + e.getMessage(), e);
        } finally {
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    /**
     * DataSource 工厂类
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
