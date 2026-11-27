# Gun Management (NeoForge 1.21.1)

Minecraft `1.21.1` 的 NeoForge 模组开发环境（基于官方 ModDevGradle 模板配置）。

## 项目信息

- **Minecraft 版本**: `1.21.1`
- **NeoForge 版本**: `21.1.268`
- **Java 工具链**: Java 21（本地路径：`/usr/lib/jvm/java-21-temurin-jdk`）
- **Mod ID**: `gun_management`
- **主类**: `com.liokasanovich.gun_management.GunManagement`
- **客户端入口**: `com.liokasanovich.gun_management.GunManagementClient`

## 常用 Gradle 命令

> 如系统默认 `java` 为其他版本（如 JDK 25），建议在命令前指定 `JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk`（VS Code / Cursor 集成终端已通过 `.vscode/settings.json` 自动配置）。

```bash
# 编译并打包模组 JAR（输出到 build/libs/）
./gradlew build

# 启动测试客户端
./gradlew runClient

# 启动测试服务端
./gradlew runServer

# 运行数据生成器（DataGen）
./gradlew runData
```

## IDE 使用说明

- **VS Code / Cursor**：直接打开 `/home/liokasonovich/文档/Gun_Management` 文件夹，已配置 `.vscode/settings.json` 自动绑定 Temurin JDK 21 与 Gradle Wrapper。
- **IntelliJ IDEA**：通过 `File -> Open` 选择 `build.gradle` 或项目根目录，在 Gradle 设置中确认 Gradle JVM 为 JDK 21 即可。
