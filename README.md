# Auto-TACZGunpack-Manager

[简体中文](#简体中文) | [English](#english)

---

## 简体中文

批量管理 / 搜索 / 下载 TACZ 枪包

面向 Minecraft **1.20.1 Forge** 的 TACZ（Timeless and Classics Zero，永恒枪械工坊：零）
辅助模组：启动游戏前自动检查枪包更新，进游戏后用一条命令批量管理所有已装枪包。

---

### 功能

- **启动前更新检查** —— 进主菜单前弹窗提示哪些枪包有新版本，勾选即更新；网络不通可直接跳过
- **批量扫描与索引** —— 一次列出 `tacz/` 下所有枪包（目录形态和 `.zip` 都支持）
- **搜索与筛选** —— 按名称 / 作者 / 武器类型 / 简介搜索，按类型统计分布
- **批量启用 / 禁用** —— 一键切换，不需要手动搬文件
- **批量导出** —— 按 `copy` / `zip` / `dir` 三种方式导出到任意目录
- **远程安装与更新** —— 从远程清单下载、校验 SHA-256、原子替换

所有功能入口都是游戏内命令 `/taczpack`，没有额外 GUI 需要学习。

---

### 前置要求

| 项 | 要求 |
|---|---|
| Minecraft | **1.20.1** |
| Forge | 47.x（开发与测试环境为 47.4.29） |
| Java | **17** |
| TACZ 本体 | **必需**，版本 `1.1.0` 或更高 |

#### 关于 TACZ 版本号

依赖声明里的 `1.1.0` 指的是 **TACZ jar 的 `Implementation-Version`**（形如 `1.1.8`），
**不是**文件名里的 `1.20.1-1.1.8`。

实测 `1.1.4` / `1.1.6` / `1.1.8` / `1.2.0` 都满足要求。

**TACZ 是硬性前置**（`mandatory = true`）。本模组管理的对象就是 `tacz/` 目录下的枪包，
没有 TACZ 这些文件不会被游戏读取，功能无从谈起。所以不是「装了更好」——
缺少 TACZ 时 Forge 会直接拒绝启动，并提示：

```
Mod autotaczgunpackmanager requires tacz 1.1.0 or above
Currently, tacz is not installed
```

装好 TACZ 再装本模组即可。

---

### 安装

#### 1. 装 Forge

用官方安装器给 Minecraft 1.20.1 装 Forge 47.x。装完启动一次游戏，
确认 `.minecraft/mods/` 目录已生成。

#### 2. 装 TACZ

从 [TACZ 的 Modrinth 页面](https://modrinth.com/mod/timeless-and-classics-zero/versions)
或 [CurseForge](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero)
下载 **1.20.1 Forge** 版本的 jar，放进 `mods/`。

实际文件名长这样（两种命名都出现过）：

```
tacz-forge-1.20.1-1.2.0.jar
tacz-1.20.1-1.1.8-release.jar
```

> 认准文件名里的 **`1.20.1`** 和 **`forge`**。别下成 NeoForge 版或
> `-sources.jar`（那是源码，游戏不认）。下错版本的典型症状是游戏直接崩溃。

#### 3. 装本模组

把 `autotaczgunpackmanager-0.1.0.jar` 放进同一个 `mods/` 目录：

```
.minecraft/
  mods/
    tacz-forge-1.20.1-1.2.0.jar
    autotaczgunpackmanager-0.1.0.jar
```

> **当前 0.1.0 尚未发布正式 Release**（历史 Release 里的
> `gun_management-*.jar` 是旧实现，不适用）。
> 目前获取方式：从
> [Actions](https://github.com/Skydeps1459/Auto-TACZGunpack-Manager/actions)
> 页面下载最新构建的 artifact，
> 或按 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 自行构建
> （`dev.bat build`，产物在 `build/libs/`）。

#### 4. 启动游戏

首次启动会自动生成配置文件，并在进入主菜单前弹出更新检查窗口。

服务端 / 整合包同理：jar 放进服务端 `mods/` 即可，无需客户端装 GUI 相关东西
（界面部分只在客户端生效）。

#### 卸载

删掉 `mods/autotaczgunpackmanager-0.1.0.jar` 就行。
配置和已下载的枪包都不动，重新装上还是原来的状态。

---

### 使用

#### 启动前更新检查

进主菜单前自动弹出窗口：

- 列出可更新的枪包（`旧版本 → 新版本`），**默认全部勾选**，可逐条取消
- 点「更新选中」开始下载，完成后点「进入游戏」
- **网络异常时按钮直接变成「跳过并进入游戏」**，绝不会把玩家卡在启动界面
- 检查在后台线程跑，界面不卡
- 按 ESC 不会关闭窗口（避免误跳过）
- 进过世界再退回主菜单**不会**重复弹窗

#### 配置文件

`config/autotacz-starter.json`，首次启动自动生成：

```json
{
  "enabled": true,
  "manifestUrl": "https://cdn.jsdelivr.net/gh/Skydeps1459/Auto-TACZGunpack-Manager@main/packs.json",
  "targetPatterns": ["mw4"],
  "timeoutSeconds": 8,
  "autoContinue": true
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否启用启动检查。设 `false` 就完全不弹窗 |
| `manifestUrl` | jsDelivr 地址 | 远程清单地址，可换成自建镜像 |
| `targetPatterns` | `["mw4"]` | 要检查哪些枪包。匹配 namespace / 名称 / 文件名，忽略大小写；`["*"]` 表示全部 |
| `timeoutSeconds` | `8` | 单个请求超时，会被夹到 2–60 秒。超时即判定网络异常并允许跳过 |
| `autoContinue` | `true` | 没有更新时是否自动进入游戏（不弹空窗口） |

配置坏了不影响启动——读不出来就用默认值，只在日志里留一条警告。

#### 游戏内命令

进游戏后（或服务端控制台）输入 `/taczpack`：

```
/taczpack                              显示帮助
/taczpack list [关键字]                 列出已安装枪包
/taczpack search <关键字>               按名称/作者/类型/简介搜索
/taczpack type [类型]                   查看武器类型分布 / 按类型筛选
/taczpack info <namespace>              查看枪包详情
/taczpack enable|disable <namespace>    启用或禁用枪包
/taczpack export <目录> [copy|zip|dir]  批量导出已启用的枪包
/taczpack check [清单地址]               检查远程更新
/taczpack update <namespace> [清单]      下载并更新单个枪包
/taczpack install <namespace> [清单]     安装远程枪包
/taczpack rescan                        重新扫描本地枪包
```

示例：

```
/taczpack type rifle
/taczpack export "D:/我的枪包备份" zip
/taczpack check https://example.com/packs.json
```

命令对权限等级的要求是 0，**单人游戏和普通玩家也能直接用**；
在多人服务器上管理员同样可用。

> 服务端控制台也能执行同样的命令（例如从 `taczpack list` 开始试）。
> 注意服务端只处理文件层面的操作，`/taczpack export` 导出的目标路径是**服务端**的磁盘。

---

### 枪包目录约定

```
<游戏目录>/tacz/               启用的枪包（目录或 .zip，TACZ 原生布局）
<游戏目录>/tacz/.disabled/     禁用的枪包
```

禁用就是**把文件移到 `.disabled/`**。前缀 `.` 让 TACZ 原生扫描器忽略这个目录，
所以对任何 TACZ 版本都有效，不需要依赖 TACZ 的配置项。
禁用的包仍会被本模组扫到，随时可以 `/taczpack enable` 恢复。

> 别手动把枪包放到别的地方——TACZ 只认 `tacz/`，本模组也只看这里。

---

### 常见问题

**弹窗一直显示「网络异常」**

清单地址在国内可能连不上。模组会自动回退到 `raw.githubusercontent.com`，
两个都不通才会判定异常。如果你用了加速器 / 代理：

- 模组**会自动识别**环境变量 `HTTPS_PROXY` / `HTTP_PROXY` / `ALL_PROXY`，
  以及 JVM 参数 `-Dhttps.proxyHost=...`、系统的 `ProxySelector`
- 支持 `http://` / `https://` / `socks5://` / `socks5h://`，可带账号密码
- `localhost` / `127.0.0.1` / `::1` **永远直连**（本地镜像不会被代理带偏）

实在连不上就让它跳过，游戏照常启动；或者干脆把 `enabled` 改成 `false`。

**不想每次启动都检查**

改 `config/autotacz-starter.json` 里 `"enabled": false`。

**启动时游戏报缺 TACZ**

先装 TACZ 本体（见上文）。注意版本要 ≥ `1.1.0`，且必须是 **1.20.1 Forge** 版。

**更新完枪包，游戏里看不到新枪**

退出存档重新进，或执行 `/taczpack rescan` 后重进世界——
TACZ 在资源加载阶段读枪包，运行中替换文件不会立刻生效。

**能不能用自建清单**

可以。`manifestUrl` 指向任意静态 JSON 就行，格式见下节。
游戏内 `/taczpack check <地址>` 也能临时指定。

---

### 远程清单格式

`check` / `update` / `install` 和启动检查**共用同一份**静态 JSON 清单。
默认地址走 **jsDelivr CDN**：

```
https://cdn.jsdelivr.net/gh/Skydeps1459/Auto-TACZGunpack-Manager@main/packs.json
```

选 CDN 而不是 raw 的原因很实际：`raw.githubusercontent.com` 的若干 IP
（185.199.108/109.133）在国内 443 端口会被阻断，DNS 能解析但连不上；
jsDelivr 有国内节点。raw 作为备用地址保留，主地址失败时自动回退。

清单是静态文件——不需要 API 鉴权、不会被限流，任何人都能自建镜像：

```json
{
  "packs": [
    {
      "namespace": "mw4",
      "name": "Modern Warfare 4",
      "version": "1.13.0-Release",
      "url": "https://github.com/Skydeps1459/Modern-Warfare-4-TACZ-GunPack/releases/download/v1.13.0-Release/Modern.Warfare.4-1.13.0-Release.zip",
      "page": "https://github.com/Skydeps1459/Modern-Warfare-4-TACZ-GunPack/releases/latest",
      "size": 86812599,
      "sha256": "d58a340892f73a604cc973af873acaf3cad244d3ad1f91b6957284d750aad7f1"
    }
  ]
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `namespace` | ✅ | 必须与包内 `gunpack.meta.json` 声明的一致 |
| `url` | ✅ | 直链。GitHub Release 的 302 跳转会**自动跟随** |
| `name` | | 显示名，缺省用 namespace |
| `version` | | 缺省视为空串（不会触发更新提示） |
| `page` | | 详情页，仅供界面跳转 |
| `size` | | 字节数，未知写 `-1` |
| `sha256` | | 提供了就**强制校验**，不匹配则拒绝安装 |

缺 `namespace` 或 `url` 的条目会被整条跳过，不影响清单里其他条目。
`_` 开头的键（如 `_readme`）视为注释忽略。清单允许 `//` 行注释。

仓库根目录的 `packs.json` 就是当前线上使用的清单——
修改后推送到 `main` 即生效（jsDelivr 有几分钟缓存，不必重新发版）。

---

### 版本与兼容

| 项 | 值 |
|---|---|
| 本模组版本 | `0.1.0` |
| modId | `autotaczgunpackmanager` |
| MC 范围 | `[1.20.1,1.21)` |
| Forge 范围 | `[47,)` |
| TACZ 范围 | `[1.1.0,)` |

1.21.1 / NeoForge 版本尚未实现。

---

### 开发

想改代码、编译、调界面、跑测试，看 [`docs/`](docs/index.md)：

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) —— 环境搭建、`dev.bat`、构建、测试、本地清单服务、CI
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) —— 分层结构、数据流、设计取舍

一句话：Java 17 + ForgeGradle 6，纯命令行开发，`dev.bat build` 出 jar，
72 个测试，全部不启动 Minecraft 即可运行。

---

## English

Batch manage / search / download TACZ gunpacks

A utility mod for Minecraft **1.20.1 Forge** and TACZ (Timeless and Classics Zero):
automatically checks for gunpack updates before entering the main menu, and lets you batch-manage all installed gunpacks in-game with a single command.

---

### Features

- **Pre-launch Update Check** — Shows a popup before the main menu listing gunpacks with newer versions; check to update, or skip directly if offline
- **Batch Scan & Indexing** — Lists all gunpacks under `tacz/` at once (supports both folder and `.zip` formats)
- **Search & Filter** — Search by name / author / weapon type / description, and view statistics by weapon type
- **Batch Enable / Disable** — Toggle with one command without manually moving files
- **Batch Export** — Export enabled gunpacks to any directory via `copy` / `zip` / `dir` modes
- **Remote Install & Update** — Download from a remote manifest, verify SHA-256, and perform atomic replacement

All features are accessed through the in-game `/taczpack` command—no extra GUI to learn.

---

### Prerequisites

| Item | Requirement |
|---|---|
| Minecraft | **1.20.1** |
| Forge | 47.x (developed and tested on 47.4.29) |
| Java | **17** |
| TACZ Core | **Required**, version `1.1.0` or higher |

#### About the TACZ Version Number

The `1.1.0` in the dependency declaration refers to the **`Implementation-Version` of the TACZ jar** (e.g. `1.1.8`),
**not** the `1.20.1-1.1.8` part of the filename.

Tested versions `1.1.4` / `1.1.6` / `1.1.8` / `1.2.0` all satisfy this requirement.

**TACZ is a hard dependency** (`mandatory = true`). This mod manages gunpacks in the `tacz/` directory;
without TACZ, these files will not be loaded by the game and the mod has nothing to manage. So it is not merely "nice to have"—
if TACZ is missing, Forge will refuse to start and display:

```
Mod autotaczgunpackmanager requires tacz 1.1.0 or above
Currently, tacz is not installed
```

Install TACZ first, then install this mod.

---

### Installation

#### 1. Install Forge

Use the official installer to install Forge 47.x for Minecraft 1.20.1. Launch the game once after installation
to confirm the `.minecraft/mods/` directory is created.

#### 2. Install TACZ

Download the **1.20.1 Forge** version jar from
[TACZ on Modrinth](https://modrinth.com/mod/timeless-and-classics-zero/versions)
or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero), and place it in `mods/`.

The actual filename looks like one of these (both naming conventions exist):

```
tacz-forge-1.20.1-1.2.0.jar
tacz-1.20.1-1.1.8-release.jar
```

> Make sure the filename contains **`1.20.1`** and **`forge`**. Do not download the NeoForge build or
> `-sources.jar` (that is source code and the game will not load it). Downloading the wrong build typically causes an immediate crash.

#### 3. Install This Mod

Place `autotaczgunpackmanager-0.1.0.jar` into the same `mods/` directory:

```
.minecraft/
  mods/
    tacz-forge-1.20.1-1.2.0.jar
    autotaczgunpackmanager-0.1.0.jar
```

> **0.1.0 does not have an official Release yet** (`gun_management-*.jar` in historical Releases
> is an older implementation and does not apply).
> Current ways to obtain it: download the latest build artifact from the
> [Actions](https://github.com/Skydeps1459/Auto-TACZGunpack-Manager/actions)
> page, or build it yourself following [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
> (`dev.bat build`, output in `build/libs/`).

#### 4. Launch the Game

On first launch, a configuration file will be generated automatically, and an update check window will appear before entering the main menu.

The same applies to servers / modpacks: just put the jar into the server's `mods/` folder—no client-side GUI setup is needed
(the UI only runs on the client).

#### Uninstall

Simply delete `mods/autotaczgunpackmanager-0.1.0.jar`.
Your config and downloaded gunpacks remain untouched, so reinstalling restores the previous state.

---

### Usage

#### Pre-launch Update Check

Automatically pops up before entering the main menu:

- Lists updatable gunpacks (`old version → new version`), **all checked by default**, and can be unchecked individually
- Click "Update Selected" to start downloading, then click "Enter Game" when finished
- **If a network error occurs, the button turns into "Skip & Enter Game"**—it will never trap you on the startup screen
- Checks run on a background thread so the UI never freezes
- Pressing ESC will not close the window (to prevent accidental skipping)
- Returning to the main menu from a world will **not** trigger the popup again

#### Configuration File

`config/autotacz-starter.json`, generated automatically on first launch:

```json
{
  "enabled": true,
  "manifestUrl": "https://cdn.jsdelivr.net/gh/Skydeps1459/Auto-TACZGunpack-Manager@main/packs.json",
  "targetPatterns": ["mw4"],
  "timeoutSeconds": 8,
  "autoContinue": true
}
```

| Field | Default | Description |
|---|---|---|
| `enabled` | `true` | Whether to enable the startup check. Set to `false` to disable the popup completely |
| `manifestUrl` | jsDelivr URL | Remote manifest URL; can be replaced with a self-hosted mirror |
| `targetPatterns` | `["mw4"]` | Which gunpacks to check. Matches namespace / name / filename (case-insensitive); `["*"]` means all |
| `timeoutSeconds` | `8` | Per-request timeout, clamped to 2–60 seconds. Exceeding it is treated as a network error and allows skipping |
| `autoContinue` | `true` | Whether to automatically enter the game when no updates are available (avoids showing an empty window) |

A corrupted config will not block startup—if it fails to parse, defaults are used and a warning is logged.

#### In-Game Commands

Run `/taczpack` in-game (or from the server console):

```
/taczpack                              Show help
/taczpack list [keyword]               List installed gunpacks
/taczpack search <keyword>             Search by name/author/type/description
/taczpack type [type]                  View weapon type distribution / filter by type
/taczpack info <namespace>             View gunpack details
/taczpack enable|disable <namespace>   Enable or disable a gunpack
/taczpack export <dir> [copy|zip|dir]  Batch export enabled gunpacks
/taczpack check [manifestUrl]          Check for remote updates
/taczpack update <namespace> [manifest]  Download and update a single gunpack
/taczpack install <namespace> [manifest] Install a remote gunpack
/taczpack rescan                       Rescan local gunpacks
```

Examples:

```
/taczpack type rifle
/taczpack export "D:/MyGunpackBackup" zip
/taczpack check https://example.com/packs.json
```

The required permission level is 0, so **both singleplayer and regular players can use it directly**;
server administrators can also use it in multiplayer.

> The server console can execute the same commands (e.g. start by trying `taczpack list`).
> Note that the server only handles file-level operations, so the target path of `/taczpack export` is on the **server's** disk.

---

### Gunpack Directory Convention

```
<game_dir>/tacz/               Enabled gunpacks (folders or .zip, native TACZ layout)
<game_dir>/tacz/.disabled/     Disabled gunpacks
```

Disabling simply **moves the file into `.disabled/`**. The leading `.` causes TACZ's native scanner to ignore this directory,
so it works across all TACZ versions without relying on TACZ config options.
Disabled packs are still detected by this mod and can be restored anytime with `/taczpack enable`.

> Do not manually place gunpacks elsewhere—TACZ only recognizes `tacz/`, and this mod only scans there too.

---

### FAQ

**The popup always shows "Network Error"**

The manifest URL may be unreachable in mainland China. The mod automatically falls back to `raw.githubusercontent.com`,
and only reports an error if both fail. If you use a game booster / proxy:

- The mod **automatically detects** `HTTPS_PROXY` / `HTTP_PROXY` / `ALL_PROXY` environment variables,
  JVM arguments `-Dhttps.proxyHost=...`, and the system `ProxySelector`
- Supports `http://` / `https://` / `socks5://` / `socks5h://`, with optional username/password
- `localhost` / `127.0.0.1` / `::1` **always connect directly** (local mirrors won't be routed through the proxy)

If it still cannot connect, just skip it and the game will launch normally; or set `enabled` to `false`.

**I don't want to check on every launch**

Set `"enabled": false` in `config/autotacz-starter.json`.

**Missing TACZ error on startup**

Install TACZ first (see above). Make sure the version is ≥ `1.1.0` and it is the **1.20.1 Forge** build.

**Updated a gunpack, but new guns don't appear in-game**

Re-enter the world/save, or run `/taczpack rescan` and rejoin the world—
TACZ reads gunpacks during resource loading, so replacing files while running does not take effect immediately.

**Can I use a self-hosted manifest?**

Yes. Point `manifestUrl` to any static JSON file (see format below).
You can also specify one temporarily in-game with `/taczpack check <url>`.

---

### Remote Manifest Format

`check` / `update` / `install` and the startup check **share the same** static JSON manifest.
The default URL uses **jsDelivr CDN**:

```
https://cdn.jsdelivr.net/gh/Skydeps1459/Auto-TACZGunpack-Manager@main/packs.json
```

CDN is preferred over raw for practical reasons: several `raw.githubusercontent.com` IPs
(185.199.108/109.133) are blocked on port 443 in mainland China (DNS resolves, but connection fails),
whereas jsDelivr has nodes in China. `raw` is kept as a fallback and used automatically if the primary URL fails.

The manifest is a static file—no API authentication, no rate limits, anyone can host a mirror:

```json
{
  "packs": [
    {
      "namespace": "mw4",
      "name": "Modern Warfare 4",
      "version": "1.13.0-Release",
      "url": "https://github.com/Skydeps1459/Modern-Warfare-4-TACZ-GunPack/releases/download/v1.13.0-Release/Modern.Warfare.4-1.13.0-Release.zip",
      "page": "https://github.com/Skydeps1459/Modern-Warfare-4-TACZ-GunPack/releases/latest",
      "size": 86812599,
      "sha256": "d58a340892f73a604cc973af873acaf3cad244d3ad1f91b6957284d750aad7f1"
    }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `namespace` | ✅ | Must match the `gunpack.meta.json` inside the pack |
| `url` | ✅ | Direct download link. GitHub Release 302 redirects are **followed automatically** |
| `name` | | Display name; defaults to `namespace` if omitted |
| `version` | | Defaults to empty string if omitted (will not trigger update prompts) |
| `page` | | Details page URL, used for UI links only |
| `size` | | File size in bytes; use `-1` if unknown |
| `sha256` | | If provided, **strictly verified**; installation is rejected on mismatch |

Entries missing `namespace` or `url` are skipped without affecting other entries in the manifest.
Keys starting with `_` (such as `_readme`) are treated as comments and ignored. `//` line comments are also allowed in the manifest.

The `packs.json` in the repository root is the live manifest currently in use—
changes take effect as soon as they are pushed to `main` (jsDelivr caches for a few minutes; no new release is required).

---

### Version & Compatibility

| Item | Value |
|---|---|
| Mod Version | `0.1.0` |
| modId | `autotaczgunpackmanager` |
| MC Range | `[1.20.1,1.21)` |
| Forge Range | `[47,)` |
| TACZ Range | `[1.1.0,)` |

1.21.1 / NeoForge is not implemented yet.

---

### Development

To modify code, compile, tweak the UI, or run tests, see [`docs/`](docs/index.md):

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — Environment setup, `dev.bat`, building, testing, local manifest server, CI
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Layered architecture, data flow, design trade-offs

In short: Java 17 + ForgeGradle 6, pure CLI development, `dev.bat build` produces the jar,
and all 72 tests run without launching Minecraft.

---

## License

MIT
