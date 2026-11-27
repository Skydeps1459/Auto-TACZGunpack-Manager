# 开发指南

本文覆盖从零把仓库跑起来需要的一切：安装 JDK、构建、启动开发客户端、跑测试、调试。
架构与设计原因看 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 1. 环境

| 项 | 版本 | 说明 |
|---|---|---|
| Minecraft | 1.20.1 | `minecraft_version_range=[1.20.1,1.21)` |
| Forge | 47.4.29 | ForgeGradle 6 自动下载 |
| Java | **17** | 1.20.1 硬性要求，高版本不支持 |
| Gradle | 8.14.3 | wrapper 内置，无需单独安装 |
| 构建插件 | ForgeGradle 6 | `net.minecraftforge.gradle` |
| Mappings | `official` 1.20.1 | 不是 MCP / Parchment |
| 内存 | `-Xmx3G` | 见 `gradle.properties` |

> **不要用 IDE**？本项目按纯命令行设计（`dev.bat`）。用 IDEA / Eclipse 也能打开
> （`build.gradle` 已带 `idea` / `eclipse` 插件），但文档里的命令都以 CLI 为准。

### JDK 17 装在哪

`dev.bat` 会按顺序探测这几个位置，命中即用：

```
C:\Program Files\Zulu\zulu-17
C:\Program Files\Java\jdk-17
C:\Program Files\Eclipse Adoptium\jdk-17
```

都不存在时，脚本报错退出并提示手工设置 `JAVA_HOME`。
如果你装在其他路径，直接设系统环境变量 `JAVA_HOME` 即可——脚本只在
`JAVA_HOME` 未定义时才去探测，不会覆盖你的设置。

---

## 2. 快速开始

```bash
dev.bat env       # 先确认 JDK 与 Gradle 都能找到
dev.bat build     # 构建 + 跑单测 -> build\libs\
dev.bat client    # 启动开发客户端
```

首次 `client` 会下载约 1 GB 的 MC 资源（199 个文件），进度条看起来像卡住，
实际在下载。之后启动只需十几秒。

`build` 成功后的产物：

```
build/libs/autotaczgunpackmanager-0.1.0.jar           125 KB  可分发
build/libs/autotaczgunpackmanager-0.1.0-sources.jar    77 KB  源码
```

### 为什么需要 `dev.bat`

两个原因，都不是为了好看：

1. **定位 JDK** —— 本机没设 `JAVA_HOME`，直接跑 `gradlew` 会找不到 JDK 17。
2. **转发 stdin** —— `build.gradle` 里给所有 `JavaExec` 打开了
   `standardInput = System.in`，否则服务端控制台命令无法从管道喂入。
   `dev.bat server < cmds.txt` 能工作就靠这个。

---

## 3. `dev.bat` 命令表

| 命令 | 实际执行 | 用途 |
|---|---|---|
| `dev.bat build` | `gradlew build` | 构建 jar，含单元测试 |
| `dev.bat test` | `gradlew test` | 只跑测试 |
| `dev.bat client` | `gradlew runClient` | 启动开发客户端 |
| `dev.bat server` | `gradlew runServer` | 启动开发服务端（`--nogui`，收 stdin） |
| `dev.bat data` | `gradlew runData` | 生成配方 / 模型 / 战利品表到 `src/generated/resources/` |
| `dev.bat clean` | `gradlew clean` | 清 `build/`，保留 `run/` 与依赖缓存 |
| `dev.bat tasks` | `gradlew tasks --all` | 列出所有任务 |
| `dev.bat log` | `type run\logs\latest.log` | 打印上次客户端日志 |
| `dev.bat env` | `java -version` + `gradlew --version` | 体检 |

### 直接用 wrapper

需要自己设 `JAVA_HOME`：

```bash
export JAVA_HOME="/c/Program Files/Zulu/zulu-17"
./gradlew build
./gradlew runClient
```

### 只跑某几个测试

```bash
./gradlew test --tests "*VersionsTest*"
./gradlew test --tests "*UpdateFlowIntegrationTest*"
```

### 强制重跑

Gradle 命中 up-to-date 缓存时，输出里会显示 `0 tests completed`，
看起来像「测试没跑」。这不是失败，是缓存：

```bash
./gradlew test --rerun-tasks
```

CI 上始终带 `--rerun-tasks`，避免缓存掩盖真实结果。

