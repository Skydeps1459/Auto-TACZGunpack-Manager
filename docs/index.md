# 开发文档

面向想改代码、编译、调试的人。只想装上玩的话看根目录的 [README](../README.md) 就够了。

| 文档 | 内容 |
|---|---|
| [DEVELOPMENT.md](DEVELOPMENT.md) | 环境搭建、`dev.bat` 命令、构建与测试、本地清单服务、CI |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层结构、数据流、各包职责、关键设计决策及其原因 |

## 先读哪一份

- **第一次 clone 下来想跑起来** → DEVELOPMENT.md 的「快速开始」
- **想加一个远程源 / 改下载逻辑** → ARCHITECTURE.md 的「`remote` —— 网络」+ DEVELOPMENT.md 的「本地清单服务」
- **想改界面或命令** → ARCHITECTURE.md 的「分层与依赖方向」，先确认改动落在哪一层

## 一句话版本

Java 17 + ForgeGradle 6 + Minecraft 1.20.1 Forge 47.4.29，纯命令行开发（不需要 IDE）。
构建入口 `dev.bat build`，产物 `build/libs/autotaczgunpackmanager-0.1.0.jar`。
核心逻辑不依赖 Minecraft API，72 个测试全部不需要启动游戏。