# SillyTavern Android 酒馆

在安卓手机上原生运行 [SillyTavern](https://github.com/SillyTavern/SillyTavern) — 不需要 Termux，不需要 Root，安装即用。

## 原理

APP 内置了完整的 Node.js 运行时和 SillyTavern 服务端。首次启动时自动解压到内部存储，启动 Node.js 服务器，然后通过 WebView 打开酒馆界面。无需任何外部依赖。

## 功能

- **安装即用** — 不需要 Termux、不需要 Root、不需要配置
- **内嵌 Node.js** — ARM64 运行时打包在 APK 中
- **完整酒馆** — 所有功能可用
- **原生安卓体验** — 全屏 WebView、返回导航、下拉刷新、文件上传
- **暗色主题** — 与酒馆风格一致

## 普通用户：下载 APK

前往 [Releases](../../releases) 下载预编译 APK。要求：
- 安卓 8.0 以上（API 26）
- ARM64 设备（99%+ 的现代手机）
- 约 500 MB 可用存储空间

## 开发者：从源码构建

只需 **2 步**。需要：一台电脑（装有 [Android Studio](https://developer.android.google.cn/studio)、Python 3、Git、Node.js/npm）。

### 第一步：准备资源

```bash
python scripts/setup_assets.py
```

这一条命令自动完成所有事情：
1. 从 Termux 软件仓库下载 **Node.js ARM64 二进制** → `app/src/main/jniLibs/arm64-v8a/libnode.so`
2. 下载并打包**共享库**（libz、libssl、libicu 等） → `app/src/main/assets/nodelibs.zip`
3. 从 GitHub 克隆 **SillyTavern** + `npm install` → `app/src/main/assets/sillytavern.zip`

不需要安卓手机 — Node.js 直接从 Termux 的公开包服务器下载。

<details>
<summary>高级选项</summary>

```bash
# 使用不同的 SillyTavern 分支
python scripts/setup_assets.py --st-branch staging

# 使用本地已有的酒馆源码（跳过 git clone）
python scripts/package_sillytavern.py --local /path/to/SillyTavern

# 使用手机导出的 Termux zip（替代在线下载）
python scripts/setup_assets.py --from-termux path/to/node-android-arm64.zip
```

手机导出方法：将 `scripts/termux_export.sh` 复制到手机，在 Termux 中运行即可。
</details>

### 第二步：编译 APK

1. 用 Android Studio 打开本项目
2. 菜单 **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
3. 输出：`app/build/outputs/apk/debug/app-debug.apk`
4. 安装到手机

## 工作原理

```
┌──────────────────────────────────┐
│          安卓 APP                │
│                                  │
│  1. 首次启动：                    │
│     解压资源到内部存储             │
│     (sillytavern.zip + nodelibs) │
│                                  │
│  2. 启动 Node.js 进程             │
│     (libnode.so 从 nativeLibs)   │
│     设置 LD_LIBRARY_PATH         │
│     执行: node server.js         │
│                                  │
│  3. SillyTavern HTTP 服务器       │
│     监听 127.0.0.1:8000          │
│                                  │
│  4. WebView 加载 localhost:8000   │
│     完整酒馆界面                  │
└──────────────────────────────────┘
```

### 为什么需要 Termux 的库？

Node.js 官方不支持安卓。获取可用的 ARM64 Node.js 二进制最可靠的方式是从 [Termux](https://termux.dev/) 获取。但 Termux 的 Node.js 链接的是 Termux 自己的共享库（libz、libssl、libicu 等），而非安卓系统库。我们把这些库一起打包，并在运行时设置 `LD_LIBRARY_PATH`。

## 项目结构

```
├── app/src/main/
│   ├── kotlin/.../
│   │   ├── LauncherActivity.kt  # 启动页：解压 → 启动 node → 打开 WebView
│   │   ├── MainActivity.kt      # 主界面：全屏 WebView
│   │   ├── NodeManager.kt       # Node.js 进程与解压生命周期管理
│   │   ├── NodeService.kt       # 前台服务（后台保活）
│   │   └── SettingsActivity.kt  # 设置界面
│   ├── assets/                  # ← 由 setup_assets.py 生成
│   ├── jniLibs/arm64-v8a/      # ← 由 setup_assets.py 生成
│   ├── res/
│   └── AndroidManifest.xml
├── scripts/
│   ├── setup_assets.py          # 一键命令：下载 + 打包所有资源
│   ├── download_node.py         # 从 Termux 仓库下载 Node.js
│   ├── package_sillytavern.py   # 克隆 + 打包 SillyTavern
│   ├── package_nodelibs.py      # 打包 .so 共享库
│   └── termux_export.sh         # （可选）在手机上导出
└── README.md
```

## 许可证

本项目仅为安卓外壳。[SillyTavern](https://github.com/SillyTavern/SillyTavern) 本身遵循 [AGPL-3.0](https://github.com/SillyTavern/SillyTavern/blob/release/LICENSE) 协议。
