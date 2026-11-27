# 架构

想改代码前先看这一页。改动落在错误的层，后面几乎必然会返工。

---

## 1. 分层与依赖方向

```
        ┌──────────────────────────────────────────────┐
        │  command/TaczPackCommand    client/StarterScreen │   输入输出
        │  （解析参数、拼文本）        （画界面、轮询状态）  │
        └───────────────────────┬──────────────────────┘
                                │ 只调用，不反向依赖
        ┌───────────────────────▼──────────────────────┐
        │  service/    starter/                         │   用例编排
        │  GunpackService  UpdateChecker  UpdateApplier │
        └───────────────────────┬──────────────────────┘
                                │
        ┌───────────────────────▼──────────────────────┐
        │  scan/        remote/                         │   能力实现
        │  GunpackScanner   HttpFetcher                 │
        │  GunpackIndex     JsonManifestSource          │
        │  PackContent      GunpackDownloader           │
        │  MetadataReader   ProxySupport                │
        └───────────────────────┬──────────────────────┘
                                │
        ┌───────────────────────▼──────────────────────┐
        │  model/     config/                           │   纯数据 / 纯配置
        │  Gunpack  InstalledPack  RemotePack  Versions │
        └──────────────────────────────────────────────┘
```

**依赖只能向下。** 一个具体的检查方法：如果 `model/` 里出现
`import net.minecraft.*`，说明分层破了，测试会立刻跑不起来。

### 这条规则换来了什么

| 好处 | 具体表现 |
|---|---|
| 不启动游戏就能测 | 72 个测试全是普通 JUnit，`./gradlew test` 十几秒跑完 |
| 加 GUI 不用重写逻辑 | `GunpackService` 的每个公开方法对应一个用户动作，命令和界面都只是调用方 |
| 换 HTTP 栈只改一处 | 全部网络细节收在 `HttpFetcher` |
| TACZ 版本升级只改一处 | 包内部布局只有 `GunpackMetadataReader` 知道 |

### 三层各管什么

- **`model`** —— 不可变记录。`Gunpack`（包内声明的信息）、`InstalledPack`
  （磁盘上的状态 + 包信息）、`RemotePack`（远程发布记录）、`Versions`（版本比较）。
  没有行为，没有 IO。
- **`scan` / `remote`** —— 能力层，可以互相不认识。`scan` 只管本地文件，
  `remote` 只管网络，两者产出都归一化成对方能懂的模型。
- **`service` / `starter`** —— 编排层。`service` 服务命令入口，
  `starter` 服务启动界面。**两者刻意不合并**，因为失败策略完全不同（见 §4）。
- **`command` / `client`** —— 只管把用户意图转成调用、把结果转成文字或像素。

---

## 2. 两条主链路

### 启动前更新检查

```
TitleScreen 初始化完成
      │  ScreenEvent.Init.Post（只处理本次进程第一次）
      ▼
StarterConfig.load(config/autotacz-starter.json)
      │  enabled == false → 直接放行
      ▼
Minecraft.setScreen(new StarterScreen(...))   ← 先挂界面，再发请求
      │
      ├─► UpdateChecker.startAsync()   后台线程
      │       │
      │       ├─ SCANNING  本地扫描 tacz/（不算哈希，快）
      │       ├─ FETCHING  主清单 → 失败则依次试备用清单
      │       │             比对 Versions.isNewer(远程, 本地)
      │       ├─ DONE        有候选 → 界面列出，默认全选
      │       ├─ NETWORK_ERROR           → 按钮变「跳过并进入游戏」
      │       └─ FAILED（意外异常）      → 同上
      │
      └─► StarterScreen 每 tick 轮询 snapshot()，不阻塞

用户点「更新选中」
      │
      ▼
UpdateApplier.applyAll(candidates, progress)
      │  逐条：下载 .part → 校验 SHA-256 → 备份旧包 → 原子替换 → 失败回滚
      │  单条失败不影响其余；最后统一汇报
      ▼
点「进入游戏」 → 关闭界面
```

关键约束：**没有任何路径会把玩家卡住**。所有失败都只是把按钮文案换成
「跳过并进入游戏」。这条是需求原话，也是设计的第一优先级。

`StarterScreenHook` 用两个静态标志控制弹窗次数：

- `handledThisLaunch` —— 本次进程只处理一次，`setScreen` 不会被重复触发
- `worldJoined` —— 由 `ClientPlayerNetworkEvent.LoggingIn` 置位；
  进过世界后回主菜单不再弹窗

> 为什么不用 `minecraft.level != null` 判断？退回主菜单时 `level` 已被清空，
> 那时无法区分「首次进入主菜单」和「从世界退回主菜单」——两个标志是唯一可靠的办法。

### 游戏内命令

```
/taczpack ...
      │  TaczPackCommand 只解析参数 + 拼反馈文本
      ▼
GunpackService（唯一有状态的服务，持有当前 GunpackIndex 引用）
      │
      ├─► GunpackScanner.scan()      本地扫描
      ├─► PackQuery                  在索引上做筛选 / 搜索
      ├─► GunpackRemoteSource.load() 远程清单
      ├─► GunpackDownloader          下载
      └─► PackExporter / PackConverter  导出
```

