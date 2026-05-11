package com.smbfilebrowser;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import jcifs.CIFSContext;
import jcifs.Configuration;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbFileOutputStream;

/**
 * SMB管理器 - 修复版
 * 
 * 核心修复：
 * 1. 使用 useSMB2Negotiation=true 解决协议协商超时
 * 2. 先尝试SMB2协商，失败再回退SMB1
 * 3. 支持匿名和Guest认证
 */
public class SMBManager {
    private static final String TAG = "SMBManager";
    private static SMBManager instance;
    
    private CIFSContext context;
    private String currentHost;
    private String currentShare;
    private String currentUsername;
    private String currentPassword;
    private boolean connected = false;

    private SMBManager() {
    }

    public static synchronized SMBManager getInstance() {
        if (instance == null) {
            instance = new SMBManager();
        }
        return instance;
    }

    /**
     * 创建基础配置
     */
    private CIFSContext createContext(boolean useSMB2Negotiation, String minVersion, String maxVersion) {
        try {
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.useSMB2Negotiation", String.valueOf(useSMB2Negotiation));
            props.setProperty("jcifs.smb.client.minVersion", minVersion);
            props.setProperty("jcifs.smb.client.maxVersion", maxVersion);
            props.setProperty("jcifs.smb.client.disablePlainTextPasswords", "false");
            props.setProperty("jcifs.smb.lmCompatibility", "3");
            props.setProperty("jcifs.smb.client.responseTimeout", "15000");
            props.setProperty("jcifs.smb.client.connTimeout", "10000");
            props.setProperty("jcifs.smb.client.soTimeout", "10000");
            
            Configuration config = new PropertyConfiguration(props);
            return new BaseContext(config);
        } catch (Exception e) {
            Log.e(TAG, "创建配置失败", e);
            return null;
        }
    }

    /**
     * 智能连接 - 按优先级尝试多种方式
     */
    public boolean connect(String host, String share, String username, String password) {
        Log.d(TAG, "========== 开始连接 ==========");
        Log.d(TAG, "目标: " + host + "/" + share);
        Log.d(TAG, "用户: " + (username == null || username.isEmpty() ? "(空)" : username));
        
        if (username == null) username = "";
        if (password == null) password = "";

        // ===== 方式1: SMB2协商 + 认证（最常用，Win10/11默认） =====
        if (!username.isEmpty()) {
            if (tryConnect(host, share, username, password, true, "SMB202", "SMB311")) {
                Log.d(TAG, ">>> 方式1成功: SMB2协商 + 认证");
                return true;
            }
        }

        // ===== 方式2: SMB2协商 + 匿名（Win10/11 无密码共享） =====
        if (username.isEmpty()) {
            if (tryAnonymous(host, share, true, "SMB202", "SMB311")) {
                Log.d(TAG, ">>> 方式2成功: SMB2协商 + 匿名");
                return true;
            }
        }

        // ===== 方式3: SMB2协商 + Guest =====
        if (tryGuest(host, share, true, "SMB202", "SMB311")) {
            Log.d(TAG, ">>> 方式3成功: SMB2协商 + Guest");
            return true;
        }

        // ===== 方式4: Legacy协商 + 认证（旧版Windows） =====
        if (!username.isEmpty()) {
            if (tryConnect(host, share, username, password, false, "SMB1", "SMB311")) {
                Log.d(TAG, ">>> 方式4成功: Legacy协商 + 认证");
                return true;
            }
        }

        // ===== 方式5: Legacy协商 + 匿名 =====
        if (username.isEmpty()) {
            if (tryAnonymous(host, share, false, "SMB1", "SMB311")) {
                Log.d(TAG, ">>> 方式5成功: Legacy协商 + 匿名");
                return true;
            }
        }

        // ===== 方式6: Legacy协商 + Guest =====
        if (tryGuest(host, share, false, "SMB1", "SMB311")) {
            Log.d(TAG, ">>> 方式6成功: Legacy协商 + Guest");
            return true;
        }

        // ===== 方式7: 强制SMB1 only =====
        if (tryConnect(host, share, username.isEmpty() ? "Guest" : username, password, false, "SMB1", "SMB1")) {
            Log.d(TAG, ">>> 方式7成功: 强制SMB1");
            return true;
        }

        Log.e(TAG, "========== 所有方式都失败 ==========");
        return false;
    }

