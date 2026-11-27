# TaCZ Gunpack Manager 技术开发方案（1.21.1 NeoForge）

> 关联仓库：`https://github.com/Skydeps1459/Auto-TACZGunpack-Manager`（分支 `1.21.1`）  
> 参考源码：`https://github.com/MCModderAnchor/TACZ`（分支 `1.21.1`）  
> 开发者 Wiki：`mcma.club`

---

## 一、 架构定位与环境搭建

* **定位**：纯客户端优先（Client-Side First）辅助 Mod，兼顾多人联机校验（当服务端也安装本 Mod 时自动激活联机同步）。
* **开发环境**：
  * **MC 版本与加载器**：Minecraft `1.21.1` + NeoForge `21.1.268`（ModDevGradle `2.0.148`），Java `21`。
  * **依赖引入**：在 `build.gradle` 中引入 TaCZ 1.21.1 NeoForge 构件（本地 `libs/` 或 Maven/CurseMaven），并结合 `MCModderAnchor/TACZ` `1.21.1` 分支源码对照 `com.tacz.guns.resource` 与命令重载逻辑。

---

## 二、 核心功能模块拆解

### 1. 本地枪包文件系统与状态机（Local Pack Manager）

TaCZ 默认扫描 `.minecraft/tacz/` 下的 `.zip` 压缩包和未解压文件夹。管理器的核心是在不破坏原文件结构的前提下实现“启用/禁用”与元数据解析：

* **目录规范**：
  * 启用目录：`<gameDir>/tacz/`
  * 禁用隔离区：`<gameDir>/tacz/.disabled/`（前缀加 `.` 防止 TaCZ 原生扫描器误读）
* **元数据扫描器（`GunpackScanner`）**：
  * 异步遍历两个目录，使用 `ZipFile`（针对 `.zip`）或 `Files.walk`（针对文件夹）读取内部的 `gunpack.meta.json` 以及 `data/<namespace>/index/guns/` 等索引 JSON。
  * 提取关键信息并缓存：`pack_id`、名称、版本、作者、简介、图标（`icon.png` 注册为 `DynamicTexture`）、包含的枪械/配件/弹药数量统计、文件 SHA-256 哈希值。
* **安全启停与热重载**：
  * 点击“启用/禁用”时，调用 Java NIO 的 `Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)`。
  * **Windows 文件锁兜底**：若捕获 `FileSystemException`（音频或模型流未释放），先触发 TaCZ 内部资源卸载方法；若仍被锁定，则标记状态为 `PENDING_RESTART`（重启后自动完成移动）。
  * 状态变更后，直接调用 TaCZ 源码中 `/tacz reload` 对应的底层重载入口，无需模拟玩家发送聊天指令。

### 2. 在线枪包浏览器与一键安装（Modrinth Integrator）

* **数据源**：直接对接 **Modrinth REST API v2**，免鉴权且支持精确筛选。
* **检索请求**：
  ```text
  GET https://api.modrinth.com/v2/search?query=tacz&facets=[["versions:1.21.1"]]&limit=20
  ```
* **下载安装管线（`DownloadManager`）**：
  1. 选中枪包后请求 `/v2/project/{id}/version`，过滤出匹配当前游戏版本的最新构建，获取 `files[0].url` 与 `hashes.sha256`。
  2. 使用 `java.net.http.HttpClient` 异步下载至 `<gameDir>/tacz/.tmp/`，实时更新进度条字节数。
  3. 下载完成后校验 SHA-256，校验通过后原子性移动至 `<gameDir>/tacz/` 并触发热重载。

### 3. 图形界面设计（GUI Architecture）

* **入口挂载**：监听 `ScreenEvent.Init.Post`，在原版主菜单（`TitleScreen`）和暂停菜单（`PauseScreen`）的右下角或 TaCZ 配置按钮旁注入图标按钮。
* **主界面（`GunpackManagerScreen` - 仿原生资源包双栏布局 + 顶部分页）**：
  * **Tab 1：本地管理（左右双栏）** — 左侧为“已禁用枪包”，右侧为“已启用枪包”，支持搜索过滤、点击箭头切换状态、悬停显示枪械列表详情，底部提供“应用并重载（Apply & Reload）”与“打开文件夹”按钮。
  * **Tab 2：在线获取（瀑布流列表 + 侧边详情抽屉）** — 展示封面图、下载量、更新时间、作者及“一键安装/已安装”状态标签。

