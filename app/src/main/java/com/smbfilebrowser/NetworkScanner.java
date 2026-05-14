package com.smbfilebrowser;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jcifs.CIFSContext;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * 局域网 SMB 共享扫描器
 * 
 * 自动扫描同一 WiFi 下的 SMB 服务器和共享文件夹
 */
public class NetworkScanner {
    private static final String TAG = "NetworkScanner";
    private static final int SMB_PORT = 445;
    private static final int TIMEOUT_MS = 1000;
    private static final int THREAD_POOL_SIZE = 20;

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
            return name != null && !name.isEmpty() ? name + " (" + ip + ")" : ip;
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

                // 获取本机 IP 和网段
                String localIp = getLocalIpAddress();
                if (localIp == null) {
                    callback.onScanFailed("无法获取本机 IP 地址，请连接 WiFi");
                    return;
                }

                Log.d(TAG, "Local IP: " + localIp);

                // 解析网段（例如 192.168.1）
                String[] parts = localIp.split("\\.");
                if (parts.length != 4) {
                    callback.onScanFailed("IP 地址格式错误: " + localIp);
                    return;
                }

                String subnet = parts[0] + "." + parts[1] + "." + parts[2];
                Log.d(TAG, "Scanning subnet: " + subnet + ".x");

                // 扫描 1-254
                List<Future<SmbServer>> futures = new ArrayList<>();
                for (int i = 1; i <= 254; i++) {
                    final String ip = subnet + "." + i;
                    if (ip.equals(localIp)) {
                        continue; // 跳过本机
                    }
                    futures.add(executor.submit(() -> scanHost(ip)));
                }

                // 收集结果
                List<SmbServer> servers = new ArrayList<>();
                for (Future<SmbServer> future : futures) {
                    try {
                        SmbServer server = future.get(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
                        if (server != null) {
                            servers.add(server);
                            Log.d(TAG, "Found server: " + server);
                            // 通知 UI
                            if (this.callback != null) {
                                this.callback.onServerFound(server);
                            }
                        }
                    } catch (Exception e) {
                        // 忽略超时
                    }
                }

                Log.d(TAG, "Scan completed, found " + servers.size() + " servers");
                callback.onScanCompleted(servers);

            } catch (Exception e) {
                Log.e(TAG, "Scan failed: " + e.getMessage(), e);
                callback.onScanFailed("扫描失败: " + e.getMessage());
            } finally {
                scanning = false;
                if (executor != null) {
                    executor.shutdown();
                }
            }
        }).start();
    }

    /**
     * 停止扫描
     */
    public void stopScan() {
        scanning = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 扫描单个主机
     */
    private SmbServer scanHost(String ip) {
        try {
            // 先检查端口是否开放
            if (!isPortOpen(ip, SMB_PORT, TIMEOUT_MS)) {
                return null;
            }

            Log.d(TAG, "Port 445 open on " + ip);

            // 尝试获取 SMB 信息
            CIFSContext context = new BaseContext();
            context = context.withCredentials(new NtlmPasswordAuthenticator(null, "Guest", ""));

            // 尝试列出共享
            String url = "smb://" + ip + "/";
            SmbFile file = new SmbFile(url, context);
            file.connect();

            String serverName = file.getServer();
            SmbServer server = new SmbServer(ip, serverName);

            // 列出共享文件夹
            try {
                SmbFile[] shares = file.listFiles();
                if (shares != null) {
                    for (SmbFile share : shares) {
                        String name = share.getName();
                        if (name != null && !name.isEmpty()) {
                            // 去掉末尾的斜杠
                            if (name.endsWith("/")) {
                                name = name.substring(0, name.length() - 1);
                            }
                            server.shares.add(name);
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not list shares on " + ip + ": " + e.getMessage());
            }

            return server;

        } catch (Exception e) {
            Log.d(TAG, "Not an SMB server: " + ip + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 检查端口是否开放
     */
    private boolean isPortOpen(String ip, int port, int timeout) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (!address.isReachable(timeout)) {
                return false;
            }
            
            // 尝试连接 SMB 端口
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(ip, port), timeout);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取本机 IP 地址
     */
    private String getLocalIpAddress() {
        if (wifiManager == null) {
            return null;
        }

        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            return null;
        }

        int ipAddress = wifiInfo.getIpAddress();
        if (ipAddress == 0) {
            return null;
        }

        // 将 int 转换为 String
        return ((ipAddress & 0xff) + "." +
                ((ipAddress >> 8) & 0xff) + "." +
                ((ipAddress >> 16) & 0xff) + "." +
                ((ipAddress >> 24) & 0xff));
    }

    /**
     * 是否正在扫描
     */
    public boolean isScanning() {
        return scanning;
    }
}
