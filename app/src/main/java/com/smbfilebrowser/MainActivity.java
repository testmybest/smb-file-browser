package com.smbfilebrowser;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 主页面 - 连接SMB服务器
 */
public class MainActivity extends AppCompatActivity {

    private EditText etHost, etShare, etUsername, etPassword;
    private Button btnConnect;
    private ProgressBar progressBar;
    private TextView tvError;

    private SMBManager smbManager;

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

        smbManager = SMBManager.getInstance();

        btnConnect.setOnClickListener(v -> connect());
    }

    private void connect() {
        String host = etHost.getText().toString().trim();
        String share = etShare.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();

        // 清除错误
        tvError.setVisibility(View.GONE);

        // 验证输入
        if (host.isEmpty()) {
            etHost.setError("请输入服务器地址");
            return;
        }
        if (share.isEmpty()) {
            etShare.setError("请输入共享名称");
            return;
        }

        // 显示进度
        setLoading(true);

        // 在后台线程连接
        new Thread(() -> {
            boolean success = smbManager.connect(host, share, username, password);

            runOnUiThread(() -> {
                setLoading(false);

                if (success) {
                    // 跳转到文件浏览页面
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

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnConnect.setEnabled(!loading);
        btnConnect.setText(loading ? "正在连接..." : "连接");
    }
}