### 4. 多人联机枪包一致性校验（可选进阶模块）

当客户端和服务端同时安装了本 Mod 时激活：

* **握手协议**：玩家登录服务器时，服务端通过 NeoForge 1.21.1 `PayloadRegistrar`（`CustomPacketPayload`）向客户端下发当前服务器启用枪包的清单（`Map<PackId, SHA256>`）。
* **差异比对与引导**：客户端对比本地清单后，若发现缺失或不匹配，自动弹出提示框列出缺失枪包名称，并提供“从 Modrinth 一键补全缺失枪包”按钮。

---

### 5. 枪包更新检测与 GitHub 镜像加速（v1.0.3 新增）

* **更新检测（`GunpackUpdateChecker`）**：
  * 游戏启动进入主菜单时后台执行一次（或在管理界面手动点击「检查更新」），收集本地 `.zip` 枪包的 SHA-256 摘要。
  * 调用 Modrinth `POST /v2/version_files` 批量匹配所属 `project_id`，再拉取最新兼容 `.zip` 版本，对比版本 ID 与哈希。
  * 若存在更新，在主菜单/暂停菜单按钮上高亮显示更新数量，并在本地列表条目标注 `[可更新 -> 版本号]`，支持「一键更新」自动下载替换并清理旧包后重载 TaCZ。
* **GitHub 镜像与构建**：
  * 支持检索 `topic:tacz` 仓库与 Release `.zip` 资产，内置 `ghfast.top`、`gh-proxy.com`、`ghproxy.net` 加速节点切换。
  * **1.20.1 Forge**（Java 17）与 **1.21.1 NeoForge**（Java 21）双端同步维护，编译命令：`./gradlew build`。

---

## 三、 工程包结构规划

```text
com.liokasanovich.gun_management
├── GunManagement.java                  // Mod 主入口、配置与网络注册
├── GunManagementClient.java            // 客户端入口
├── client
│   ├── ClientEvents.java               // 监听 ScreenEvent 注入主菜单/暂停菜单按钮
│   ├── gui
│   │   ├── GunpackManagerScreen.java   // 主管理界面（Tab 切换）
│   │   ├── widget/GunpackListWidget.java // 滚动列表控件（渲染图标、名称、状态）
│   │   └── SyncConfirmScreen.java      // 联机缺失枪包提示弹窗
│   └── texture/PackIconCache.java      // 异步加载本地 zip 内图标与远程封面图
├── core
│   ├── GunpackInfo.java                // 枪包数据模型（元数据、路径、哈希、统计）
│   ├── GunpackScanner.java             // 异步扫描与 JSON 解析器
│   ├── GunpackOperator.java            // 启用/禁用移动文件、调用 TaCZ 重载入口
│   └── ModrinthClient.java             // Modrinth API 检索与异步下载器
└── network
    └── PacketSyncGunpacks.java         // 服务端->客户端枪包哈希清单同步包
```

---

## 四、 分阶段开发路线图

1. **阶段一（核心闭环）**：对接 `MCModderAnchor/TACZ` 1.21.1 依赖 -> 实现 `GunpackInfo` / `GunpackScanner` 读取本地 `.zip` 与文件夹 -> 实现 `GunpackOperator` 移动文件至 `.disabled`（含 `PENDING_RESTART` 兜底）并调用 TaCZ 重载入口。
2. **阶段二（GUI 完善）**：完成 `GunpackManagerScreen` 双栏列表、图标纹理缓存与按钮入口挂载，实现完整的本地可视化管理。
3. **阶段三（在线生态）**：接入 `ModrinthClient`，实现游戏内搜索、封面加载、异步下载与 SHA-256 哈希校验。
4. **阶段四（联机扩展）**：加入 `PacketSyncGunpacks` 发包校验，解决多人服务器进服因枪包不一致导致的枪械隐形或报错问题。
