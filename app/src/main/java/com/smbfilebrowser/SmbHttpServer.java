package com.smbfilebrowser;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fi.iki.elonen.NanoHTTPD;
import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

/**
 * 本地HTTP代理服务器 - 将SMB文件通过HTTP暴露给播放器
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

        String smbPath = uri.substring(URI_PREFIX.length());
        // URL解码处理中文
        try {
            smbPath = URLDecoder.decode(smbPath, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "URL decode failed: " + e.getMessage());
        }
        Log.d(TAG, "SMB path: " + smbPath);

        return handleStream(smbPath, session.getHeaders());
    }

    private Response handleStream(String smbPath, Map<String, String> headers) {
        try {
            String fullPath = "smb://" + smbPath;
            Log.d(TAG, "Opening: " + fullPath);
            SmbFile smbFile = new SmbFile(fullPath, smbContext);

            if (smbFile.isFile()) {
                String mime = getMimeType(smbPath);
                return serveFile(headers, smbFile, mime);
            } else {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Not a file");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }

    private Response serveFile(Map<String, String> headers, SmbFile file, String mime) throws IOException {
        InputStream fis = null;
        boolean success = false;

        try {
            long fileLen = file.length();
            fis = file.getInputStream();

            // 解析Range头
            long startFrom = 0;
            String range = headers.get("range");
            if (range != null && range.startsWith("bytes=")) {
                range = range.substring("bytes=".length());
                int minus = range.indexOf('-');
                try {
                    if (minus > 0) {
                        startFrom = Long.parseLong(range.substring(0, minus));
                    }
                } catch (NumberFormatException ignored) {}
            }

            Response res;
            if (range != null && startFrom > 0 && startFrom < fileLen) {
                // Range请求 - 视频拖拽
                long newLen = fileLen - startFrom;
                long skipped = fis.skip(startFrom);
                Log.d(TAG, "Range: " + startFrom + " skip=" + skipped);

                res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, newLen);
                res.addHeader("Accept-Ranges", "bytes");
                res.addHeader("Content-Length", "" + newLen);
                res.addHeader("Content-Range", "bytes " + startFrom + "-" + (fileLen-1) + "/" + fileLen);
            } else {
                // 完整文件
                res = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLen);
                res.addHeader("Content-Length", Long.toString(fileLen));
                res.addHeader("Accept-Ranges", "bytes");
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
        return "application/octet-stream";
    }

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
