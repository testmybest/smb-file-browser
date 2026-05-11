package com.smbfilebrowser;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文件浏览页面 - 支持下载和上传
 */
public class FileBrowserActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private FloatingActionButton fabUpload;
    private TextView tvEmpty;
    private View loadingView;

    private FileAdapter adapter;
    private SMBManager smbManager;
    private String currentPath = "";

    private ProgressDialog progressDialog;

    private static final int REQUEST_PERMISSION = 100;
    private static final int REQUEST_PICK_FILE = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        initViews();
        setupActionBar();
        requestPermissions();
        loadFiles();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_view);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        fabUpload = findViewById(R.id.fab_upload);
        tvEmpty = findViewById(R.id.tv_empty);
        loadingView = findViewById(R.id.loading_view);

        smbManager = SMBManager.getInstance();
        adapter = new FileAdapter();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadFiles);

        fabUpload.setOnClickListener(v -> pickFile());
    }

    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("文件浏览");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_browser, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_refresh) {
            loadFiles();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (currentPath.isEmpty()) {
            super.onBackPressed();
        } else {
            int lastSlash = currentPath.lastIndexOf('/');
            currentPath = lastSlash > 0 ? currentPath.substring(0, lastSlash) : "";
            loadFiles();
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, REQUEST_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要存储权限才能下载文件", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadFiles() {
        loadingView.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                List<SMBManager.FileItem> files = smbManager.listDirectory(currentPath);

                runOnUiThread(() -> {
                    loadingView.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                    adapter.setData(files);
                    if (files.isEmpty()) {
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingView.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                    Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ========== 下载文件 ==========

    private void downloadFile(SMBManager.FileItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("下载文件")
                .setMessage("确定要下载 \"" + item.name + "\" 到手机吗？\n大小: " + formatSize(item.size))
                .setPositiveButton("下载", (dialog, which) -> doDownload(item))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDownload(SMBManager.FileItem item) {
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("正在下载...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.show();

        String localPath = smbManager.getDownloadDir() + "/" + item.name;

        smbManager.downloadFile(item.path, localPath, new SMBManager.DownloadListener() {
            @Override
            public void onProgress(int percent, long downloaded, long total, long speed) {
                runOnUiThread(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.setProgress(percent);
                        progressDialog.setMessage("下载中 " + percent + "%\n" +
                                formatSize(downloaded) + " / " + formatSize(total) +
                                "\n速度: " + formatSize(speed) + "/s");
                    }
                });
            }

            @Override
            public void onComplete(String path) {
                runOnUiThread(() -> {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                        progressDialog = null;
                    }
                    Toast.makeText(FileBrowserActivity.this,
                            "下载完成！\n保存到: " + path, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onFailed(String error) {
                runOnUiThread(() -> {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                        progressDialog = null;
                    }
                    Toast.makeText(FileBrowserActivity.this,
                            "下载失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ========== 上传文件 ==========

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK) {
            if (data != null) {
                if (data.getClipData() != null) {
                    // 多选
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        uploadFromUri(uri);
                    }
                } else if (data.getData() != null) {
                    // 单选
                    uploadFromUri(data.getData());
                }
            }
        }
    }

    private void uploadFromUri(Uri uri) {
        try {
            // 从URI获取真实文件路径
            String localPath = getRealPathFromUri(uri);
            if (localPath == null) {
                Toast.makeText(this, "无法读取该文件", Toast.LENGTH_SHORT).show();
                return;
            }

            String fileName = localPath.substring(localPath.lastIndexOf('/') + 1);
            String remotePath = currentPath.isEmpty() ? fileName : currentPath + "/" + fileName;

            progressDialog = new ProgressDialog(this);
            progressDialog.setMessage("正在上传...");
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setMax(100);
            progressDialog.setProgress(0);
            progressDialog.setCancelable(false);
            progressDialog.show();

            smbManager.uploadFile(localPath, remotePath, new SMBManager.UploadListener() {
                @Override
                public void onProgress(int percent, long uploaded, long total, long speed) {
                    runOnUiThread(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.setProgress(percent);
                            progressDialog.setMessage("上传中 " + percent + "%\n" +
                                    formatSize(uploaded) + " / " + formatSize(total) +
                                    "\n速度: " + formatSize(speed) + "/s");
                        }
                    });
                }

                @Override
                public void onComplete(String path) {
                    runOnUiThread(() -> {
                        if (progressDialog != null) {
                            progressDialog.dismiss();
                            progressDialog = null;
                        }
                        Toast.makeText(FileBrowserActivity.this,
                                "上传成功！", Toast.LENGTH_SHORT).show();
                        loadFiles();
                    });
                }

                @Override
                public void onFailed(String error) {
                    runOnUiThread(() -> {
                        if (progressDialog != null) {
                            progressDialog.dismiss();
                            progressDialog = null;
                        }
                        Toast.makeText(FileBrowserActivity.this,
                                "上传失败: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "上传失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 从URI获取真实文件路径
     */
    private String getRealPathFromUri(Uri uri) {
        try {
            // 先尝试直接从URI获取路径
            String path = uri.getPath();
            if (path != null && new java.io.File(path).exists()) {
                return path;
            }

            // 尝试通过ContentResolver查询
            String[] projection = {android.provider.OpenableColumns.DISPLAY_NAME};
            try (android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String displayName = cursor.getString(0);
                    // 保存到缓存目录
                    File cacheDir = getCacheDir();
                    File destFile = new File(cacheDir, displayName);
                    try (InputStream is = getContentResolver().openInputStream(uri);
                         FileOutputStream os = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                    }
                    return destFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            Log.e("FileBrowser", "获取文件路径失败", e);
        }
        return null;
    }

    // ========== 文件操作菜单 ==========

    private void showFileMenu(View view, SMBManager.FileItem item) {
        PopupMenu menu = new PopupMenu(this, view);
        menu.getMenuInflater().inflate(R.menu.menu_file_item, menu.getMenu());

        // 如果是文件夹，隐藏下载和播放选项
        if (item.isDirectory) {
            menu.getMenu().findItem(R.id.action_download).setVisible(false);
            menu.getMenu().findItem(R.id.action_play).setVisible(false);
        } else {
            // 非媒体文件隐藏播放选项
            if (!isVideoFile(item) && !isAudioFile(item)) {
                menu.getMenu().findItem(R.id.action_play).setVisible(false);
            }
        }

        menu.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.action_play) {
                openMediaPlayer(item);
                return true;
            } else if (id == R.id.action_download) {
                downloadFile(item);
                return true;
            } else if (id == R.id.action_delete) {
                confirmDelete(item);
                return true;
            } else if (id == R.id.action_info) {
                showFileInfo(item);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void confirmDelete(SMBManager.FileItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除 \"" + item.name + "\" 吗？此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    new Thread(() -> {
                        try {
                            smbManager.delete(item.path);
                            runOnUiThread(() -> {
                                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
                                loadFiles();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    "删除失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFileInfo(SMBManager.FileItem item) {
        String info = "名称: " + item.name + "\n"
                + "类型: " + (item.isDirectory ? "文件夹" : "文件") + "\n"
                + "大小: " + (item.isDirectory ? "-" : formatSize(item.size)) + "\n"
                + "路径: " + item.path + "\n"
                + "修改时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(item.modified));

        new MaterialAlertDialogBuilder(this)
                .setTitle("文件信息")
                .setMessage(info)
                .setPositiveButton("确定", null)
                .show();
    }

    // ========== 打开媒体文件 ==========

    private void openMediaPlayer(SMBManager.FileItem item) {
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra("file_path", item.path);
        intent.putExtra("file_name", item.name);
        intent.putExtra("is_audio", isAudioFile(item));
        startActivity(intent);
    }

    private void openImageViewer(SMBManager.FileItem item) {
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra("file_path", item.path);
        intent.putExtra("file_name", item.name);
        startActivity(intent);
    }

    private boolean isVideoFile(SMBManager.FileItem item) {
        String name = item.name.toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                || name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv")
                || name.endsWith(".webm") || name.endsWith(".rmvb") || name.endsWith(".3gp")
                || name.endsWith(".ts") || name.endsWith(".m4v");
    }

    private boolean isAudioFile(SMBManager.FileItem item) {
        String name = item.name.toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac")
                || name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".m4a")
                || name.endsWith(".wma") || name.endsWith(".ape");
    }

    private boolean isImageFile(SMBManager.FileItem item) {
        String name = item.name.toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp");
    }

    // ========== 工具方法 ==========

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ========== 文件适配器 ==========

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

        private List<SMBManager.FileItem> data = new ArrayList<>();
        private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        public void setData(List<SMBManager.FileItem> data) {
            this.data = data;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SMBManager.FileItem item = data.get(position);

            holder.tvName.setText(item.name);

            if (item.isDirectory) {
                holder.ivIcon.setImageResource(R.drawable.ic_folder);
                holder.tvSize.setText("文件夹");
                holder.tvSize.setTextColor(0xFFFFB800);
            } else {
                holder.ivIcon.setImageResource(getFileIcon(item));
                holder.tvSize.setText(formatSize(item.size));
                holder.tvSize.setTextColor(0xFF757575);
            }

            if (item.modified > 0) {
                holder.tvDate.setText(dateFormat.format(new Date(item.modified)));
            } else {
                holder.tvDate.setText("");
            }

            // 点击
            holder.itemView.setOnClickListener(v -> {
                if (item.isDirectory) {
                    currentPath = item.path;
                    loadFiles();
                } else if (isVideoFile(item) || isAudioFile(item)) {
                    // 视频/音频文件直接播放
                    openMediaPlayer(item);
                } else if (isImageFile(item)) {
                    // 图片文件打开查看器
                    openImageViewer(item);
                } else {
                    // 其他文件弹出菜单
                    showFileMenu(v, item);
                }
            });

            // 长按
            holder.itemView.setOnLongClickListener(v -> {
                showFileMenu(v, item);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private int getFileIcon(SMBManager.FileItem item) {
            String name = item.name.toLowerCase();
            if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                    || name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv")
                    || name.endsWith(".webm")) {
                return R.drawable.ic_video;
            }
            if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac")
                    || name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".m4a")) {
                return R.drawable.ic_audio;
            }
            if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")) {
                return R.drawable.ic_image;
            }
            if (name.endsWith(".pdf")) {
                return R.drawable.ic_pdf;
            }
            if (name.endsWith(".doc") || name.endsWith(".docx")) {
                return R.drawable.ic_file;
            }
            if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
                return R.drawable.ic_file;
            }
            if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z")) {
                return R.drawable.ic_file;
            }
            return R.drawable.ic_file;
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvName, tvSize, tvDate;

            ViewHolder(View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_icon);
                tvName = itemView.findViewById(R.id.tv_name);
                tvSize = itemView.findViewById(R.id.tv_size);
                tvDate = itemView.findViewById(R.id.tv_date);
            }
        }
    }
}
