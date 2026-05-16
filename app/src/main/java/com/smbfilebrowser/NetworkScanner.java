package com.smbfilebrowser;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * 局域网 SMB 共享扫描器
 * 
 * 快速扫描同一 WiFi 下的 SMB 服务器
 */
public class NetworkScanner {
    private static final String TAG = "NetworkScanner";
    private static final int SMB_PORT = 445;
    private static final int CONNECT_TIMEOUT = 500; // 500ms 超时
    private static final int THREAD_POOL_SIZE = 50;

    private final Context context;
    private final WifiManager wifiManager;
    private ScanCallback callback;
    private ExecutorService executor;
    private volatile boolean scanning = false;

    public interface ScanCallback {
        void onScanStarted();
        void onServerFound(SmbServer server);
        void onScanCompleted(List<SmbServer> servers);
        void onScanFailed(String error);
    }

    public static class SmbServer {
        public String ip;
        public String name;
        public List<String> shares = new ArrayList<>();

        public SmbServer(String ip, String name) {
            this.ip = ip;
            this.name = name;
        }

        @Override
        public String toString() {
            if (name != null && !name.isEmpty() && !name.equals(ip)) {
                return name + " (" + ip + ")";
            }
            return ip;
        }
    }

    public NetworkScanner(Context context) {
        this.context = context;
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * 开始扫描局域网
     */
    public void startScan(ScanCallback callback) {
        if (scanning) {
            Log.w(TAG, "Scan already in progress");
            return;
        }

        this.callback = callback;
        this.scanning = true;
        this.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        new Thread(() -> {
            try {
                callback.onScanStarted();

                String localIp = getLocalIpAddress();
                if (localIp == null) {
                    callback.onScanFailed("无法获取本机 IP，请确认已连接 WiFi");
                    return;
                }

                String[] parts = localIp.split("\\.");
                String subnet = parts[0] + "." + parts[1] + "." + parts[2];
                Log.d(TAG, "Scanning subnet: " + subnet + ".x (local: " + localIp + ")");

                // 第一阶段：快速扫描端口（50线程并行）
                List<String> aliveHosts = new ArrayList<>();
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

                for (int i = 1; i <= 254; i++) {
                    final String ip = subnet + "." + i;
                    if (ip.equals(localIp)) continue;

                    futures.add(executor.submit(() -> {
                        if (!scanning) return;
                        if (isPortOpen(ip, SMB_PORT)) {
                            synchronized (aliveHosts) {
                                aliveHosts.add(ip);
                            }
                        }
                    }));
                }

                // 等待端口扫描完成
                for (java.util.concurrent.Future<?> f : futures) {
                    try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }

                Log.d(TAG, "Found " + aliveHosts.size() + " hosts with port 445 open");

                if (!scanning) return;

                // 第二阶段：对开放端口的主机尝试SMB连接
                List<SmbServer> servers = new CopyOnWriteArrayList<>();
                List<java.util.concurrent.Future<?>> smbFutures = new ArrayList<>();

                for (String ip : aliveHosts) {
                    if (!scanning) break;
                    smbFutures.add(executor.submit(() -> {
                        if (!scanning) return;
                        try {
                            SmbServer server = probeSmbServer(ip);
                            if (server != null) {
                                servers.add(server);
                                if (callback != null) {
                                    callback.onServerFound(server);
                                }
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "Probe failed " + ip + ": " + e.getMessage());
                        }
                    }));
                }

                for (java.util.concurrent.Future<?> f : smbFutures) {
                    try { f.get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }

                if (!scanning) return;

                Log.d(TAG, "Scan completed: " + servers.size() + " SMB servers found");
                callback.onScanCompleted(new ArrayList<>(servers));

            } catch (Exception e) {
                Log.e(TAG, "Scan error: " + e.getMessage(), e);
                if (scanning) {
                    callback.onScanFailed("扫描失败: " + e.getMessage());
                }
            } finally {
                scanning = false;
                if (executor != null) executor.shutdown();
            }
        }).start();
    }

    /**
     * 探测SMB服务器
     */
    private SmbServer probeSmbServer(String ip) {
        try {
            // 创建独立的SMB context（不依赖外部连接）
            java.util.Properties props = new java.util.Properties();
            props.setProperty("jcifs.smb.client.responseTimeout", "2000");
            props.setProperty("jcifs.smb.client.soTimeout", "2000");
            PropertyConfiguration config = new PropertyConfiguration(props);
            CIFSContext ctx = new BaseContext(config);
            ctx = ctx.withCredentials(
                    new NtlmPasswordAuthenticator(null, "Guest", ""));

            String url = "smb://" + ip + "/";
            SmbFile file = new SmbFile(url, ctx);

            // 尝试连接
            file.connect();

            String serverName;
            try {
                serverName = file.getServer();
            } catch (Exception e) {
                serverName = ip;
            }

            SmbServer server = new SmbServer(ip, serverName);

            // 列出共享
            try {
                SmbFile[] shares = file.listFiles();
                if (shares != null) {
                    for (SmbFile share : shares) {
                        String name = share.getName();
                        if (name != null && !name.isEmpty()) {
                            if (name.endsWith("/")) {
                                name = name.substring(0, name.length() - 1);
                            }
                            // 过滤掉系统共享
                            if (!name.startsWith("$") && !name.equals("IPC$")) {
                                server.shares.add(name);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not list shares on " + ip);
            }

            // 至少要有一个非隐藏共享才算有效
            if (!server.shares.isEmpty()) {
                Log.d(TAG, "Found SMB server: " + server + " shares: " + server.shares);
                return server;
            }

            // 即使没有列出共享，也返回（可能需要认证）
            return server;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 快速检查端口是否开放
     */
    private boolean isPortOpen(String ip, int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 获取本机 IP 地址
     */
    private String getLocalIpAddress() {
        if (wifiManager == null) return null;

        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) return null;

        int ipAddress = wifiInfo.getIpAddress();
        if (ipAddress == 0) return null;

        return ((ipAddress & 0xff) + "." +
                ((ipAddress >> 8) & 0xff) + "." +
                ((ipAddress >> 16) & 0xff) + "." +
                ((ipAddress >> 24) & 0xff));
    }

    public void stopScan() {
        scanning = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public boolean isScanning() {
        return scanning;
    }
}
