package com.smbfilebrowser;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 主页面 - 连接SMB服务器
 */
public class MainActivity extends AppCompatActivity {

    private EditText etHost, etShare, etUsername, etPassword;
    private Button btnConnect, btnScan;
    private ProgressBar progressBar;
    private TextView tvError, tvScanStatus;
    private LinearLayout scanLayout;
    private ListView lvScanResults;

    private SMBManager smbManager;
    private NetworkScanner networkScanner;
    private ArrayAdapter<String> scanAdapter;
    private List<NetworkScanner.SmbServer> scannedServers = new ArrayList<>();
    
    // 用于展示的扁平化列表：每个条目是一个 (server, share) 组合
    private List<ServerShareEntry> shareEntries = new ArrayList<>();
    
    private static class ServerShareEntry {
        NetworkScanner.SmbServer server;
        String share;
        String displayText;
        
        ServerShareEntry(NetworkScanner.SmbServer server, String share) {
            this.server = server;
            this.share = share;
            this.displayText = server.ip + " - " + share;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = findViewById(R.id.et_host);
        etShare = findViewById(R.id.et_share);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnConnect = findViewById(R.id.btn_connect);
        progressBar = findViewById(R.id.progress_bar);
        tvError = findViewById(R.id.tv_error);
        
        btnScan = findViewById(R.id.btn_scan);
        scanLayout = findViewById(R.id.scan_layout);
        tvScanStatus = findViewById(R.id.tv_scan_status);
        lvScanResults = findViewById(R.id.lv_scan_results);

        smbManager = SMBManager.getInstance();
        networkScanner = new NetworkScanner(this);

        scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvScanResults.setAdapter(scanAdapter);

        btnConnect.setOnClickListener(v -> connect());
        btnScan.setOnClickListener(v -> startScan());

        // 点击扫描结果 - 直接选择该共享
        lvScanResults.setOnItemClickListener((parent, view, position, id) -> {
            if (position < shareEntries.size()) {
                ServerShareEntry entry = shareEntries.get(position);
                etHost.setText(entry.server.ip);
                etShare.setText(entry.share);
                scanLayout.setVisibility(View.GONE);
                Toast.makeText(this, "已选择: " + entry.server.ip + "/" + entry.share, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void connect() {
        String host = etHost.getText().toString().trim();
        String share = etShare.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();

        tvError.setVisibility(View.GONE);

        if (host.isEmpty()) {
            etHost.setError("请输入服务器地址");
            return;
        }
        if (share.isEmpty()) {
            etShare.setError("请输入共享名称");
            return;
        }

        setLoading(true);

        new Thread(() -> {
            boolean success = smbManager.connect(host, share, username, password);

            runOnUiThread(() -> {
                setLoading(false);

                if (success) {
                    Intent intent = new Intent(MainActivity.this, FileBrowserActivity.class);
                    startActivity(intent);
                    Toast.makeText(this, "连接成功！", Toast.LENGTH_SHORT).show();
                } else {
                    tvError.setVisibility(View.VISIBLE);
                    tvError.setText("连接失败，请检查：\n" +
                            "1. 手机和电脑是否在同一WiFi\n" +
                            "2. IP地址是否正确\n" +
                            "3. 共享名称是否正确\n" +
                            "4. Windows防火墙是否允许SMB\n" +
                            "5. 尝试输入Windows用户名和密码");
                    Toast.makeText(this, "连接失败", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void startScan() {
        if (networkScanner.isScanning()) {
            networkScanner.stopScan();
            btnScan.setText("扫描网络");
            tvScanStatus.setText("扫描已停止");
            return;
        }

        scannedServers.clear();
        shareEntries.clear();
        scanAdapter.clear();
        scanLayout.setVisibility(View.VISIBLE);
        btnScan.setText("停止扫描");
        tvScanStatus.setText("正在扫描...");

        networkScanner.startScan(new NetworkScanner.ScanCallback() {
            @Override
            public void onScanStarted() {
                runOnUiThread(() -> tvScanStatus.setText("正在扫描局域网..."));
            }

            @Override
            public void onServerFound(NetworkScanner.SmbServer server) {
                runOnUiThread(() -> {
                    scannedServers.add(server);
                    
                    // 为每个共享创建一个条目
                    if (server.shares.isEmpty()) {
                        // 没有发现共享，显示服务器但不添加到选择列表
                        scanAdapter.add(server.ip + " (无共享)");
                    } else {
                        for (String share : server.shares) {
                            ServerShareEntry entry = new ServerShareEntry(server, share);
                            shareEntries.add(entry);
                            scanAdapter.add(entry.displayText);
                        }
                    }
                    
                    tvScanStatus.setText("已发现 " + scannedServers.size() + " 个服务器, " + shareEntries.size() + " 个共享");
                });
            }

            @Override
            public void onScanCompleted(List<NetworkScanner.SmbServer> servers) {
                runOnUiThread(() -> {
                    btnScan.setText("扫描网络");
                    if (shareEntries.isEmpty()) {
                        tvScanStatus.setText("未发现可访问的 SMB 共享");
                    } else {
                        tvScanStatus.setText("扫描完成，发现 " + shareEntries.size() + " 个共享");
                    }
                });
            }

            @Override
            public void onScanFailed(String error) {
                runOnUiThread(() -> {
                    btnScan.setText("扫描网络");
                    tvScanStatus.setText("扫描失败: " + error);
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnConnect.setEnabled(!loading);
        btnConnect.setText(loading ? "正在连接..." : "连接");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkScanner != null) {
            networkScanner.stopScan();
        }
    }
}
