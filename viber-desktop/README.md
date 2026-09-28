# RemoteViber Desktop (Linux & Windows)

RemoteViber 桌面端为 Linux 与 Windows 用户提供极致轻量、高弹性、非阻塞的原生独立窗口与多终端拼图体验。

---

## 一、双重桌面架构（按需选择）

### 模式 A：超低资源原生独立窗口（推荐 ⚡ 资源占用 < 30MB）
利用操作系统底层原生渲染引擎（Windows: Microsoft Edge WebView / Chrome App Mode；Linux: Chromium / Chrome / Brave Native App），无需下载 200MB+ 的冗余运行库，秒级直开、内存消耗极低。

- **Linux 用户**：
  直接执行 `dist-bin/viber-desktop-linux`，或将 `dist-bin/remote-viber.desktop` 放入 `~/.local/share/applications/` 即可在系统启动器/应用菜单中拥有专属图标。
- **Windows 用户**：
  双击执行 `dist-bin/RemoteViber-Windows.bat` 或在 PowerShell 中运行 `dist-bin/RemoteViber-Windows.ps1`（传入 `-CreateShortcut` 可一键在桌面生成快捷方式）。

---

### 模式 B：Electron 独立桌面宿主 (`viber-desktop`)
提供全局窗口热键、系统托盘管理与深度桌面通知。

```bash
cd viber-desktop
npm install
npm start
```

---

## 二、终端拼图系统（Mosaic Grid）

- **自由拖拽调换位置**：按住各分屏顶部的 `⋮⋮` 拖拽把手即可在终端之间自由调换排列位置（高弹性弹簧动画）。
- **边缘自动磁吸拼接**：将终端拖动至目标分屏的左、右、上、下边缘，将自动触发磁吸分屏拼图。
- **快捷键指南**：
  - `Alt + 1 ~ 4`：直接切换焦点到第 1~4 号终端分屏
  - `Alt + 方向键 (↑ / ↓ / ← / →)`：基于空间网格在相邻终端之间切换焦点
  - `Alt + [` / `Alt + ]`：顺时针/逆时针轮转切换终端焦点
  - `Alt + M` / `F11`：最大化聚焦当前终端 / 还原终端拼图
  - `Alt + \`：向右快速拼接分屏
  - `Alt + -`：向下快速拼接分屏
  - `Alt + S`：与相邻拼图调换位置
  - `Alt + W`：移出当前拼图分屏
  - `Alt + K`：唤起/关闭快捷键速查表
