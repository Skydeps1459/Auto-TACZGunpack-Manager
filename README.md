# Auto-TACZGunpack-Manager (Gun Management)

[简体中文](#简体中文) | [English](#english)

---

## 简体中文

面向 **TaCZ（Timeless and Classics Zero，永恒枪械工坊：零）** 的游戏内可视化枪包管理模组，支持 **Minecraft 1.20.1 Forge** 与 **Minecraft 1.21.1 NeoForge**。

无需切出游戏或手动搬运压缩包，在游戏主菜单或暂停菜单即可完成枪包的启用、禁用、搜索下载、更新检测与多人联机同步。

### 核心功能

- **本地枪包双栏管理**：支持 `.zip` 与文件夹形态枪包，展示图标、版本、枪械/弹药/配件数量，双击或点击箭头即可切换启用/禁用并热重载。
- **在线搜索与一键安装**：
  - 支持 **Modrinth** 搜索与一键下载安装（含 SHA-256 校验）。
  - 支持 **GitHub**（`topic:tacz` 仓库与 Release `.zip`）搜索，并内置多条国内下载加速节点切换。
- **枪包更新提示与一键更新**：启动时自动在后台比对本地枪包与 Modrinth 最新版本，发现更新时会在菜单按钮和枪包列表中高亮提示，支持在管理界面一键检查并更新全部枪包。
- **多人联机枪包同步提示**：客户端与服务端同时安装本模组时，进服自动比对服务端已启用枪包，对缺失或版本不一致的枪包弹窗提示并支持从 Modrinth 一键补全。
- **管理员命令**：提供 `/gunmanager` 命令用于快捷查看、重载或切换枪包。

### 环境要求

| 版本分支 | Minecraft | 加载器 | Java | 前置模组 |
|---|---|---|---|---|
| `1.20.1` | 1.20.1 | Forge 47.x | Java 17 | TaCZ（1.20.1 Forge） |
| `1.21.1` | 1.21.1 | NeoForge 21.1.x | Java 21 | TaCZ（1.21.1 NeoForge） |

### 安装方法

1. 根据你的游戏版本安装对应的 **Forge (1.20.1)** 或 **NeoForge (1.21.1)**。
2. 安装对应版本的 **TaCZ** 模组，放入 `.minecraft/mods/` 目录。
3. 从 [Releases](https://github.com/Skydeps1459/Auto-TACZGunpack-Manager/releases) 下载对应版本的 `gun_management-*.jar`，放入 `.minecraft/mods/` 目录。
4. 启动游戏即可使用（多人服务器若需联机枪包一致性提示，服务端 `mods/` 中也需放入同一版本）。

### 使用说明

1. **打开管理界面**
   - 在游戏**主菜单**或**暂停菜单**右下角点击「**枪包管理**」按钮（若有可用更新，按钮会以金色显示「**枪包管理 (N 更新)**」）；
   - 或在模组列表点击本模组的「**Config / 配置**」按钮进入。
2. **本地管理**
   - 左侧为**已禁用枪包**，右侧为**已启用枪包**，支持顶部搜索框按名称或命名空间过滤。
   - 选中后点击中间 `->` / `<-` 按钮，或**双击条目**即可切换状态，点击底部「**应用并重载**」立即生效。
   - 注：为防止游戏运行中卸载导致异常，**进入世界后仅允许启用，禁用/卸载请先返回主菜单**。
3. **检查与更新枪包**
   - 在本地管理页面底部点击「**检查更新**」可手动检测 Modrinth 上的新版本；
   - 检测到更新时，对应条目会标注 `[可更新 -> 版本号]`，底部按钮会变为「**一键更新 (N)**」，点击即可自动下载替换并重载 TaCZ（更新已启用的枪包需在主菜单操作）。
4. **在线获取**
   - 切换到「**在线获取**」标签页，可在顶部切换 **Modrinth** 或 **GitHub** 来源；使用 GitHub 时可点击节点按钮切换直连或加速镜像。
   - 输入关键词后按回车或点击「**搜索**」，选中左侧项目查看详情后点击右下角「**一键安装**」即可自动下载并重载。
5. **管理员命令（需 OP 权限）**
   - `/gunmanager list`：列出所有已识别枪包及其状态
   - `/gunmanager enable <namespace>`：启用指定枪包
   - `/gunmanager reload`：触发 TaCZ 重载

### 相关文档

- 技术架构与实现细节请查看：[docs/TECH_DESIGN.md](docs/TECH_DESIGN.md)

---

## English

An in-game visual gunpack manager mod for **TaCZ (Timeless and Classics Zero)**, supporting both **Minecraft 1.20.1 Forge** and **Minecraft 1.21.1 NeoForge**.

Manage local gunpacks, search and install packs from Modrinth or GitHub, receive automatic gunpack update notifications, and sync missing packs when joining multiplayer servers—all without leaving the game.

### Features

- **Dual-Column Local Manager**: Supports both `.zip` archives and folder gunpacks, displaying icons, versions, and gun/ammo/attachment counts. Double-click or use the arrow buttons to enable/disable and hot-reload.
- **Online Browser & One-Click Install**:
  - Search and install gunpacks from **Modrinth** with automatic SHA-256 verification.
  - Search **GitHub** (`topic:tacz` repositories and release `.zip` assets) with switchable acceleration mirror nodes.
- **Gunpack Update Notifications & One-Click Update**: Automatically checks installed `.zip` gunpacks against Modrinth in the background on startup, highlights available updates on the menu button and pack list, and lets you update all packs with one click.
- **Multiplayer Sync Prompt**: When installed on both client and server, compares server-enabled gunpacks upon joining and prompts to download missing/mismatched packs from Modrinth.
- **Admin Command**: Includes `/gunmanager` for quick listing, enabling, and reloading.

### Requirements

| Branch | Minecraft | Loader | Java | Dependency |
|---|---|---|---|---|
| `1.20.1` | 1.20.1 | Forge 47.x | Java 17 | TaCZ (1.20.1 Forge) |
| `1.21.1` | 1.21.1 | NeoForge 21.1.x | Java 21 | TaCZ (1.21.1 NeoForge) |

### Installation

1. Install **Forge (1.20.1)** or **NeoForge (1.21.1)** matching your Minecraft version.
2. Install the corresponding **TaCZ** mod jar into `.minecraft/mods/`.
3. Download the matching `gun_management-*.jar` from [Releases](https://github.com/Skydeps1459/Auto-TACZGunpack-Manager/releases) and place it in `.minecraft/mods/`.
4. Launch the game. (Install the mod on the server as well if you want multiplayer gunpack mismatch prompts.)

### Usage

1. **Open the Manager**
   - Click the **Gunpacks** button at the bottom-right of the **Title Screen** or **Pause Menu** (turns gold as **Gunpacks (N updates)** when updates are detected).
   - Or click **Config** on this mod in the Mods list.
2. **Manage Local Packs**
   - Disabled packs are on the left; enabled packs are on the right. Use the search box to filter by name or namespace.
   - Select a pack and click `->` / `<-` (or **double-click** it) to toggle, then click **Apply & Reload**.
   - Note: Disabling/unloading gunpacks is blocked while in-world; return to the main menu first to disable packs.
3. **Check & Apply Gunpack Updates**
   - Click **Check Updates** in the Local Packs tab to manually check Modrinth for newer versions.
   - Packs with updates show an `[Update -> version]` badge, and the button becomes **Update All (N)** for one-click updating and reloading (updating enabled packs requires being on the main menu).
4. **Online Browser**
   - Switch to the **Online Browser** tab and toggle between **Modrinth** and **GitHub** (with mirror selection for GitHub).
   - Press **Enter** or click **Search**, select a pack to inspect details, and click **Install Pack**.
5. **Admin Commands (OP required)**
   - `/gunmanager list`: List all detected gunpacks and their states
   - `/gunmanager enable <namespace>`: Enable a gunpack
   - `/gunmanager reload`: Trigger a TaCZ gunpack reload

### Documentation

- For technical design and implementation details, see [docs/TECH_DESIGN.md](docs/TECH_DESIGN.md).