`GunpackIndex` 本身不可变，`rescan()` 时**整体替换**引用。
这样不存在「扫描到一半别人在读」的中间态，也省掉全套锁。

---

## 3. 各包职责与关键决策

### `model` —— 不可变数据

`Versions` 是唯一有逻辑的类。它不实现完整语义化版本，只解决一个真实问题：

```
TACZ 枪包版本号是自由文本：1.0.0 / v1.2 / 1.13.0-Release / 1.0.0-beta
```

解析规则：去掉 `v` 前缀 → 取开头连续数字段作为主版本 → 剩余文本只用于判定
是否预发布（`beta` / `rc` / `alpha` / `pre` 等关键词）。

**为什么 `-Release` 不参与比较**：MW4 真实用 `1.13.0-Release`，别的包可能写
`1.13.0`。若按「文本段 > 数字段」一刀切，两者被判为不同版本，用户每次启动
都被提示更新——这是最糟糕的体验。所以只有明确的预发布词才降级，其余后缀忽略。

这一条有专门的回归测试（曾实测 `1.13.0-Release` vs `1.13.0` 返回 `-1`，已修）。

### `scan` —— 本地发现

**`PackContent` 是这层最重要的抽象。** 目录形态和 zip 形态的枪包内部结构
完全一致，差异只是「怎么按名字拿到流」。抽象掉这一层之后，
`GunpackMetadataReader` 只需写一份解析逻辑：

```java
public interface PackContent extends Closeable {
    InputStream open(String relativePath);   // 不存在返回 null
    boolean exists(String relativePath);
    int countJsonUnder(String prefix);
    List<String> listJsonNamesUnder(String prefix);
}
```

两个实现：`DirectoryPackContent`（`Files.newInputStream` 相对解析）、
`ZipPackContent`（`ZipFile` 条目名查找）。

`GunpackScanner` 只负责**发现**：遍历、判断形态、交给 reader、组装 `InstalledPack`。
不做缓存、不做搜索、不移动文件，因此可以被反复调用而无副作用。

扫描是容错的：单个包损坏只进 `ScanResult.failures()`，不影响其余包。
结果按 namespace 排序，保证多次扫描输出稳定（便于 diff 和测试）。

**禁用机制**：移入 `tacz/.disabled/`。前缀 `.` 让 TACZ 原生扫描器忽略该目录，
因此对所有 TACZ 版本都有效；同时本模组仍能扫到它，便于一键恢复。
选「移动文件」而不是 TACZ 的配置项，就是因为它无版本依赖。

### `remote` —— 网络

四个类的分工，**不要混**：

| 类 | 只做这件事 |
|---|---|
| `ProxySupport` | 探测代理（环境变量 → `ProxySelector` → 直连），回环永远直连 |
| `HttpFetcher` | 超时 / UA / 重定向 / 大小上限，统一入口 |
| `JsonManifestSource` | 把 JSON 解析成 `List<RemotePack>` |
| `GunpackDownloader` | 下载到 `.part` → 校验 → 原子改名 |

**只下不管换**：`GunpackDownloader` 只负责把文件下载到目标目录，
不碰「替换已安装枪包」。已安装包可能是目录形态、需要备份回滚，
那套逻辑在 `UpdateApplier`。分成两件事才能各自测试。

#### `HttpFetcher` 的两个坑

**重定向必须 `NORMAL` 不能 `ALWAYS`。** GitHub Release 的下载地址返回 302 跳到
`release-assets.githubusercontent.com`（跨域），不跟随就永远拿不到资产。
但 `ALWAYS` 会跟随 https→http 的降级跳转，等于允许中间人换掉下载内容。

**两种超时不是一回事。** `connectTimeout = 15s`（建连）；`requestTimeout` 是单次
请求上限，元数据用 20 秒。下载走 `openStream` 单独设 10 分钟——
86 MB 的枪包在慢速网络上远超 20 秒，用元数据超时必然失败。

#### `GunpackDownloader` 的写入顺序

```
tacz/foo.zip.part   ← 16 KiB 缓冲流式写入，同时累计字节数 + 算 SHA-256
      │
      ├─ 清单给了 sha256 → 比对，不符则删 .part 并抛 ChecksumMismatchException
      └─ 通过 → 删旧 tacz/foo.zip → 原子改名 .part → foo.zip
```

先写临时文件再改名，保证下载中断不会在枪包目录留下半个 zip。
TACZ 扫到损坏 zip 会报错，这种脏状态很难让玩家自己清掉。

`verifyChecksum` 在清单未提供摘要时**放行**而不是拒绝——摘要可选，
不能因此让所有没填摘要的包都装不上。

### `service` —— 命令侧编排

`GunpackService` 是**唯一有状态的服务**（持有当前索引）。

设计原则：**每个公开方法对应一个用户可见动作**（扫描 / 启用 / 禁用 / 更新 / 安装 /
导出）。命令入口只解析参数并调用它，因此业务逻辑不散落在命令类里，
未来加 GUI 可以原样复用。

