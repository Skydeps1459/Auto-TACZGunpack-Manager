# Auto-TACZGunpack-Manager

批量管理 / 搜索 / 下载 TACZ 枪包

针对 Minecraft 的 TACZ（Timeless and Classics Zero，永恒枪械工坊：零）模组，提供枪包的批量处理能力。

## 目标功能

- 批量扫描、索引本地枪包
- 按名称 / 作者 / 武器类型搜索
- 从远程源下载并更新枪包
- 批量导出、转换

当前实现状态：以上四项均已可用，入口为游戏内命令 `/taczpack`。

## 开发环境

| 项 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.29 |
| Java | 17（Zulu OpenJDK） |
| Gradle | 8.14.3（wrapper 内置） |
| 构建插件 | ForgeGradle 6 |
| Mappings | official 1.20.1 |

## 快速开始

```bash
dev.bat build     # 构建
dev.bat client    # 启动开发客户端
dev.bat server    # 启动开发服务端
```

> 项目自带 `dev.bat`，会自动定位 JDK 17 并设置 `JAVA_HOME`
> （本机未设置该系统变量，直接跑 `gradlew` 会找不到 JDK）。
>
> 首次运行 `client` 会下载约 1GB 的 Minecraft 资源（199 个文件），
> 期间进度看起来像卡住，实际是在下载。之后启动只需十几秒。

## 使用

### 启动前更新检查

进入主菜单前会自动弹出一个窗口，检查 MW4 枪包是否有更新：

- 列出可更新的枪包（`旧版本 → 新版本`），默认全部勾选，可逐条取消
- 点「更新选中」下载并替换，完成后点「进入游戏」
- **网络异常时按钮变为「跳过并进入游戏」**，绝不会把玩家卡在启动界面
- 检查在后台线程进行，界面不卡顿；按 ESC 不会关闭窗口（避免误跳过）
- 玩家进过世界后再退回主菜单，不会重复弹窗

配置在 `config/autotacz-starter.json`（首次启动自动生成）：

```json
{
  "enabled": true,
  "manifestUrl": "https://raw.githubusercontent.com/.../packs.json",
  "targetPatterns": ["mw4"],
  "timeoutSeconds": 8,
  "autoContinue": true
}
```

| 字段 | 说明 |
|---|---|
| `enabled` | 是否启用启动检查 |
| `manifestUrl` | 远程清单地址 |
| `targetPatterns` | 要检查的枪包匹配模式，匹配 namespace / 名称 / 文件名，忽略大小写；`["*"]` 表示全部 |
| `timeoutSeconds` | 单个请求超时（2–60 秒），超时即判定为网络异常并允许跳过 |
| `autoContinue` | 无更新时是否自动进入游戏 |

### 游戏内命令

进入游戏后（或服务端控制台）使用 `/taczpack`：

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

### 枪包目录约定

```
<游戏目录>/tacz/               启用的枪包（目录或 .zip）
<游戏目录>/tacz/.disabled/     禁用的枪包
```

禁用通过移动文件实现，前缀 `.` 让 TACZ 原生扫描器忽略该目录，
因此对所有 TACZ 版本都有效，且禁用的包仍可被本模组扫描到以便一键恢复。

### 远程源格式

`check` / `update` / `install` 使用一份静态 JSON 清单。默认地址是
**jsDelivr CDN**：

```
https://cdn.jsdelivr.net/gh/Skydeps1459/Auto-TACZGunpack-Manager@main/packs.json
```

选它而不是 `raw.githubusercontent.com` 的原因很实际：raw 域名在国内经常
连不上（DNS 能解析，但 185.199.108/109.133 的 443 会被阻断），jsDelivr
带国内节点，稳定性明显更好。raw 作为备用地址保留，主地址失败时自动回退
（见 `StarterConfig#FALLBACK_MANIFESTS`）。

清单是静态文件，不需要 API 鉴权、不会被限流，也方便任何人自建镜像：

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

`namespace` 与 `url` 为必需字段；`sha256` 可选，提供后会强制校验。
清单允许 `//` 行注释。仓库根目录的 `packs.json` 就是当前线上使用的清单，
修改后推送到 `main` 分支即生效（jsDelivr 有几分钟缓存）。

启动前检查和游戏内命令共用同一份清单格式。

### 代理与网络

Java 的 `HttpClient` 缺省**既不读系统代理设置，也不读 `HTTP_PROXY` 环境变量**。
不做适配的话，用加速器或代理工具的玩家会每次都看到「网络异常」。
所以 `remote/ProxySupport` 会按以下顺序自动探测：

