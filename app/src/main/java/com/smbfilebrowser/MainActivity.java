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

        lvScanResults.setOnItemClickListener((parent, view, position, id) -> {
            if (position < scannedServers.size()) {
                NetworkScanner.SmbServer server = scannedServers.get(position);
                etHost.setText(server.ip);
                if (!server.shares.isEmpty()) {
                    etShare.setText(server.shares.get(0));
                }
                scanLayout.setVisibility(View.GONE);
                Toast.makeText(this, "已选择: " + server, Toast.LENGTH_SHORT).show();
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
                    String displayText = server.toString();
                    if (!server.shares.isEmpty()) {
                        displayText += " [" + String.join(", ", server.shares) + "]";
                    }
                    scanAdapter.add(displayText);
                    tvScanStatus.setText("已发现 " + scannedServers.size() + " 个服务器");
                });
            }

            @Override
            public void onScanCompleted(List<NetworkScanner.SmbServer> servers) {
                runOnUiThread(() -> {
                    btnScan.setText("扫描网络");
                    if (servers.isEmpty()) {
                        tvScanStatus.setText("未发现 SMB 服务器");
                    } else {
                        tvScanStatus.setText("扫描完成，发现 " + servers.size() + " 个服务器");
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