### `starter` —— 启动界面侧编排

`UpdateChecker` 与 `UpdateApplier` 服务启动窗口场景，和 `service` 分开是因为需求不同：

| | `service`（命令） | `starter`（启动窗口） |
|---|---|---|
| 失败处理 | 抛异常，命令里 catch 后回显 | 收敛成状态码，绝不抛给调用方 |
| 进度反馈 | 无（命令是一次性输出） | 必须持续汇报 |
| 批量操作 | 不涉及 | 单条失败不影响其余，最后统一汇报 |
| 整体重扫描 | 做完就 `rescan()` | 不做，避免界面卡顿 |

`UpdateChecker` 的状态机：

```
IDLE → SCANNING → FETCHING → DONE
                     ↓
          NETWORK_ERROR / FAILED   （均可跳过）
```

全部字段 `volatile`，界面在渲染线程轮询 `snapshot()`，检查在后台线程跑。

**`fallbackManifests` 为什么是实例字段而非直接读常量**：为了让测试能传空列表
把网络完全隔离。之前用常量时，本地因代理干扰全失败而通过，CI 能联网就真的
连上了备用地址，测出来是 `DONE` 而非期望的 `NETWORK_ERROR`——3 个用例在 CI 红。

`UpdateApplier` 的安全顺序：

```
下载到 .part（由 Downloader 保证）
  → 校验通过
  → 旧文件改名 .bak（备份）
  → 移入新文件
  → 失败则从 .bak 回滚
```

目录形态的枪包很常见，而对非空目录用 `REPLACE_EXISTING` 会抛
`DirectoryNotEmptyException`，因此统一按「先清空目标再移动」处理。

### `config` —— 为什么不走 ForgeConfigSpec

启动检查在客户端启动**极早期**就要读配置，而 `ModConfigEvent` 的加载时序依赖
模组加载顺序，容易出现「配置还没加载完就被读」。一份独立 JSON
（`config/autotacz-starter.json`）读取时机完全可控，也便于用户手工编辑。

`Dto` 用可空包装类型（`Boolean` / `Integer`）而不是基本类型，是为了区分
「字段未设置」和「设为 false」，从而让默认值（`enabled=true`）真正生效。
`timeoutSeconds` 会夹到 `[2, 60]`，避免用户填 0 或 9999 造成卡死或误判。

配置读取失败只记日志并用默认值——配置坏了不该妨碍启动。

### `client` 与 `command`

`StarterScreen` 377 行，负责渲染列表、勾选框、按钮状态机、进度显示。
`TaczPackCommand` 441 行，12 个子命令，全部走 Brigadier 注册。

两者都不含业务判断——判断在 `service` / `starter`。

---

## 4. 扩展点

### 新增一个远程源

实现 `GunpackRemoteSource`，只要一个方法：

```java
public interface GunpackRemoteSource {
    String name();
    List<RemotePack> search(String keyword) throws IOException;
    Optional<RemotePack> findByNamespace(String namespace) throws IOException;
}
```

四步清单：

1. 产出必须归一化成 `RemotePack`，不要把自己的 JSON 结构泄给上层
2. `sourceName` 写进每个 `RemotePack`，多源共存时靠日志区分
3. 网络异常抛 `IOException`，让上层决定是回退还是报错
4. 单条坏数据跳过并记日志，别让整份清单失败

### 新增一个游戏内子命令

在 `TaczPackCommand` 里加 `literal(...)`，业务逻辑放进 `GunpackService` 的新方法。
不要在命令类里直接读文件或发 HTTP。

### 改枪包内部格式解析

只改 `GunpackMetadataReader`。它被刻意设计成**全项目唯一**了解 TACZ 包
内部布局的地方——TACZ 升级改格式时，改动范围就是这一个文件。

---

## 5. 已知取舍

| 取舍 | 现状 | 原因 |
|---|---|---|
| 版本比较不是完整 semver | 主版本数字 + 预发布关键词 | 真实数据是自由文本，完整 semver 反而误判 |
| 清单无签名 | 只靠 SHA-256 | 摘要来自同一份清单，防不住清单本身被篡改；要更强保证得上签名 |
| 索引全内存 | 每次启动重扫 | 枪包数量在几十量级，缓存收益不抵复杂度 |
| 扫描算哈希可选 | 默认关，比对更新时才开 | 大包哈希是主要开销 |
| 不做并发下载 | 逐条顺序 | 单条 86 MB，并发收益小且更易触发限流 |
| 禁用靠移动文件 | `tacz/.disabled/` | 对所有 TACZ 版本有效，比配置项可靠 |

### 远程源的安全性说明

当前信任模型：清单通过 HTTPS 获取（jsDelivr 或 GitHub raw），
下载的包用清单里的 SHA-256 校验。这能防传输损坏和 CDN 返回错误内容，
**防不住清单本身被替换**——那时摘要和文件会一起被换掉。

真要更强的保证需要给清单加签名（维护者私钥签，模组内置公钥验）。
目前没做，是因为清单托管在 GitHub 仓库、有提交历史可追溯，
威胁模型下收益不明显。若将来改为第三方镜像，这一条要重新评估。