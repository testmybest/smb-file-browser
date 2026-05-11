# SMB 文件浏览器 - Android APK

## 📱 这是一个完整的Android项目，可以直接编译成APK

### 功能特性
- ✅ 连接Windows SMB共享文件夹
- ✅ 浏览文件夹和文件
- ✅ 在线播放视频（MP4/MKV/AVI等）
- ✅ 在线播放音乐（MP3/FLAC等）
- ✅ 查看图片
- ✅ 下载文件到手机
- ✅ 上传文件到共享
- ✅ 创建/删除/重命名文件夹

---

## 🚀 编译方法

### 方法一：在Android Studio中编译（推荐）

1. **下载Android Studio**
   - 访问：https://developer.android.com/studio
   - 下载并安装

2. **打开项目**
   - 打开Android Studio
   - 选择 "Open an Existing Project"
   - 选择 `/workspace/smb-file-browser` 文件夹

3. **编译APK**
   - 点击菜单 Build → Build Bundle(s) / APK(s) → Build APK(s)
   - 等待编译完成
   - APK位置：`app/build/outputs/apk/debug/app-debug.apk`

### 方法二：命令行编译

```bash
# 设置环境变量
export ANDROID_HOME=/path/to/android-sdk

# 进入项目目录
cd /workspace/smb-file-browser

# 编译
./gradlew assembleDebug

# APK位置
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📲 使用方法

1. 安装APK到手机
2. 打开应用
3. 输入Windows电脑的IP地址（如：192.168.1.100）
4. 输入共享文件夹名称
5. 输入用户名和密码（如果需要）
6. 点击"连接"

---

## 🔧 获取Windows共享信息

### 查看IP地址
1. 在Windows上按 Win+R
2. 输入 `cmd` 回车
3. 输入 `ipconfig` 回车
4. 找到 "IPv4 地址"（如：192.168.1.100）

### 设置共享
1. 右键要共享的文件夹
2. 选择"属性" → "共享"
3. 点击"共享"按钮
4. 添加用户并设置权限
5. 记住共享名称

---

## 📁 项目结构

```
smb-file-browser/
├── app/
│   ├── src/main/
│   │   ├── java/com/smbfilebrowser/
│   │   │   ├── MainActivity.java        # 连接页面
│   │   │   ├── FileBrowserActivity.java # 文件浏览
│   │   │   ├── VideoPlayerActivity.java # 视频播放
│   │   │   ├── ImageViewerActivity.java # 图片查看
│   │   │   └── SMBManager.java          # SMB核心
│   │   ├── res/                         # 资源文件
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

---

## ⚠️ 注意事项

1. 确保手机和电脑在同一WiFi网络
2. Windows防火墙可能需要允许SMB连接
3. 如果连接失败，检查：
   - IP地址是否正确
   - 共享名称是否正确
   - 用户名密码是否正确
   - Windows防火墙设置
