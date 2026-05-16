package com.smbfilebrowser;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fi.iki.elonen.NanoHTTPD;
import jcifs.CIFSContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

/**
 * 本地HTTP代理服务器
 * 
 * 在本地启动HTTP服务器，将SMB文件通过HTTP协议暴露给播放器
 * 播放器访问 http://127.0.0.1:23333/stream/... 即可播放SMB视频
 * 
 * 参考：SMB-Steamer (https://github.com/CzBiX/SMB-Steamer)
 */
public class SmbHttpServer extends NanoHTTPD {
    private static final String TAG = "SmbHttpServer";
    public static final int PORT = 23333;
    public static final String URI_PREFIX = "/stream/";

    private static SmbHttpServer instance;
    private CIFSContext smbContext;

    private SmbHttpServer(CIFSContext smbContext) {
        super("127.0.0.1", PORT);
        this.smbContext = smbContext;
        setAsyncRunner(new ThreadPoolRunner());
    }

    public static synchronized SmbHttpServer getInstance(CIFSContext smbContext) {
        if (instance == null) {
            instance = new SmbHttpServer(smbContext);
        }
        return instance;
    }

    public static synchronized void stopInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    public static boolean isRunning() {
        return instance != null && instance.isAlive();
    }

    /**
     * 启动服务器
     */
    public void startServer() throws IOException {
        if (!isAlive()) {
            start();
            Log.d(TAG, "HTTP server started on port " + PORT);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String uri = session.getUri();
        Log.d(TAG, "Request: " + method + " " + uri);

        if (method != Method.GET || !uri.startsWith(URI_PREFIX)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
        }

        // 提取SMB路径：/stream/host/share/path -> host/share/path
        String smbPath = uri.substring(URI_PREFIX.length());
        return handleStream(smbPath, session.getHeaders());
    }

    private Response handleStream(String smbPath, Map<String, String> headers) {
        SmbFile smbFile;
        try {
            String fullPath = "smb://" + smbPath;
            Log.d(TAG, "Opening: " + fullPath);
            smbFile = new SmbFile(fullPath, smbContext);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create SmbFile: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad request: " + e.getMessage());
        }

        try {
            if (smbFile.isFile()) {
                String mime = getMimeType(smbPath);
                return serveFile(headers, smbFile, mime);
            } else {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Not a file");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error serving file: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }

    /**
     * 处理文件请求，支持Range（视频拖拽）
     */
    private Response serveFile(Map<String, String> headers, SmbFile file, String mime) throws IOException {
        InputStream fis = null;
        boolean success = false;

        try {
            // 计算ETag
            String etag = Integer.toHexString(
                    (file.getCanonicalPath() + file.lastModified() + file.length()).hashCode());

            // 解析Range头
            long startFrom = 0;
            long endAt = -1;
            String range = headers.get("range");
            if (range != null && range.startsWith("bytes=")) {
                range = range.substring("bytes=".length());
                int minus = range.indexOf('-');
                try {
                    if (minus > 0) {
                        startFrom = Long.parseLong(range.substring(0, minus));
                        if (minus + 1 < range.length()) {
                            endAt = Long.parseLong(range.substring(minus + 1));
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            // 条件请求处理
            String ifNoneMatch = headers.get("if-none-match");
            boolean notMatch = ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.equals(etag));

            long fileLen = file.length();
            fis = file.getInputStream();

            Response res;

            if (range != null && startFrom >= 0 && startFrom < fileLen) {
                // Range请求 - 视频拖拽
                if (endAt < 0) {
                    endAt = fileLen - 1;
                }
                long newLen = endAt - startFrom + 1;
                if (newLen < 0) newLen = 0;

                // 跳转到请求位置
                long skipped = 0;
                while (skipped < startFrom) {
                    long n = fis.skip(startFrom - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }

                res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, newLen);
                res.addHeader("Accept-Ranges", "bytes");
                res.addHeader("Content-Length", "" + newLen);
                res.addHeader("Content-Range", "bytes " + startFrom + "-" + endAt + "/" + fileLen);
                res.addHeader("ETag", etag);
                Log.d(TAG, "Range: " + startFrom + "-" + endAt + "/" + fileLen);

            } else if (range != null && startFrom >= fileLen) {
                // Range超出范围
                res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
                res.addHeader("Content-Range", "bytes */" + fileLen);

            } else if (notMatch) {
                // 未修改
                res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "");
                res.addHeader("ETag", etag);

            } else {
                // 完整文件请求
                res = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLen);
                res.addHeader("Content-Length", Long.toString(fileLen));
                res.addHeader("Accept-Ranges", "bytes");
                res.addHeader("ETag", etag);
                Log.d(TAG, "Full file: " + fileLen + " bytes");
            }

            success = true;
            return res;

        } finally {
            if (!success && fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    /**
     * 线程池执行器
     */
    private static class ThreadPoolRunner implements AsyncRunner {
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<ClientHandler> running = Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void closeAll() {
            executor.shutdown();
            for (ClientHandler handler : new java.util.ArrayList<>(running)) {
                handler.close();
            }
        }

        @Override
        public void closed(ClientHandler clientHandler) {
            running.remove(clientHandler);
        }

        @Override
        public void exec(ClientHandler clientHandler) {
            running.add(clientHandler);
            executor.submit(clientHandler);
        }
    }
}