    /**
     * 尝试认证连接
     */
    private boolean tryConnect(String host, String share, String username, String password,
                                boolean useSMB2Negotiation, String minVer, String maxVer) {
        try {
            Log.d(TAG, "尝试: SMB2Neg=" + useSMB2Negotiation + " ver=" + minVer + "-" + maxVer + " user=" + username);
            
            CIFSContext ctx = createContext(useSMB2Negotiation, minVer, maxVer);
            if (ctx == null) return false;

            CIFSContext authContext = ctx.withCredentials(
                new NtlmPasswordAuthenticator(null, username, password)
            );

            String smbPath = "smb://" + host + "/" + share + "/";
            SmbFile smbFile = new SmbFile(smbPath, authContext);
            
            // 用 exists() 而不是 listFiles() 来验证，更轻量
            if (smbFile.exists()) {
                saveConnection(host, share, username, password, ctx);
                Log.d(TAG, "连接验证成功");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.d(TAG, "失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 尝试匿名连接
     */
    private boolean tryAnonymous(String host, String share, boolean useSMB2Negotiation, String minVer, String maxVer) {
        try {
            Log.d(TAG, "尝试匿名: SMB2Neg=" + useSMB2Negotiation + " ver=" + minVer + "-" + maxVer);
            
            CIFSContext ctx = createContext(useSMB2Negotiation, minVer, maxVer);
            if (ctx == null) return false;

            CIFSContext anonContext = ctx.withAnonymousCredentials();

            String smbPath = "smb://" + host + "/" + share + "/";
            SmbFile smbFile = new SmbFile(smbPath, anonContext);
            
            if (smbFile.exists()) {
                saveConnection(host, share, "", "", ctx);
                Log.d(TAG, "匿名连接成功");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.d(TAG, "匿名失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 尝试Guest连接
     */
    private boolean tryGuest(String host, String share, boolean useSMB2Negotiation, String minVer, String maxVer) {
        try {
            Log.d(TAG, "尝试Guest: SMB2Neg=" + useSMB2Negotiation + " ver=" + minVer + "-" + maxVer);
            
            CIFSContext ctx = createContext(useSMB2Negotiation, minVer, maxVer);
            if (ctx == null) return false;

            CIFSContext guestContext = ctx.withCredentials(
                new NtlmPasswordAuthenticator(null, "Guest", "")
            );

            String smbPath = "smb://" + host + "/" + share + "/";
            SmbFile smbFile = new SmbFile(smbPath, guestContext);
            
            if (smbFile.exists()) {
                saveConnection(host, share, "Guest", "", ctx);
                Log.d(TAG, "Guest连接成功");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.d(TAG, "Guest失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 保存成功的连接
     */
    private void saveConnection(String host, String share, String username, String password, CIFSContext ctx) {
        currentHost = host;
        currentShare = share;
        currentUsername = username;
        currentPassword = password;
        context = ctx;
        connected = true;
    }

    /**
     * 获取认证上下文
     */
    private CIFSContext getAuthContext() {
        if (currentUsername == null || currentUsername.isEmpty()) {
            return context.withAnonymousCredentials();
        }
        return context.withCredentials(
            new NtlmPasswordAuthenticator(null, currentUsername, currentPassword)
        );
    }

    /**
     * 列出目录内容
     */
    public List<FileItem> listDirectory(String path) throws Exception {
        if (!connected) {
            throw new Exception("未连接到服务器");
        }

        List<FileItem> items = new ArrayList<>();
        String smbPath = "smb://" + currentHost + "/" + currentShare + "/";
        if (path != null && !path.isEmpty()) {
            smbPath += path;
        }

        CIFSContext authContext = getAuthContext();
        SmbFile directory = new SmbFile(smbPath, authContext);

        if (!directory.exists()) {
            throw new Exception("目录不存在");
        }
        if (!directory.isDirectory()) {
            throw new Exception("不是目录");
        }

        SmbFile[] files = directory.listFiles();
        if (files != null) {
            for (SmbFile file : files) {
                if (file.getName().startsWith(".")) continue;

                FileItem item = new FileItem();
                item.name = file.getName().replace("/", "");
                item.path = (path == null || path.isEmpty()) ?
                    file.getName() : path + "/" + file.getName();
                item.isDirectory = file.isDirectory();
                item.size = file.length();
                item.modified = file.getLastModified();
                items.add(item);
            }
        }

        // 排序：文件夹在前
        items.sort((a, b) -> {
            if (a.isDirectory != b.isDirectory) {
                return a.isDirectory ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });

        return items;
    }

    /**
     * 删除文件或文件夹
     */
    public void delete(String remotePath) throws Exception {
        if (!connected) {
            throw new Exception("未连接到服务器");
        }

        String smbPath = "smb://" + currentHost + "/" + currentShare + "/" + remotePath;
        CIFSContext authContext = getAuthContext();
        SmbFile smbFile = new SmbFile(smbPath, authContext);

        if (!smbFile.exists()) {
            throw new Exception("文件不存在");
        }

        if (smbFile.isDirectory()) {
            // 递归删除文件夹内容
            SmbFile[] children = smbFile.listFiles();
            if (children != null) {
                for (SmbFile child : children) {
                    String childPath = remotePath + "/" + child.getName().replace("/", "");
                    delete(childPath);
                }
            }
        }
        smbFile.delete();
    }

    public boolean isConnected() { return connected; }
    public void disconnect() { connected = false; }

    /**
     * 下载文件 - 从SMB下载到手机本地
     * @param remotePath SMB上的文件路径
     * @param localPath 手机上的保存路径
     * @param listener 下载进度监听
     */
    public void downloadFile(String remotePath, String localPath, DownloadListener listener) {
        new Thread(() -> {
            InputStream is = null;
            OutputStream os = null;
            try {
                String smbPath = "smb://" + currentHost + "/" + currentShare + "/" + remotePath;
                CIFSContext authContext = getAuthContext();
                SmbFile smbFile = new SmbFile(smbPath, authContext);

                if (!smbFile.exists()) {
                    listener.onFailed("文件不存在");
                    return;
                }
                if (smbFile.isDirectory()) {
                    listener.onFailed("不能下载文件夹");
                    return;
                }

                long fileSize = smbFile.length();

                // 创建本地目录
                File localFile = new File(localPath);
                File parentDir = localFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                is = new SmbFileInputStream(smbFile);
                os = new FileOutputStream(localFile);

                byte[] buffer = new byte[64 * 1024]; // 64KB
                int bytesRead;
                long totalRead = 0;
                long lastTime = System.currentTimeMillis();

                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    // 每秒更新一次进度
                    long now = System.currentTimeMillis();
                    if (now - lastTime >= 1000) {
                        int percent = (int) ((totalRead * 100) / fileSize);
                        long speed = (totalRead * 1000) / (now - lastTime);
                        listener.onProgress(percent, totalRead, fileSize, speed);
                        lastTime = now;
                    }
                }

                os.flush();
                listener.onComplete(localPath);

            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                listener.onFailed("下载失败: " + e.getMessage());
            } finally {
                try {
                    if (is != null) is.close();
                    if (os != null) os.close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * 上传文件 - 从手机上传到SMB
     * @param localPath 手机上的文件路径
     * @param remotePath SMB上的保存路径
     * @param listener 上传进度监听
     */
    public void uploadFile(String localPath, String remotePath, UploadListener listener) {
        new Thread(() -> {
            InputStream is = null;
            OutputStream os = null;
            try {
                File localFile = new File(localPath);
                if (!localFile.exists()) {
                    listener.onFailed("本地文件不存在");
                    return;
                }

                long fileSize = localFile.length();

                String smbPath = "smb://" + currentHost + "/" + currentShare + "/" + remotePath;
                CIFSContext authContext = getAuthContext();

                // 确保远程目录存在
                SmbFile smbFile = new SmbFile(smbPath, authContext);
                SmbFile parentSmb = new SmbFile(smbFile.getParent(), authContext);
                if (!parentSmb.exists()) {
                    parentSmb.mkdirs();
                }

                is = new FileInputStream(localFile);
                os = new SmbFileOutputStream(smbFile);

                byte[] buffer = new byte[64 * 1024]; // 64KB
                int bytesRead;
                long totalRead = 0;
                long lastTime = System.currentTimeMillis();

                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    long now = System.currentTimeMillis();
                    if (now - lastTime >= 1000) {
                        int percent = (int) ((totalRead * 100) / fileSize);
                        long speed = (totalRead * 1000) / (now - lastTime);
                        listener.onProgress(percent, totalRead, fileSize, speed);
                        lastTime = now;
                    }
                }

                os.flush();
                listener.onComplete(remotePath);

            } catch (Exception e) {
                Log.e(TAG, "上传失败", e);
                listener.onFailed("上传失败: " + e.getMessage());
            } finally {
                try {
                    if (is != null) is.close();
                    if (os != null) os.close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * 获取默认下载目录
     */
    public String getDownloadDir() {
        return android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/SMB";
    }

    /**
     * 获取流式播放缓存目录
     */
    public String getStreamCacheDir(android.content.Context context) {
        File cacheDir = new File(context.getCacheDir(), "smb_stream");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir.getAbsolutePath();
    }

    /**
     * 流式下载文件到缓存 - 用于视频/音频边下边播
     * 先快速下载一部分数据让播放器可以开始播放，然后后台继续下载
     * @param remotePath SMB上的文件路径
     * @param context Android Context
     * @param listener 流式下载监听
     */
    public void streamFile(String remotePath, android.content.Context context, StreamListener listener) {
        new Thread(() -> {
            InputStream is = null;
            OutputStream os = null;
            String localPath = null;
            try {
                String smbPath = "smb://" + currentHost + "/" + currentShare + "/" + remotePath;
                CIFSContext authContext = getAuthContext();
                SmbFile smbFile = new SmbFile(smbPath, authContext);

                if (!smbFile.exists()) {
                    listener.onFailed("文件不存在");
                    return;
                }

                long fileSize = smbFile.length();

                // 创建缓存文件
                String cacheDir = getStreamCacheDir(context);
                String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
                // 去掉文件名中的特殊字符
                fileName = fileName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
                localPath = cacheDir + "/" + fileName;
                File localFile = new File(localPath);

                is = new SmbFileInputStream(smbFile);
                os = new FileOutputStream(localFile);

                byte[] buffer = new byte[128 * 1024]; // 128KB 大缓冲区，加速下载
                int bytesRead;
                long totalRead = 0;
                long lastTime = System.currentTimeMillis();
                boolean readyNotified = false;

                // 先下载 256KB 或文件的前 2%，让播放器可以秒开
                // 256KB 足够解码器读取 MP4/MKV 的文件头和前几帧
                long preBufferSize = Math.min(256 * 1024, fileSize / 50);
                if (preBufferSize < 64 * 1024) preBufferSize = 64 * 1024; // 最小 64KB

                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    // 通知播放器可以开始了
                    if (!readyNotified && totalRead >= preBufferSize) {
                        readyNotified = true;
                        listener.onReady(localPath, fileSize);
                    }

                    // 每秒更新进度
                    long now = System.currentTimeMillis();
                    if (now - lastTime >= 1000) {
                        int percent = (int) ((totalRead * 100) / fileSize);
                        long speed = (totalRead * 1000) / (now - lastTime);
                        listener.onProgress(percent, totalRead, fileSize, speed);
                        lastTime = now;
                    }
                }

                os.flush();

                // 如果文件太小，还没通知 ready 就下载完了
                if (!readyNotified) {
                    listener.onReady(localPath, fileSize);
                }

                listener.onComplete(localPath);

            } catch (Exception e) {
                Log.e(TAG, "流式下载失败", e);
                listener.onFailed("流式下载失败: " + e.getMessage());
            } finally {
                try {
                    if (is != null) is.close();
                    if (os != null) os.close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * 清理流式播放缓存
     */
    public void clearStreamCache(android.content.Context context) {
        File cacheDir = new File(context.getCacheDir(), "smb_stream");
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    /**
     * 流式播放监听
     */
    public interface StreamListener {
        /** 已下载足够数据，可以开始播放了 */
        void onReady(String localPath, long totalSize);
        /** 下载进度更新 */
        void onProgress(int percent, long downloaded, long total, long speed);
        /** 下载完成 */
        void onComplete(String localPath);
        /** 下载失败 */
        void onFailed(String error);
    }

    /**
     * 下载进度监听
     */
    public interface DownloadListener {
        void onProgress(int percent, long downloaded, long total, long speed);
        void onComplete(String localPath);
        void onFailed(String error);
    }

    /**
     * 上传进度监听
     */
    public interface UploadListener {
        void onProgress(int percent, long uploaded, long total, long speed);
        void onComplete(String remotePath);
        void onFailed(String error);
    }

    public static class FileItem {
        public String name;
        public String path;
        public boolean isDirectory;
        public long size;
        public long modified;
    }
}