---

## 4. 测试

```bash
dev.bat test
```

**72 个测试，全部是普通 JUnit 5，没有一个需要启动 Minecraft。**
这正是分层设计的目的：`model` / `scan` / `remote` / `service` 不 import 任何
MC/Forge 类，可以像普通 Java 库一样测。

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `ProxySupportTest` | 14 | 代理探测优先级、回环直连、`NO_PROXY`、格式解析容错 |
| `JsonManifestSourceTest` | 10 | 清单解析、`//` 注释、缺字段容错、**校验仓库内的 `packs.json`** |
| `GunpackIndexTest` | 10 | 索引查询、namespace 归一化、模糊搜索 |
| `VersionsTest` | 9 | 版本号比较（含 `1.13.0-Release` 这类真实形态） |
| `StarterConfigTest` | 7 | 默认值、超时夹取、损坏配置回退 |
| `UpdateCheckerTest` | 8 | 状态机、网络异常收敛为可跳过、`autoContinue` 开关 |
| `GunpackScannerTest` | 6 | 目录 / zip 两种形态、损坏包容错 |
| `HttpFetcherTest` | 3 | 超时、UA、重定向 |
| `UpdateFlowIntegrationTest` | 3 | 全链路（**需本地服务**） |
| `RealMw4UpdateTest` | 2 | 真实 MW4 包（**需本地服务或真实 zip**） |

### 5 个会被跳过的测试

`UpdateFlowIntegrationTest` 与 `RealMw4UpdateTest` 用
`Assumptions.assumeTrue(...)` 做前置判断，条件不满足时**跳过而非失败**：

```
本地实测：67 passed / 5 skipped / 0 failed
```

这是刻意的——CI 上不会有本地 HTTP 服务，也不该让集成测试变成红灯。
想让它真跑起来，看下一节。

---

## 5. 本地清单服务（测全链路）

`testserver/` 里有一个最小 HTTP 服务 + 示例枪包，用来验证
「发现更新 → 下载 → 校验 → 替换」这条路径。

```bash
python testserver/serve.py          # 监听 127.0.0.1:8977，Ctrl+C 停止
```

它只是把 `testserver/` 目录当静态站点（`http.server` + `SimpleHTTPRequestHandler`），
内容只有三个文件：

```
testserver/packs.json     清单，指向下面的 zip
testserver/mw4-core.zip   示例枪包
testserver/serve.py       8 行 HTTP 服务
```

服务起来后另开一个终端：

```bash
./gradlew test --tests "*UpdateFlowIntegrationTest*" --rerun-tasks
```

想让**游戏里的启动窗口**也连本地服务，把 `run/config/autotacz-starter.json`
的 `manifestUrl` 改成：

```json
"manifestUrl": "http://127.0.0.1:8977/packs.json"
```

> 回环地址永远直连，不会被 `ProxySupport` 塞进代理——这条是刻意的硬规则，
> 否则本地测试会因为开着代理而莫名其妙失败。

---

## 6. 开发环境里的 TACZ

这是个需要单独说明的坑。

### 真实 TACZ 跑不起来

TACZ 的 mixin 把 **SRG 名硬编码在字节码里**（例如 `@Shadow` 字段 `f_9743_`）。
本项目用 `official` mappings，而 **`@Shadow` 不参与 refmap 重映射**，
必然抛：

```
InvalidMixinException: @Shadow field f_9743_ was not located in the target class
```

`fg.deobf`（反混淆依赖）也**不能**解决——它重写的是方法调用点，
不会改 `@Shadow` 里的字段名。实测两条路都走不通，已回滚。

### 用空壳 mod 代替

关键事实：**本项目零处 `import com.tacz`**。它只操作 `tacz/` 目录下的文件，
不调用 TACZ 任何 API。所以 TACZ 对构建和运行都不是真实依赖，
只是**发布层面的前置声明**。

于是开发期用一个 1 KB 的空壳 mod 满足 `mandatory = true`：

```bash
./gradlew generateTaczDevStub     # -> run/mods/tacz-dev-stub.jar
```

`runClient` / `runServer` / `runData` 都 `dependsOn` 这个任务，不用手动跑。
生成的 jar 里只有一个类：

```java
@Mod("tacz")
public final class TaczDevStub {
    public TaczDevStub() {
        LoggerFactory.getLogger("tacz-dev-stub")
                .warn("TACZ 开发占位模组已加载：仅满足前置声明，不含真实 TACZ 功能");
    }
}
```

