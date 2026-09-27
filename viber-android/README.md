# RemoteViber Android Native Client

纯原生 Android 客户端，完全复刻 RemoteViber Web 端 UI 与交互，**不使用 WebView 包装**。通过纯 Kotlin + Jetpack Compose + OkHttp WebSocket 直接与 RemoteViber Host 通信。

---

## 🌟 核心特性

1. **纯原生 Jetpack Compose 界面 (无 WebView)**:
   - **赛博深色主题 (Sci-Fi Dark)**: 深度黑金/青蓝配比（`#090D16`，Cyan `#22D3EE`，Indigo `#6366F1`）。
   - **顶部状态栏 (`TopBar`)**: 主机在线状态、Ping 延迟指示、控制面板/终端工作台双模式切换开关、快捷终端与启动弹窗入口、主机多配置切换器。
   - **系统资源监控卡片 (`SystemMonitorCard`)**: CPU 动态进度条、RAM 内存条、网络上下行速率、运行进程数统计。
   - **预设模板卡片网格 (`PresetCard`)**: 一键执行预配置（支持 Docker 容器启动与多指令脚本）、查看命令、删除预设。
   - **活跃会话矩阵 (`SessionCard`)**: 按项目文件夹自动分组、实时状态绿/灰点、PID 跟踪、运行时间、连接/关闭/重启/销毁。
   - **多标签终端工作台 (`SessionTabs` + `TerminalScreen`)**: 横向滚动标签栏、快速关闭、一键返回控制面板。
   - **原生终端虚拟辅助键盘栏 (`VirtualKeyboardBar`)**:
     - 终端快捷键：`ESC`, `TAB`, `CTRL`, `^C`, `^D`, `^Z`, `^L`, 方向键 `←`, `↑`, `↓`, `→`。
     - 剪贴板快速粘贴：一键将手机剪贴板命令粘贴输入终端。
     - 字体无级缩放：`A-` / `A+`（支持 9sp ~ 22sp）。
     - 快捷 Prompt 提示词抽屉：内置 `git status`, `docker ps`, `htop`, `tail -f logs` 等高频运维命令。

2. **高性能原生 ANSI 终端解析引擎 (`TerminalBuffer`)**:
   - 纯 Kotlin 实现 ANSI 转义序列解析（支持 16 色与 256 色色表、粗体/下划线、光标回车 `\r` 覆盖更新、自动折行）。
   - 直接生成 Compose `AnnotatedString`，由 `LazyColumn` 虚拟化滚动渲染，万行日志流畅不卡顿。

3. **客户端逻辑与网络能力**:
   - **多主机配置管理 (`HostManager`)**: 本地存储多个 Host 配置文件，支持一键切换。
   - **一键配对与 Deep Link**: 支持扫描或点击 `viber://connect?data=...` 及 `http://...:8765/?token=...` 自动解析并绑定主机。
   - **原生 OkHttp WebSocket 客户端 (`ViberWebSocketClient`)**:
     - 握手协议：`HELLO` 身份认证、`WELCOME` 状态同步。
     - 保持存活：心跳 Ping/Pong 检测、网络波动或手机息屏后自动重连。
     - 会话管理：`SESSION_LIST`, `SESSION_START`, `SESSION_ATTACH`, `SESSION_TERMINATE`, `SESSION_RESTART`。
     - 客户端安全构造：支持 Docker 参数构建与多行指令合并执行。

---

## 📁 目录结构

```
viber-android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/remoteviber/client/
│       │   ├── ViberApplication.kt         # 应用入口
│       │   ├── MainActivity.kt             # Deep link 路由与界面宿主
│       │   ├── model/
│       │   │   ├── Models.kt               # 会话、预设、系统状态数据模型
│       │   │   └── TerminalBuffer.kt       # 原生 ANSI 终端解析引擎
│       │   ├── network/
│       │   │   └── ViberWebSocketClient.kt # OkHttp WebSocket 协议通信
│       │   ├── data/
│       │   │   └── HostManager.kt          # 多主机 SharedPreferences 存储与 DeepLink 解析
│       │   └── ui/
│       │       ├── ViberMainApp.kt         # 主应用路由与脚手架
│       │       ├── theme/
│       │       │   ├── Color.kt            # 赛博深色调配色盘
│       │       │   └── Theme.kt            # Material3 主题配置
│       │       ├── components/
│       │       │   ├── TopBar.kt               # 顶部导航与主机状态栏
│       │       │   ├── SystemMonitorCard.kt    # CPU/内存/网络资源卡片
│       │       │   ├── PresetCard.kt           # 预设卡片 (Docker / 命令)
│       │       │   ├── SessionCard.kt          # 进程会话矩阵卡片
│       │       │   ├── SessionTabs.kt          # 终端标签页横向栏
│       │       │   ├── VirtualKeyboardBar.kt   # 悬浮辅助键盘与快捷指令
│       │       │   ├── LaunchBottomSheet.kt    # 新建/启动会话抽屉
│       │       │   └── HostManagerDialog.kt    # 多主机管理切换弹窗
│       │       └── screens/
│       │           ├── DashboardScreen.kt      # 控制面板主页
│       │           └── TerminalScreen.kt       # 终端工作台全屏页
│       └── res/
│           ├── values/ (colors, strings, themes)
│           └── mipmap-anydpi-v26/ (ic_launcher)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradle/wrapper/gradle-wrapper.properties
```

---

## 🛠️ 构建与安装

### 方式 1：使用 Android Studio
1. 打开 **Android Studio (Giraffe / Iguana / Koala 或更高版本)**。
2. 选择 **Open** 并导入 `/workspace/viber-android` 目录。
3. 等待 Gradle 同步完成（使用 JDK 17 或 JDK 21）。
4. 连接 Android 手机或模拟器，点击 **Run 'app'** 即可直接安装运行。

### 方式 2：使用命令行 Gradle 打包 APK
在配置有 Android SDK 和 JDK 的环境下执行：
```bash
cd viber-android
chmod +x gradlew
./gradlew assembleDebug
```
生成的 APK 文件位于：
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📲 配对与使用方法

1. **启动 RemoteViber Host**:
   ```bash
   ./viber-host-linux-x86_64
   ```
2. **连接手机**:
   - **方式 A (Deep Link 一键唤醒)**: 在手机浏览器中打开输出的 `viber://connect?data=...` 链接，自动唤醒 RemoteViber 并完成配对。
   - **方式 B (URL 导入)**: 打开 RemoteViber Android App，点击右上角主机图标，粘贴 Host 输出的 Tailscale URL（例如 `http://100.125.28.78:8765/?token=...`），点击“导入”，立即秒级连接。