1. 环境变量 `HTTPS_PROXY` / `HTTP_PROXY` / `ALL_PROXY`（大小写均识别）
2. `ProxySelector.getDefault()`，覆盖「游戏内代理设置」与 `-Dhttp.proxyHost` 启动参数
3. 都没有则直连

支持 `http://`、`https://`、`socks5://`、`socks5h:` 形式，可带账号密码；
解析失败按「没配代理」处理，而不是让启动失败。

**本地回环永远直连**（`localhost` / `127.0.0.1` / `::1`），另外也会尊重
`NO_PROXY`。这条是硬规则：回环流量一旦被塞进代理，本地整合包、局域网镜像
和集成测试都会莫名其妙地失败。

## 项目结构

```
src/main/java/com/skydeps/autotacz/
  model/     不可变领域模型（Gunpack / PackSource / PackState / Versions）
  scan/      本地扫描与内存索引（GunpackScanner / GunpackIndex / PackQuery）
  remote/    远程源与下载（JsonManifestSource / GunpackDownloader / HttpFetcher / ProxySupport）
  service/   用例编排（GunpackService / PackExporter / PackConverter）
  config/    启动检查配置（StarterConfig）
  starter/   启动前更新检查（UpdateChecker / UpdateApplier）
  client/    客户端界面（StarterScreen / StarterScreenHook）
  command/   用户入口（TaczPackCommand）
src/test/java/     单元测试与端到端测试
src/main/resources/
  META-INF/mods.toml   模组声明
dev.bat               CLI 构建/运行入口
```

分层原则：`command` 与 `client` 只处理输入输出，`service` / `starter` 编排用例，
`model` 不依赖任何 MC/Forge API。因此核心逻辑可以脱离游戏做单元测试——
68 个测试中有 65 个不启动 Minecraft 即可运行。

### 测试用的本地清单服务

`testserver/` 下有一个最小 HTTP 服务与示例枪包，用于验证「发现更新 → 下载 → 替换」全链路：

```bash
python testserver/serve.py          # 监听 127.0.0.1:8977
./gradlew test --tests "*UpdateFlowIntegrationTest*"
```

把 `config/autotacz-starter.json` 的 `manifestUrl` 指向
`http://127.0.0.1:8977/packs.json`，即可在游戏里实测启动窗口。
服务未启动时集成测试会自动跳过，不会误报失败。

分层原则：`command` 只解析参数，`service` 编排用例，`model` 不依赖任何 MC/Forge API。
因此核心逻辑可以直接用单元测试覆盖，也能被未来的 GUI 复用。

## 命令行开发

本项目采用纯 CLI 工作流，不依赖 IDE。所有命令都通过 `dev.bat` 入口
（它会自动定位 JDK 17 并设置 `JAVA_HOME`）。

```bash
dev.bat build     # 构建模组 jar -> build\libs\（含单元测试）
dev.bat client    # 启动 Minecraft 开发客户端
dev.bat server    # 启动开发服务端
dev.bat data      # 生成配方 / 模型 / 战利品表
dev.bat clean     # 清理构建产物
dev.bat tasks     # 列出所有 Gradle 任务
dev.bat log       # 打印客户端日志
dev.bat env       # 显示 JDK 与 Gradle 版本
```

**运行测试**
```bash
./gradlew test
```

> 若看到「0 个测试 PASSED」，那不是测试没跑，而是 Gradle 命中了 up-to-date
> 缓存。强制重跑用 `./gradlew test --rerun-tasks`。

**脚本化执行服务端命令**（可用于自动化验证）
```bash
printf 'taczpack list\nstop\n' > cmds.txt
./dev.bat server < cmds.txt
```

**查看实时日志**
```bash
tail -f run/logs/latest.log
```

**直接用 gradle wrapper**（需自己设好 `JAVA_HOME`）
```bash
export JAVA_HOME="/c/Program Files/Zulu/zulu-17"
./gradlew build
./gradlew runClient
```

主类：`com.skydeps.autotacz.AutoTaczGunpackManager`

## 网络说明

本机 `maven.minecraftforge.net` 存在 DNS 污染，需经本地代理（`127.0.0.1:2080`）访问。
相关配置已写入 `~/.gradle/gradle.properties`：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=2080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=2080
```

若在其他机器上开发，请删除这几行或改为可用地址。

## License

MIT