`mods.toml` 里 `version = "1.1.8"`，正好落进 `[1.1.0,)`。

### 三条硬限制

1. 占位 jar **只写进 `run/mods/`**，不进 `build/libs/`，不分发。
2. **不要**和真实 TACZ 同时放进 `mods/`——`modId` 冲突会崩。
3. 发布时玩家装的是真 TACZ；占位 mod 对发布物没有任何影响。

> 实现细节上有个坑：`ant.javac` 的 `source` / `target` 会被 Gradle toolchain
> 覆盖，产出 class 版本 65（Java 21），1.20.1 拒绝加载。必须用 `release: '17'`
> （验证过产物主版本号是 61）。

---

## 7. 脚本化执行服务端命令

服务端跑起来后可以从管道喂命令，便于自动化验证：

```bash
printf 'taczpack list\ntaczpack type\nstop\n' > cmds.txt
dev.bat server < cmds.txt
```

前提是 `build.gradle` 里这段（已配置）：

```groovy
tasks.withType(JavaExec).configureEach {
    standardInput = System.in
}
```

去掉它，管道输入会被吞掉，服务端只会干等。

---

## 8. 日志与调试

| 文件 | 内容 |
|---|---|
| `run/logs/latest.log` | 开发客户端 / 服务端最新日志 |
| `run/crash-reports/` | 崩溃报告 |
| `run/config/autotacz-starter.json` | 启动检查配置 |
| `run/tacz/` | 测试用枪包目录 |

```bash
tail -f run/logs/latest.log        # 实时看
dev.bat log                        # 打印最后一份
```

`runClient` 的日志级别是 `debug`（`forge.logging.console.level=debug`），
信息量很大；查本模组相关输出时：

```bash
grep -i "autotacz\|taczpack" run/logs/latest.log
```

---

## 9. CI

`.github/workflows/build.yml`，`push` / `pull_request` 触发：

1. `actions/setup-java@v4` 装 Temurin JDK 17
2. `gradle/actions/setup-gradle@v4`
3. `./gradlew build --rerun-tasks`
4. 上传 `build/libs/*.jar` 为 artifact

期望结果：**67 passed / 5 skipped / 0 failed**、`BUILD SUCCESSFUL`。
5 个跳过的是集成测试（CI 无本地服务），属正常。

### 踩过的坑

| 症状 | 原因 | 修法 |
|---|---|---|
| `./gradlew: Permission denied (exit 126)` | Windows 复制丢了执行位，`git ls-files -s` 显示 `100644` | `git update-index --chmod=+x gradlew` |
| 3 个 `UpdateCheckerTest` 在 CI 红、本地绿 | 测试真的连到了备用清单地址 | 备用地址改成可注入，测试传空列表 |
| `javadoc` 报「不可映射字符」 | 缺 `encoding`，按 GBK 读 UTF-8 源码 | `tasks.withType(Javadoc)` 设 `encoding = 'UTF-8'` |
| `gradle.properties` 中文变 `æ¹éç®¡ç` | 按 ISO-8859-1 读取 | 写成 `\uXXXX` 转义 |

---

## 10. 网络（Gradle 侧）

本机 `maven.minecraftforge.net` 有 DNS 污染，需经本地代理。配置写在
`~/.gradle/gradle.properties`（**不在本仓库**）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=2080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=2080
```

换机器开发时删掉这几行，或改成你自己的代理地址。
Gradle 只读 `systemProp.*`，不认 `HTTP_PROXY` 环境变量——这点和模组运行时的
`ProxySupport` 不一样，后者两者都认。

> 注意区分：**构建期**走 Gradle 的 `systemProp`；**运行期**（玩家启动游戏）
> 走 `ProxySupport` 的探测链路。两者没有关系。

---

## 11. 发布

```bash
dev.bat build
# 把 build/libs/autotaczgunpackmanager-0.1.0.jar 上传到 Release
```

不发布 `-sources.jar` 和 `tacz-dev-stub.jar`。

改了 `packs.json`（线上清单）不需要重新发版——推送到 `main` 后
jsDelivr 几分钟内生效。清单格式与缓存行为见 [ARCHITECTURE.md](ARCHITECTURE.md) 的
「`remote` —— 网络」一节。