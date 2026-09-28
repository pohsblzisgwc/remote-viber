# RemoteViber Desktop (Linux & Windows 原生桌面应用)

RemoteViber 桌面端为 Linux 与 Windows 用户提供极致轻量、高弹性、非阻塞的原生独立桌面工作台与多终端拼图体验。
完全基于系统级图形库开发，无外部浏览器或第三方运行环境依赖，启动快至毫秒级、初始内存开销低至约 30MB。

---

## 一、双平台原生架构

### 1. Linux 原生桌面端 (`dist-bin/viber-desktop-linux`)
- **图形核心**：C 语言编写，深度链接 `GTK+ 3.0` 与 `WebKit2GTK 4.1` 原生系统库。
- **窗口与交互**：
  - 支持 Client-Side Decoration (CSD) 现代深色磨砂玻璃顶栏，圆润 Squircle 倒角与柔和阴影。
  - 硬件加速 Cairo / OpenGL / Wayland / X11 极速渲染。
  - 非阻塞异步守护进程管理：启动时自动侦测 8765 端口服务；若未运行，后台异步拉起 `viber-host-linux-x86_64` 并展示原生动画过渡，无需用户手动启停。
- **一键构建**：
  ```bash
  make -C viber-desktop linux
  ```
- **快捷运行**：
  ```bash
  ./dist-bin/viber-desktop-linux
  ```
  桌面集成：将 `dist-bin/remote-viber.desktop` 复制至 `~/.local/share/applications/` 即可在系统启动器/应用菜单中拥有专属高清图标。

---

### 2. Windows 原生桌面端 (`dist-bin/RemoteViber.exe`)
- **图形核心**：基于 Win32 API 与 Microsoft Edge WebView2 Evergreen 原生运行时打造的 64 位独立 PE 程序（体积仅 46KB）。
- **窗口与交互**：
  - 适配 Windows 10/11 原生沉浸式深色模式（`DWMWA_USE_IMMERSIVE_DARK_MODE`）与圆角特性（`DWMWCP_ROUNDED`）。
  - WinSock2 异步非阻塞端口侦测与本地服务自愈拉起。
  - 高弹性 CSS 弹簧动画（Spring Physics）与 GPU 硬件加速渲染。
- **一键构建**：
  ```bash
  make -C viber-desktop windows
  ```
- **快捷运行**：
  双击执行 `dist-bin/RemoteViber.exe`，或在 PowerShell 中运行 `dist-bin/RemoteViber-Windows.ps1`（传入 `-CreateShortcut` 可一键在桌面生成快捷方式）。

---

## 二、终端拼图系统（Mosaic Grid）

前端采用轻量高弹性响应式网格与 5 区域磁吸判定算法：

- **自由拖拽调换位置**：按住各分屏顶部的 `⋮⋮` 拖拽把手即可在终端之间自由调换排列位置（高弹性 spring 动画 `cubic-bezier(0.34, 1.56, 0.64, 1)`）。
- **边缘自动磁吸拼接**：将终端拖动至目标分屏的左、右、上、下边缘，将自动触发磁吸分屏拼图与发光虚线高亮指示。
- **空间感知快捷键指南**：
  - `Alt + 1 ~ 4`：直接切换焦点到第 1~4 号终端分屏
  - `Alt + 方向键 (↑ / ↓ / ← / →)`：基于二维空间网格在相邻终端之间切换焦点
  - `Alt + [` / `Alt + ]`：顺时针/逆时针轮转切换终端焦点
  - `Alt + M` / `F11`：最大化聚焦当前终端 / 还原终端拼图
  - `Alt + \`：向右快速拼接分屏
  - `Alt + -`：向下快速拼接分屏
  - `Alt + S`：与相邻拼图调换位置
  - `Alt + W`：移出当前拼图分屏
  - `Alt + K`：唤起/关闭快捷键速查表
  - `Ctrl + R` / `F5`：刷新当前工作台
