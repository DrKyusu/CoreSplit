## CoreSplit — Minecraft 26.2 Fabric 并行引擎 Mod
# 核心功能


CoreSplit 是一款面向 Minecraft 26.2 Fabric 的性能优化模组，
旨在通过智能资源管理和实时监控来改善客户端与服务端的游戏体验。

## 核心功能

- **实时性能监控** — 在服务端精确追踪 TPS、MSPT 和总 tick 数，
  每 20 tick 更新一次统计数据。

- **兼容性自动检测** — 启动时自动扫描已知冲突模组
  （C2ME、Lithium、Async 等），并在日志中输出警告信息，
  方便排查性能问题。

- **游戏内配置界面** — 通过 Mod Menu + YACL3 提供图形化配置屏幕，
  支持选择优化引擎模式（自动/本地/在线）、开关覆盖层显示、
  管理兼容性设置。

- **HUD 覆盖层** — 按 F6 切换显示性能指标覆盖层，
  实时展示 TPS、MSPT 和 FPS 等关键数据。

- **网络指标同步** — 服务端定期向客户端推送 TPS/MSPT 快照，
  实现跨端实时数据展示。

- **TOML 配置文件** — 服务端配置存储于 `config/coresplit.toml`，
  首次启动自动生成。

## 环境要求

- Minecraft 26.2
- Fabric Loader ≥ 0.16.0
- Java 25+
- Fabric API

## 兼容性

已知与以下模组存在功能重叠，启用兼容模式可降低冲突风险：

| 模组 | 重叠区域 |
|------|---------|
| C2ME | 区块并行加载 |
| Lithium | 实体 AI 并行 |
| Async | 实体并行 tick |

## 开源协议

AGPL-3.0

## 相关链接

- 源码：https://github.com/DrKyusu/CoreSplit
- 问题反馈：https://github.com/DrKyusu/CoreSplit/issues
