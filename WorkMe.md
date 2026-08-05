# CoreSplit 维护与继续开发文档

> **文档用途**：本文件是 CoreSplit 项目的维护手册与记忆备份，供后续开发者（含 AI 助手）快速理解项目全貌、各文件职责、架构决策与待办事项。
>
> **最后更新**：2026-08-06  
> **项目版本**：2.5.0  
> **目标 MC 版本**：26.2  
> **作者**：DR.Kyusu  

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈与构建配置](#2-技术栈与构建配置)
3. [项目目录结构总览](#3-项目目录结构总览)
4. [核心入口层](#4-核心入口层)
5. [AI 优化模块 (`ai/`)](#5-ai-优化模块-ai)
6. [区块引擎模块 (`chunk/`)](#6-区块引擎模块-chunk)
7. [爆炸优化模块 (`explosion/`)](#7-爆炸优化模块-explosion)
8. [性能管理器 (`governor/`)](#8-性能管理器-governor)
9. [调度器模块 (`scheduler/`)](#9-调度器模块-scheduler)
10. [内存优化模块 (`memory/`)](#10-内存优化模块-memory)
11. [渲染限制模块 (`renderlimiter/`)](#11-渲染限制模块-renderlimiter)
12. [纹理模块 (`texture/`)](#12-纹理模块-texture)
13. [模型模块 (`model/`)](#13-模型模块-model)
14. [声音模块 (`sound/`)](#14-声音模块-sound)
15. [兼容性模块 (`compat/`)](#15-兼容性模块-compat)
16. [配置系统 (`config/`)](#16-配置系统-config)
17. [命令系统 (`command/`)](#17-命令系统-command)
18. [HUD 与叠加层 (`hud/`, `overlay/`)](#18-hud-与叠加层-hud-overlay)
19. [日志系统 (`logging/`)](#19-日志系统-logging)
20. [监控模块 (`monitoring/`)](#20-监控模块-monitoring)
21. [Mixin 注入层 (`mixin/`)](#21-mixin-注入层-mixin)
22. [其他模块](#22-其他模块)
23. [资源文件说明](#23-资源文件说明)
24. [测试体系](#24-测试体系)
25. [关键架构决策与教训](#25-关键架构决策与教训)
26. [后续开发计划](#26-后续开发计划)

---

## 1. 项目概述

CoreSplit 是一个 Minecraft 26.2 (Fabric) 的客户端+服务端性能优化模组。它通过以下方式提升游戏体验：

- **AI 节流**：根据实体到玩家的距离分桶，远距离实体降低 AI 更新频率
- **爆炸分帧**：将爆炸计算分散到多帧/多 tick，避免单帧卡顿
- **内存优化**：对象池复用、LRU 缓存、自动驱逐策略
- **渲染限制**：实体/掉落物/粒子渲染距离与数量控制
- **动态性能调节**：根据实时帧率自动调整渲染距离、粒子等质量参数
- **GPU 加速**：实验性 CUDA/OpenCL/Vulkan 后端支持
- **深度兼容**：与 Iris/Sodium 光影模组协调，避免设置冲突
- **区块并行生成**：多线程并行处理区块生成的各阶段

**模组 ID**：`coresplit`  
**环境**：`*`（客户端+服务端）  
**许可证**：AGPL-3.0

---

## 2. 技术栈与构建配置

### 依赖版本（`gradle.properties`）

| 依赖 | 版本 |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.152.1+26.2 |
| YACL | 3.9.5+26.2-fabric |
| Mod Menu | 20.0.1 |
| Java | 25 (sourceCompatibility & targetCompatibility) |
| Loom | 1.17.16 |
| Gradle | 9.6.1（系统安装） |
| JUnit | 5.10.2 |

### 构建命令

```bash
# 编译
gradle compileJava

# 运行测试
gradle test

# 构建 JAR
gradle build

# 启动客户端（Mixin 变更必须用此验证）
gradle runClient

# 启动服务端
gradle runServer
```

### 关键构建说明

- `processResources` 会将 `gradle.properties` 中的 `mod_version` 注入 `fabric.mod.json` 的 `${version}` 占位符
- `withSourcesJar()` 启用源码 JAR 打包
- 所有 Java 文件使用 UTF-8 编码，`options.release = 25`
- NightConfig TOML 库通过 `include` 打包进 JAR（内嵌依赖）

---

## 3. 项目目录结构总览

```
d:\CoreSplit\
├── build.gradle                    # 构建脚本
├── gradle.properties               # 版本与依赖配置
├── settings.gradle                 # Gradle 设置
├── WorkMe.md                       # 本文档
├── src/
│   ├── main/
│   │   ├── java/com/coresplit/
│   │   │   ├── CoreSplitMod.java           # 通用/服务端入口
│   │   │   ├── CoreSplitClientMod.java     # 客户端入口
│   │   │   ├── ai/                         # AI 优化（9 个文件）
│   │   │   ├── api/                        # 公共 API（1 个文件）
│   │   │   ├── chunk/                      # 区块引擎（6 个文件）
│   │   │   ├── command/                    # 命令系统（2 个文件）
│   │   │   ├── compat/                     # 兼容性（10 个文件）
│   │   │   │   └── remote/                 # 远程 schema（5 个文件）
│   │   │   ├── config/                     # 配置系统（4 个文件）
│   │   │   ├── core/                       # 模块管理（1 个文件）
│   │   │   ├── explosion/                  # 爆炸优化（7 个文件）
│   │   │   ├── governor/                   # 性能管理器（1 个文件）
│   │   │   ├── hud/                        # HUD 渲染（5 个文件）
│   │   │   ├── itemrender/                 # 物品渲染（3 个文件）
│   │   │   ├── logging/                    # 日志系统（2 个文件）
│   │   │   ├── memory/                     # 内存优化（8 个文件）
│   │   │   ├── mixin/                      # Mixin 注入（12 个文件）
│   │   │   │   ├── ai/                     # AI 相关 Mixin
│   │   │   │   ├── explosion/              # 爆炸相关 Mixin
│   │   │   │   ├── memory/                 # 内存相关 Mixin
│   │   │   │   └── render/                 # 渲染相关 Mixin
│   │   │   ├── model/                      # 模型系统（9 个文件）
│   │   │   ├── monitoring/                 # 性能监控（5 个文件）
│   │   │   ├── network/                    # 网络通信（1 个文件）
│   │   │   ├── overlay/                    # 叠加层（4 个文件）
│   │   │   ├── particle/                   # 粒子过滤（1 个文件）
│   │   │   ├── renderlimiter/              # 渲染限制 + GPU（14 个文件）
│   │   │   │   └── gpu/                    # GPU 后端实现
│   │   │   ├── scheduler/                  # 任务调度（5 个文件）
│   │   │   ├── sound/                      # 声音系统（5 个文件）
│   │   │   └── texture/                    # 纹理系统（6 个文件）
│   │   └── resources/
│   │       ├── fabric.mod.json             # Mod 描述文件
│   │       ├── mixins.coresplit.json       # Mixin 配置
│   │       ├── coresplit-default.toml      # 默认服务端配置
│   │       └── assets/coresplit/
│   │           ├── icon.png                # Mod 图标
│   │           └── lang/
│   │               ├── en_us.json          # 英文语言文件
│   │               └── zh_cn.json          # 中文语言文件
│   └── test/
│       └── java/com/coresplit/             # 测试代码（32 个文件）
└── build/                                  # 构建输出（自动生成）
```

---

## 4. 核心入口层

### `CoreSplitMod.java`

**职责**：通用/服务端入口点，实现 `ModInitializer` 和 `DedicatedServerModInitializer`。

**初始化流程**：
1. 启动双语异步文件日志器（CSLogger）
2. 加载模块管理器（ModuleManager）
3. 加载服务端配置（CoreSplitConfig）
4. 检测模组冲突（ConflictManager）
5. 初始化性能监控器（ServerPerformanceMonitor + PerformanceMonitor）
6. 初始化高负载优化模块：
   - PrioritizedTaskScheduler（优先级任务调度器）
   - ExplosionOptimizer（爆炸优化）
   - AiOptimizer（AI 优化）
   - MemoryOptimizer（内存优化）
7. 注册服务端命令（CSServerCommands）
8. 注册服务端生命周期回调（SERVER_STARTING / SERVER_STOPPING）
9. 注册服务端 tick 回调（START_SERVER_TICK / END_SERVER_TICK）

**静态字段（全局访问点）**：
- `config` — 服务端配置
- `perfMonitor` — 服务端性能监控
- `clientPerformanceMonitor` — 客户端性能监控
- `conflictManager` — 冲突管理器
- `moduleManager` — 模块管理器
- `clientMod` — 客户端入口实例
- `explosionOptimizer` / `aiOptimizer` / `memoryOptimizer` / `prioritizedScheduler`

**关键设计**：
- 所有静态可变字段使用 `volatile` 保证内存可见性
- tick 回调中各模块独立 try-catch 隔离，一个模块故障不影响其他模块
- 所有回调中的字段访问都有 null 检查

### `CoreSplitClientMod.java`

**职责**：客户端入口点，实现 `ClientModInitializer`。

**初始化流程**：
1. 加载客户端配置（CoreSplitYaclConfig）
2. 应用 CSLogger 配置（开关 + 日志级别）
3. 注册按键绑定（F6 叠加层切换 + F3+S 扩展信息）
4. 初始化 GPU 后端注册表（GpuBackendRegistry）
5. 检测渲染兼容性（RenderCompatDetector）
6. 初始化 Iris/Sodium 兼容管理器
7. 启动远程 schema 异步拉取（受配置开关控制）
8. 初始化实体渲染限制器（EntityRenderLimiter）
9. 初始化粒子过滤器（ParticleFilter）
10. 初始化模块管理器
11. 初始化性能管理器（PerformanceGovernor）
12. 初始化任务调度器（TaskScheduler）— 从配置应用持久化值
13. 初始化资源监控器、配置文件管理器、区块引擎
14. 初始化动态内存优化器
15. 注册客户端 tick 回调
16. 注册客户端命令（CSCommands）
17. 注册 HUD 提供者（DefaultHudProviders）
18. **注册 HUD 渲染回调（HudElementRegistry）**

**HUD 渲染（v2.5.0 修复）**：
```java
// MC 26.2 移除了 HudRenderCallback，必须使用 HudElementRegistry
HudElementRegistry.attachElementBefore(
    VanillaHudElements.CHAT,
    Identifier.fromNamespaceAndPath(MOD_ID, "main_hud"),
    (ctx, deltaTracker) -> { /* 渲染逻辑 */ });
```

### `api/CoreSplitAPI.java`

**职责**：对外公共 API 接口定义，供其他模组集成。

**接口**：
- `PBRTextureRegistry` — PBR 材质注册
- `EntityTextureRegistry` — 实体纹理变体注册
- `ModelRegistry` — 自定义模型/动画注册
- `ChunkEngineAPI` — 区块引擎状态查询与控制

**Record 类**：
- `PBRProperties` — PBR 材质属性（金属度、粗糙度、AO、法线强度、发光等）
- `ResourceRegistration` — 资源注册信息

### `core/ModuleManager.java`

**职责**：模块管理器单例，负责模块的加载、启用/禁用状态管理。

---

## 5. AI 优化模块 (`ai/`)

### 模块概述

AI 优化模块通过距离分桶节流、路径缓存、路径共享三种策略减少不必要的 AI 计算开销。

### 文件清单

| 文件 | 职责 |
|---|---|
| `AiOptimizer.java` | **门面类**。管理节流控制器、路径缓存、路径共享注册表和空间索引。提供 `shouldTickAi()` / `shouldManuallyDisableAi()` 供 Mixin 调用。维护 `/cs ai off` 手动禁用区域状态。 |
| `AiBucket.java` | 距离桶枚举。根据实体到玩家的距离将实体分入 NEAR / MID / FAR / OFFSCREEN 桶，各桶有不同的 AI 更新频率。 |
| `AiConfig.java` | AI 优化子配置。读取/保存节流开关、路径缓存参数（TTL、最大容量）、路径共享开关、桶半径等。 |
| `AiSpatialIndex.java` | 空间索引。提供 `getEntitiesInRange()` 快速范围查询，供节流控制器和路径共享注册表使用。 |
| `EntityAiThrottle.java` | 节流控制器。根据实体所在桶决定本次 tick 是否执行 AI，近处实体每 tick 执行，远处实体按比例跳过。 |
| `PathCache.java` | 路径缓存。LRU + TTL 策略，使用 `ConcurrentSkipListMap` 实现 O(log n) 驱逐。键为 `PathKey`，值为缓存的路径对象。 |
| `PathKey.java` | 缓存键。由起点区块坐标、终点区块坐标、实体类型组成，实现 `equals` / `hashCode`。 |
| `PathNavCache.java` | 路径导航缓存门面。提供静态方法 `tryGetCachedPath()` / `cachePath()` 供 `PathNavigationMixin` 调用，隔离 Mixin 与缓存实现。 |
| `SharedPathRegistry.java` | 路径共享注册表。同类实体（如多个村民前往同一目标）共享路径计算结果，减少重复寻路。 |

### 关键设计

- **手动 AI 禁用**（`/cs ai off r=N`）：独立于全局 AI 优化开关，优先级最高。以最近玩家为中心，半径内村民 AI 被完全取消（`ci.cancel()`）。使用平方距离比较避免开方运算。
- **Mixin 注入点**：`Mob.serverAiStep()`（HEAD），**不是** `Mob.tick()`。`serverAiStep()` 仅负责 AI 调度（goalSelector/brain/控制器），cancel 它只跳过 AI，物理仍由 `tick()→aiStep()` 正常执行。
- **LRU 键溢出修复**：缓存键不能使用 `System.nanoTime() << N` 位移（nanoTime > 2^43 后溢出 long 符号位），改用纯单调递增的 `AtomicLong` 计数器。

---

## 6. 区块引擎模块 (`chunk/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `ChunkEngine.java` | **核心引擎**。管理区块生成、加载和实体过滤，提供异步处理和配置化控制。单例模式，含 `shutdown()` 优雅关闭。 |
| `AsyncChunkIO.java` | 异步区块读写。线程安全的异步 IO 操作，包含缓冲池复用和资源管理机制。 |
| `ChunkEngineConfig.java` | 区块引擎配置。管理生成线程数、IO 线程数等参数，支持动态重载。 |
| `ChunkTaskScheduler.java` | 区块任务调度。优先级队列 + 并发控制，确保高优先级区块（玩家附近）先处理。 |
| `EntityFilter.java` | 实体分区管理。通过分区减少不必要的实体处理，优化实体位置更新和范围查询。 |
| `ParallelChunkGenerator.java` | 并行区块生成。多线程并行处理区块的噪声、生物群落、结构等生成阶段。 |

---

## 7. 爆炸优化模块 (`explosion/`)

### 模块概述

将爆炸计算从单帧分散到多帧/多 tick，避免大量同时爆炸导致的服务端卡顿。

### 文件清单

| 文件 | 职责 |
|---|---|
| `ExplosionOptimizer.java` | **门面类**。拦截爆炸事件，提交到批处理器进行分帧处理。提供 `onServerTick()` 和 `onClientTick()` 回调。 |
| `AsyncExplosionProcessor.java` | 异步爆炸处理器。在后台线程计算方块影响和粒子数量。 |
| `ExplosionBatcher.java` | 爆炸批处理器。分帧处理爆炸任务，确保单帧不会因爆炸处理造成卡顿。**有界队列**（MAX_PENDING_CAPACITY=50000）防止 OOM。 |
| `ExplosionBlockImpact.java` | 方块影响裁剪器。优化爆炸范围内的方块检测，跳过远离爆炸中心的方块检测。 |
| `ExplosionConfig.java` | 爆炸配置管理。每帧/每 tick 最大爆炸数、粒子级别、远距离方块跳过等参数持久化和范围校验。 |
| `ExplosionParticleLimiter.java` | 爆炸粒子限制器。根据配置控制爆炸粒子数量上限。 |
| `ExplosionTask.java` | 爆炸任务封装。实现 `Comparable` 支持优先级排序（距离优先），支持爆炸合并。 |

### 关键设计

- 爆炸队列使用 `PriorityBlockingQueue` + 容量上限 + 溢出拒绝策略，防止无界队列 OOM
- 粒子级别可配置：LOW / MEDIUM / HIGH / UNLIMITED

---

## 8. 性能管理器 (`governor/`)

### `PerformanceGovernor.java`

**职责**：动态性能调节器，根据实时帧率自动调整客户端渲染设置以维持目标帧率。

**核心功能**：
- 每 tick 采集 FPS 数据，计算短期/长期趋势
- 根据 FPS 与目标值的偏差，动态调整：
  - 渲染距离（升/降 1-2 chunk）
  - 粒子数量
  - 实体渲染距离
  - 质量等级（0-5：Max → Min）
- 高 FPS 场景（>200fps）使用扩展间隔（5s）和连续趋势检测，避免频繁质量切换
- 与 Iris/Sodium 协调：写入 framerateLimit / renderDistance 时通过 CompatCoordinator 钳制
- 可强制关闭 vsync，使目标帧率上限精确生效

**质量等级**：
| 等级 | 标签 | 含义 |
|---|---|---|
| 0 | Max | 最高质量，不限制 |
| 1 | High | 高质量 |
| 2 | Medium | 中等质量 |
| 3 | Low | 低质量 |
| 4 | Lower | 更低质量 |
| 5 | Min | 最低质量 |

---

## 9. 调度器模块 (`scheduler/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `TaskScheduler.java` | **通用任务调度器**。基于 `ThreadPoolExecutor`，支持核心数/线程数配置、优雅关闭。配置变更通过 `applyConfiguration()` / `applyConfigurationWithMultiplier()` 即时生效。 |
| `PrioritizedTaskScheduler.java` | **优先级任务调度器**。用于爆炸/AI 等 CPU 密集型工作。有界队列（≤50000）+ CallerRunsPolicy 饱和处理。使用 `ConcurrentHashMap.compute` 防止任务提交竞态。 |
| `HardwareDetector.java` | 硬件检测工具。多方法探测 CPU 逻辑核心数（Runtime + OS 原生），推荐 CPU 密集型线程数（全部核心 - 1）。 |
| `ProfileManager.java` | 性能配置文件管理。预设 LOW / MEDIUM / HIGH / EXTREME 配置，一键切换调度器参数。 |
| `ResourceMonitor.java` | 资源监控器。实时采集 CPU 使用率、内存使用率、活跃线程数，供 HUD/F3 显示。 |

### 关键设计

- v2.5 将默认调度器核心数从 `AVAILABLE_CORES / 2` 提升到 `ALL_CORES - 1`，16 核 CPU 上从 8→15（87% 利用率）
- `completedCount` 计数器在 `finally` 块中递增，确保准确
- 线程数配置范围：1~4 倍 CPU 核心数；核心数配置范围：1~系统最大核心数

---

## 10. 内存优化模块 (`memory/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `MemoryOptimizer.java` | **门面类**。管理对象池、缓存驱逐、堆整理等子模块，提供 `onTick()` 回调。 |
| `ByteBufferPool.java` | ByteBuffer 对象池。复用 ByteBuffer 避免频繁分配/GC，有界容量 + 范围校验。 |
| `DynamicMemoryOptimizer.java` | 动态内存优化器。在**专用低优先级后台线程**中执行内存清理，避免主线程 `System.gc()` 导致的帧丢失（50ms 暂停 = 1000fps 下 50 帧丢失）。 |
| `EntityDataPool.java` | 实体数据对象池。复用实体数据对象，减少 GC 压力。有 `Math.max(16, ...)` 下限钳制。 |
| `EntityDataUnloader.java` | 实体数据卸载器。远距离实体的非关键数据从内存卸载。 |
| `HeapDefragmenter.java` | 堆整理器。减少内存碎片化。 |
| `MemoryConfig.java` | 内存配置。纹理缓存大小、实体池大小、驱逐间隔、激进驱逐阈值。 |
| `ResourceEvictor.java` | 资源驱逐器。基于 TTL + LRU 策略驱逐缓存资源。 |

### 关键设计

- 所有内存清理操作在专用低优先级后台线程执行，**绝不**在主线程调用 `System.gc()`
- LRU 缓存使用 `ConcurrentHashMap` + `ConcurrentSkipListMap` 实现无锁 LRU
- LRU 键使用纯单调递增 `AtomicLong`，不使用 `nanoTime` 位移

---

## 11. 渲染限制模块 (`renderlimiter/`)

### 文件清单

#### 顶层

| 文件 | 职责 |
|---|---|
| `EntityRenderLimiter.java` | 实体渲染限制器。控制掉落物/生物的渲染数量和距离。`beginFrame()` 每帧刷新缓存避免重复单例查询。 |
| `GpuAccelerationManager.java` | GPU 加速管理器。管理 GPU 后端的生命周期和任务分发。 |
| `GpuMonitor.java` | GPU 监控器。通过反射获取 GPU 使用率和名称，独立 try-catch 处理类加载失败。 |

#### GPU 后端 (`gpu/`)

| 文件 | 职责 |
|---|---|
| `GpuBackend.java` | GPU 后端抽象接口。定义 `submit()` / `isAvailable()` / `getBackendType()` 等方法。 |
| `GpuBackendRegistry.java` | GPU 后端注册表。自动检测可用设备，选择最佳后端，支持运行时切换和健康监控。 |
| `BackendType.java` | 后端类型枚举：CUDA / OPENCL / VULKAN / OPENGL / CPU_FALLBACK。 |
| `CudaBackend.java` | CUDA 后端实现（通过反射调用 JCuda）。 |
| `OpenClBackend.java` | OpenCL 后端实现（通过反射调用 JOCL）。 |
| `VulkanBackend.java` | Vulkan 后端实现（通过反射调用 LWJGL Vulkan）。 |
| `OpenGlBackend.java` | OpenGL 后端实现。 |
| `CpuFallbackBackend.java` | CPU 回退后端。GPU 后端不可用或连续失败时的安全回退。 |
| `GpuBackendStats.java` | 后端统计。任务数、成功率、平均延迟。 |
| `GpuDeviceDescriptor.java` | GPU 设备描述。设备名、显存、计算能力等。 |
| `GpuTask.java` | GPU 任务封装。 |

### 关键设计

- GPU 后端互斥：启用一个自动禁用其他
- 健康监控：慢任务（>100ms）或连续失败（3+次，成功率 <80%）自动回退到 CPU
- GPU API 调用使用反射 + 独立 try-catch，安全处理类加载失败
- 后端切换包含验证检查：注册表初始化、后端注册、可用性，失败自动回退 CPU

---

## 12. 纹理模块 (`texture/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `TextureCache.java` | 纹理缓存。LRU 策略，使用 `AtomicLong` 访问顺序键（非 nanoTime 位移）。有界容量（256-8192）。 |
| `TextureManager.java` | 纹理管理器。纹理的加载、注册、卸载。 |
| `EmissiveRenderer.java` | 发光纹理渲染器。支持 OptiFine 发光纹理格式。 |
| `EntityTextureProvider.java` | 实体纹理提供者。支持纹理变体（如不同颜色的羊）。 |
| `OptiFinePropertiesParser.java` | OptiFine properties 文件解析器。解析 OptiFine 格式的纹理属性配置。 |
| `PlayerSkinEnhancer.java` | 玩家皮肤增强器。HD 皮肤支持。 |

---

## 13. 模型模块 (`model/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `AnimationController.java` | 动画控制器。管理实体动画的播放、混合、过渡。 |
| `AnimationOptimizer.java` | 动画优化器。远距离实体降低动画更新频率或跳过插值。 |
| `AnimationStateManager.java` | 动画状态管理器。管理动画状态机的状态转换。 |
| `BlockEntityModelAdapter.java` | 方块实体模型适配器。适配自定义方块实体模型。 |
| `CemModelLoader.java` | CEM（Custom Entity Models）模型加载器。加载 CEM 格式的自定义实体模型。 |
| `JemFileParser.java` | JEM 文件解析器。解析 OptiFine JEM 模型格式。 |
| `ModelDebugger.java` | 模型调试器。开发工具，可视化模型/动画状态。 |
| `PhysicsCompatibilityLayer.java` | 物理兼容层。与物理模组（如 Physics Mod）的兼容性处理。 |
| `PlayerModelHandler.java` | 玩家模型处理器。自定义玩家模型支持。 |

---

## 14. 声音模块 (`sound/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `EntitySoundSystem.java` | 实体声音系统。管理实体相关声音的播放、距离衰减。 |
| `SoundAnimationIntegrator.java` | 声音动画集成器。将声音事件与动画同步。 |
| `SoundDrivenVisualManager.java` | 声音驱动视觉管理器。声音触发的视觉效果（如音乐节拍粒子）。 |
| `SoundPropertiesParser.java` | 声音属性解析器。解析声音配置文件。 |
| `SoundVariantManager.java` | 声音变体管理器。同一声音事件的多种变体随机播放。 |

---

## 15. 兼容性模块 (`compat/`)

### 顶层文件

| 文件 | 职责 |
|---|---|
| `CompatCoordinator.java` | **兼容协调器**。协调 CoreSplit governor 与 Iris/Sodium 的行为，包括帧率限制、渲染距离、光影距离等。构建 `CompatSummary` 供 F3/HUD 显示。 |
| `CompatibilityDetector.java` | 兼容性检测器。检测已安装的兼容目标模组。 |
| `ConflictManager.java` | 冲突管理器。检测已知不兼容模组，自动解决冲突。使用 `FabricLoader.getInstance().isModLoaded()` 模式。 |
| `IrisCompatManager.java` | Iris 兼容管理器。通过反射读取/修改 Iris 配置（光影开关、光影包名、阴影距离等）。字段读取使用统一循环处理 `NoSuchFieldException`。 |
| `SodiumCompatManager.java` | Sodium 兼容管理器。通过反射读取/修改 Sodium 配置（帧率限制、渲染距离等）。 |
| `RenderCompatDetector.java` | 渲染兼容检测器。检测 Iris/Sodium 版本，与远程最新版本比对，提示更新。 |
| `ShaderPerformanceOptimizer.java` | 光影性能优化器。光影开启时自动调整阴影距离、渲染距离、粒子、实体数量等。 |
| `ShaderMemoryOptimizer.java` | 光影内存优化器。光影开启时激进清理纹理/模型缓存。 |
| `MiniHudInfoLine.java` | MiniHUD 信息行集成（已停用，改用原版 F3 界面）。 |
| `ModMenuCompat.java` | Mod Menu 集成。在 Mod Menu 中添加 CoreSplit 设置入口。 |

### 远程 Schema (`remote/`)

| 文件 | 职责 |
|---|---|
| `RemoteSchemaFetcher.java` | 远程 schema 拉取器。异步从 GitHub 拉取 Iris/Sodium 配置 schema 与 release 信息。受双开关（deepCompatEnabled + remoteSchemaEnabled）控制。 |
| `IrisConfigSchema.java` | Iris 配置 schema。定义 Iris 配置项的字段名和类型。 |
| `SodiumConfigSchema.java` | Sodium 配置 schema。定义 Sodium 配置项的字段名和类型。 |
| `SchemaCache.java` | Schema 缓存。TTL + 同步策略防止竞态。 |
| `RateLimiter.java` | 速率限制器。令牌桶算法，防止 GitHub API 请求过于频繁。 |

### 关键设计

- 兼容管理器在客户端启动时初始化（CoreSplitClientMod 第 73-76 行），关闭时优雅停止（第 136-144 行）
- 远程拉取在异步守护线程中执行，不阻塞主线程
- 反射调用全部有 try-catch 保护，模组未安装时安全返回默认值

---

## 16. 配置系统 (`config/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `CoreSplitConfig.java` | **服务端配置**。TOML 格式（`config/coresplit.toml`），包含网络监控、性能管理、爆炸/AI/内存优化等开关。首次启动从 `coresplit-default.toml` 复制。 |
| `CoreSplitYaclConfig.java` | **客户端配置**。YACL 配置界面 + 运行时静态字段。包含所有客户端可调参数（FPS 目标、调度器、渲染限制、GPU 加速、光影优化、内存管理、日志、HUD 等）。`makeScreen()` 构建 YACL 配置界面。 |
| `ClientOptimizationMode.java` | 客户端优化模式枚举：AUTO / MANUAL / CUSTOM。 |
| `HudPosition.java` | HUD 位置枚举：TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT。 |

### 配置项分类（CoreSplitYaclConfig）

YACL 配置界面包含以下类别（Category）：

1. **Engine** — 优化模式
2. **Performance** — 目标 FPS、容差、vsync
3. **Scheduler** — CPU 核心数、线程倍数
4. **Render** — 实体/掉落物/粒子渲染限制
5. **Shader Optimization** — 光影性能优化
6. **Memory** — 纹理缓存、对象池、驱逐策略
7. **Logging** — CSLogger 开关、日志级别
8. **Overlay** — F6 叠加层开关、字体缩放
9. **CS HUD** — HUD 位置、透明度、背景
10. **Compatibility** — Iris/Sodium 深度兼容
11. **GPU Acceleration** — CUDA/OpenCL/Vulkan/OpenGL

### 关键设计

- 所有可变静态字段使用 `volatile`（配置 UI 线程写入与客户端 tick 线程读取需要内存可见性）
- 所有配置值有范围校验（MIN/MAX 常量集中定义）
- setter 方法执行三件事：更新字段值 → 调用功能类应用设置 → 调用 `save()` 持久化
- `makeScreen()` 异常时返回 fallback Screen，永不返回 null

---

## 17. 命令系统 (`command/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `CSCommands.java` | **客户端命令注册**。通过 `ClientCommandRegistrationCallback` 注册所有客户端命令。 |
| `CSServerCommands.java` | **服务端命令注册**。通过 `CommandRegistrationCallback` 注册服务端命令。 |

### 客户端命令列表

| 命令 | 功能 |
|---|---|
| `/cshelp` | 列出所有命令及用法 |
| `/csset` (`/csSet`) | 打开 YACL 设置界面 |
| `/csfps` | 显示当前/平均/最低/最高 FPS |
| `/csstats` | 综合性能统计 |
| `/csreload` | 从磁盘重新加载配置并应用 |
| `/cstoggle <module>` | 切换模块开关（governor/scheduler/ai） |
| `/csinfo` | 模组版本、硬件信息、模块状态 |

### 服务端命令列表

| 命令 | 功能 |
|---|---|
| `/cs ai on` | 恢复村民 AI（清除手动禁用区域） |
| `/cs ai off` | 禁用默认半径（2格）内村民 AI |
| `/cs ai off r=N` | 禁用半径 N（1-32）内村民 AI |

### 关键设计

- 客户端命令使用 `ClientCommands.literal()` / `ClientCommands.argument()`（MC 26.2 API 重命名）
- 字符串参数使用 `com.mojang.brigadier.arguments.StringArgumentType`（非 `net.minecraft.commands.arguments.StringArgumentType`）
- 屏幕打开使用 `Minecraft.setScreenAndShow()`（MC 26.2 重命名），传 `null` 作为 parent
- 客户端命令已运行在主线程，不使用 `Minecraft.execute()`
- 换行组件使用 `Component.literal("\n")`（非 `Component.newline()`）
- 服务端命令 OP 权限等级 2+（LEVEL_GAMEMASTERS）
- 半径参数支持多种格式：`r=N` / `R=n` / 纯数字 / `半径=N`
- 响应时间 ≤300ms，支持中英文

---

## 18. HUD 与叠加层 (`hud/`, `overlay/`)

### HUD 系统 (`hud/`)

| 文件 | 职责 |
|---|---|
| `CSHudApi.java` | HUD API 单例。外部模组通过 `CSHudApi.getInstance().register(...)` 注册自定义 HUD 行提供者。 |
| `CSHudRenderer.java` | HUD 渲染器。收集所有注册的 `HudLineProvider` 提供的文本行，按优先级排序后渲染。支持背景、阴影、字体缩放、位置（四角）。检测 MiniHUD 存在时自动禁用。 |
| `DefaultHudProviders.java` | 内置 HUD 提供者注册。FPS、玩家信息、世界信息、模块状态等默认行。 |
| `HudLine.java` | HUD 文本行数据类。包含文本内容和颜色。 |
| `HudLineProvider.java` | HUD 行提供者接口。`getLines()` 返回行列表，`isVisible()` 控制可见性，`getProviderId()` 返回唯一标识。 |

### 叠加层 (`overlay/`)

| 文件 | 职责 |
|---|---|
| `CoreSplitOverlay.java` | 叠加层核心。包含 CS HUD 渲染入口（`render()`）和 F6 叠加层渲染入口（`renderHud()`）。维护渲染数据缓存（500ms TTL），颜色分级（FPS/资源 正常/警告/临界）。包含 Iris/Sodium 兼容信息追加。 |
| `CoreSplitF3KeyBinding.java` | F3 扩展信息按键绑定。F3+S 切换 CoreSplit 扩展信息行。 |
| `CoreSplitDebugEntry.java` | F3 调试界面信息行注册。将 CoreSplit 数据输出到原版 F3 界面。 |
| `OverlayKeyBinding.java` | F6 叠加层切换按键绑定。 |

### 关键设计（v2.5.0 修复）

- **HUD 渲染使用 `HudElementRegistry`**：MC 26.2 的 Fabric API（fabric-rendering-v1 25.1.6）**完全移除**了 `HudRenderCallback`。必须使用 `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ...)` 注册 HUD 元素。
- HUD 渲染在 `HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)` 方法中执行
- F6 叠加层有 1 秒缓存 TTL，避免每帧调用 `buildSummary()`
- 矩阵缩放使用 `pushMatrix()` / `popMatrix()` 避免浮点精度漂移
- MiniHUD 存在检测：`FabricLoader.getInstance().isModLoaded("minihud")`

---

## 19. 日志系统 (`logging/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `CSLogger.java` | **双语异步文件日志器**。在 `<gameDir>/CSlogs/` 下生成中英双语日志文件。异步写入（有界队列 8192 + 低优先级守护线程）。日志格式：`[时间戳] [级别] [事件源] 消息`。支持日志级别实时切换。队列饱和时按严重度处理：丢弃 DEBUG/INFO（周期告警），WARN/ERROR 回退到 SLF4J。 |
| `LogLevel.java` | 日志级别枚举：DEBUG / INFO / WARN / ERROR。 |

### 日志文件命名

- 英文：`cslogYYYYMMDD.txt`
- 中文：`cslogYYYYMMDD_Zh_CN.txt`
- 每日午夜自动轮转

### 关键设计

- 日志目录 `CSlogs` 使用 `Files.createDirectories()` 自动创建（幂等）
- 异步写入避免影响游戏性能
- 队列饱和时按严重度分级处理，防止关键事件丢失
- `CSLogger.info(source, enMsg, zhMsg)` / `CSLogger.info(source, enTemplate, zhTemplate, args...)` 双语 API

---

## 20. 监控模块 (`monitoring/`)

### 文件清单

| 文件 | 职责 |
|---|---|
| `PerformanceMonitor.java` | **客户端性能监控器**。采集 FPS、MSPT、帧时间等指标。环形缓冲区存储历史数据，`min(totalUpdates, buffer.length)` 计算准确平均值。帧时间值有 clamp 防止负数/过大值。 |
| `ServerPerformanceMonitor.java` | 服务端性能监控器。采集 TPS、MSPT、tick 耗时。`onTickStart()` / `onTickEnd()` 配对调用。 |
| `ClientMetricsCache.java` | 客户端指标缓存。线程安全的指标快照，供 HUD/F3 读取。 |
| `BottleneckAnalyzer.java` | 瓶颈分析器。分析 CPU/GPU/内存瓶颈，提供优化建议。 |
| `DeviceProfile.java` | 设备配置文件。CPU/GPU/内存规格检测和分级。 |

### 关键设计

- 帧时间值需要 clamp 防止负数或过大值腐蚀 FPS 计算
- CPU 使用率使用增量计算而非绝对时间戳除法
- 历史数据的并发读写需要同步防止数据竞态

---

## 21. Mixin 注入层 (`mixin/`)

### Mixin 配置 (`mixins.coresplit.json`)

```json
{
  "required": true,
  "package": "com.coresplit.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "ai.MobMixin",
    "ai.GoalSelectorMixin",
    "ai.PathNavigationMixin",
    "ai.BrainMixin",
    "explosion.ServerExplosionMixin",
    "memory.EntityMixin",
    "memory.ChunkSerializerMixin"
  ],
  "client": [
    "DebugScreenEntriesMixin",
    "KeyboardHandlerMixin",
    "explosion.ParticleEngineMixin",
    "render.ItemEntityRendererMixin",
    "render.LivingEntityRendererMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

### Mixin 数组说明

- **`mixins` 数组**：环境无关的 Mixin（目标类在客户端和服务端都存在）。包括 Mob、GoalSelector、PathNavigation、Brain、ServerExplosion、Entity、SerializableChunkData。
- **`client` 数组**：仅客户端加载的 Mixin（目标类只在客户端存在）。包括 Hud 相关、ParticleEngine、渲染器。

> **重要**：`server` 数组**仅**在专用服务端加载（FabricLoader env==SERVER），**不**在单人的内置服务端加载。环境无关的 Mixin 必须放在 `mixins` 数组中。

### 各 Mixin 说明

#### AI 相关 (`mixin/ai/`)

| 文件 | 注入目标 | 功能 |
|---|---|---|
| `MobMixin.java` | `Mob.serverAiStep()` HEAD | AI 节流入口。检查 `AiOptimizer.shouldManuallyDisableAi()` 和 `shouldTickAi()`，返回 false 时 `ci.cancel()` 短路 AI。**注入 `serverAiStep()` 而非 `tick()`**——cancel tick() 会破坏物理状态。 |
| `GoalSelectorMixin.java` | `GoalSelector` | 目标选择器优化。 |
| `PathNavigationMixin.java` | `PathNavigation` | 寻路缓存入口。注入寻路方法，查询/写入 `PathNavCache`。**Mixin 类只含 @Inject 钩子**，工具方法已提取到 `PathNavCache` 非 Mixin 类。 |
| `BrainMixin.java` | `Brain` | Brain 优化。 |

#### 爆炸相关 (`mixin/explosion/`)

| 文件 | 注入目标 | 功能 |
|---|---|---|
| `ServerExplosionMixin.java` | `ServerExplosion` | 服务端爆炸拦截。将爆炸提交到 `ExplosionOptimizer` 进行分帧处理。**注意**：MC 26.2 中 `ServerExplosion` 在 `net.minecraft.world.level.ServerExplosion`（非 `server.level`）。 |
| `ParticleEngineMixin.java` | `ParticleEngine`（客户端） | 粒子引擎优化。 |

#### 内存相关 (`mixin/memory/`)

| 文件 | 注入目标 | 功能 |
|---|---|---|
| `EntityMixin.java` | `Entity` | 实体数据管理优化。 |
| `ChunkSerializerMixin.java` | `SerializableChunkData.write()` | 区块序列化优化。**注意**：MC 26.2 中 `ChunkSerializer` 改名为 `SerializableChunkData`（同包 `net.minecraft.world.level.chunk.storage`）。**返回 CompoundTag 的非 void 方法必须用 `CallbackInfoReturnable`，不能用 `CallbackInfo`**。 |

#### 渲染相关 (`mixin/render/`)

| 文件 | 注入目标 | 功能 |
|---|---|---|
| `ItemEntityRendererMixin.java` | `ItemEntityRenderer`（客户端） | 掉落物渲染优化。 |
| `LivingEntityRendererMixin.java` | `LivingEntityRenderer`（客户端） | 生物渲染优化。 |

#### 其他 (`mixin/`)

| 文件 | 注入目标 | 功能 |
|---|---|---|
| `DebugScreenEntriesMixin.java` | F3 调试界面（客户端） | 注册 CoreSplit 自定义 F3 信息行。 |
| `KeyboardHandlerMixin.java` | `KeyboardHandler`（客户端） | 按键事件处理。 |

### Mixin 开发铁律

1. **Mixin 类只含注入钩子**（@Inject / @Redirect / @Shadow / @Overwrite）。所有非注入方法必须 `private`，否则触发 `InvalidMixinException`。工具方法提取到单独的非 Mixin 类。
2. **非 void 返回方法必须用 `CallbackInfoReturnable`**，用 `CallbackInfo` 会在 apply 阶段崩溃。
3. **`required: true` + `defaultRequire: 1`** 意味着 Mixin 应用失败会崩溃游戏。
4. **Mixin 变更必须通过 `runClient` / `runServer` 验证**——单元测试不覆盖 Mixin 类转换。
5. **用 `javap` 验证目标方法签名**——MC 26.2 的类映射与旧版本差异很大。
6. **`ci.cancel()` 前务必确认方法职责**——cancel `Mob.tick()` 会破坏物理状态，cancel `serverAiStep()` 只跳过 AI。

---

## 22. 其他模块

### 物品渲染 (`itemrender/`)

| 文件 | 职责 |
|---|---|
| `FrustumCuller.java` | 视锥体裁剪器。判断物品实体是否在相机视锥体内，跳过不可见实体的渲染。 |
| `ItemRenderConfig.java` | 物品渲染配置。 |
| `ItemRenderOptimizer.java` | 物品渲染优化器。根据距离和数量控制掉落物渲染。 |

### 粒子 (`particle/`)

| 文件 | 职责 |
|---|---|
| `ParticleFilter.java` | 粒子过滤器。根据距离和配置控制粒子生成和渲染。单例模式。 |

### 网络 (`network/`)

| 文件 | 职责 |
|---|---|
| `NetworkHandler.java` | 网络通信。维护服务端性能快照（TPS/MSPT），每 20 tick 更新一次。使用 `AtomicInteger` 计数器防止竞态，NaN/Infinity 过滤防止客户端解析失败。 |

---

## 23. 资源文件说明

### `fabric.mod.json`

Mod 描述文件。定义了：
- 入口点：`main`（CoreSplitMod）、`client`（CoreSplitClientMod）、`modmenu`（ModMenuCompat）
- Mixin 配置：`mixins.coresplit.json`
- 依赖：fabricloader ≥0.16.0、fabric-api *、minecraft ≥26.2、java ≥25
- 建议安装：modmenu、iris、sodium

### `mixins.coresplit.json`

Mixin 配置文件。`required: true` 表示 Mixin 应用失败会崩溃游戏。详见 [第 21 节](#21-mixin-注入层-mixin)。

### `coresplit-default.toml`

服务端默认配置。包含以下节：
- `[network]` — 网络指标开关
- `[monitoring]` — 监控开关、每 tick 日志
- `[compatibility]` — 兼容模式
- `[performance]` — governor 开关、目标 FPS、容差
- `[explosion]` — 爆炸优化开关、每帧/每 tick 上限、粒子级别
- `[ai]` — AI 优化开关、节流、路径缓存、桶半径
- `[memory]` — 内存优化开关、对象池大小、缓存上限
- `[device]` — 设备自动检测

### 语言文件

- `lang/en_us.json` — 英文翻译
- `lang/zh_cn.json` — 中文翻译

包含所有命令、配置、HUD 文本的翻译键。

### `icon.png`

Mod 图标。

---

## 24. 测试体系

### 测试文件列表（32 个）

| 目录 | 测试文件 | 测试内容 |
|---|---|---|
| `ai/` | EntityAiThrottleTest, PathCacheTest, PathKeyTest, SharedPathRegistryTest | AI 节流、路径缓存、缓存键、路径共享 |
| `chunk/` | ChunkEngineTest, ChunkTaskSchedulerPerfTest, EntityFilterPerfTest, ParallelChunkGeneratorPerfTest | 区块引擎、任务调度性能、实体过滤性能、并行生成性能 |
| `compat/` | IrisConfigSchemaTest, RateLimiterTest, RemoteSchemaFetcherTest, SchemaCacheTest | Iris schema、速率限制、远程拉取、schema 缓存 |
| `explosion/` | ExplosionBatcherTest, ExplosionBlockImpactTest, ExplosionParticleLimiterTest | 爆炸批处理、方块影响、粒子限制 |
| `governor/` | PerformanceGovernorTest | 性能管理器 |
| `logging/` | CSLoggerTest | 日志系统 |
| `memory/` | ByteBufferPoolTest, EntityDataPoolTest | 缓冲池、实体数据池 |
| `model/` | ModelSystemTest | 模型系统 |
| `monitoring/` | BottleneckAnalyzerTest, DeviceProfileTest, PerformanceMonitorPerfTest | 瓶颈分析、设备配置、性能监控 |
| `renderlimiter/gpu/` | GpuBackendTest, MockBackend | GPU 后端、模拟后端 |
| `scheduler/` | PrioritizedTaskSchedulerTest, ProfileManagerTest, SchedulerStressTest, TaskSchedulerTest | 优先级调度、配置文件、压力测试、任务调度 |
| `sound/` | SoundSystemTest | 声音系统 |
| `texture/` | TextureSystemTest | 纹理系统 |
| 根目录 | OptimizationComparisonBenchmark | 优化效果对比基准 |

### 测试注意事项

- 构造函数有参数钳制（如 `PathCache maxSize=Math.max(16,...)`），测试阈值时传值必须 ≥ 钳制下限
- 基准测试的 OLD-vs-NEW 对比必须给 OLD 基线一个真实的开销，空的 OLD 循环会让 NEW 看起来更慢
- `SchedulerStressTest` 需要 1ms 任务延迟避免不稳定性
- Mixin 类转换不被单元测试覆盖，必须通过 `runClient` / `runServer` 验证

---

## 25. 关键架构决策与教训

### MC 26.2 API 变更清单

1. `ClientCommandManager` → `ClientCommands`（同包，仍有 `literal()` 和 `argument()`）
2. `net.minecraft.commands.arguments.StringArgumentType` 不存在 → 使用 `com.mojang.brigadier.arguments.StringArgumentType`
3. `Minecraft.setScreen(Screen)` → `setScreenAndShow(Screen)`，无 public `screen` 字段或 `getScreen()` getter
4. `Minecraft.execute(Runnable)` 已移除 → 客户端命令已在主线程执行，直接调用 UI 代码
5. `Component.newline()` 已移除 → 使用 `Component.literal("\n")`
6. `HudRenderCallback` 已完全移除 → 使用 `HudElementRegistry.attachElementBefore/After`
7. `ServerExplosion` 从 `server.level` 包移到 `world.level` 包
8. `ChunkSerializer` 改名为 `SerializableChunkData`（同包）
9. `MultiBufferSource` 改名为 `SubmitNodeCollector`
10. 矩阵堆栈从 `PoseStack` 变为 `Matrix3x2fStack`（1.21.8 起）
11. `Identifier` 从 `net.minecraft.util` 移到 `net.minecraft.resources`

### 重要 Bug 修复历史

1. **MobMixin 注入点**：从 `Mob.tick()` 改为 `Mob.serverAiStep()`，避免 cancel 破坏物理状态
2. **PathNavigationMixin 工具方法**：从 Mixin 类提取到 `PathNavCache` 非 Mixin 类，避免 `InvalidMixinException`
3. **LRU 缓存键溢出**：从 `nanoTime << N` 改为 `AtomicLong` 单调计数器
4. **ChunkSerializerMixin 回调类型**：从 `CallbackInfo` 改为 `CallbackInfoReturnable`（目标方法返回 CompoundTag）
5. **HUD 渲染**：从 `HudRenderCallback` 迁移到 `HudElementRegistry`（API 被移除）
6. **/csset 命令**：`makeScreen(null)` 异常时返回 null 导致 NPE，改为返回 fallback Screen
7. **无界队列 OOM**：ExplosionBatcher 和 PrioritizedTaskScheduler 添加容量上限
8. **主线程 System.gc()**：移到专用低优先级后台线程
9. **SodiumCompatManager 编译错误**：readField 的 try-catch 缺少 IllegalAccessException
10. **Mixin config server 数组**：单人内置服务端不加载 server 数组的 Mixin，环境无关的 Mixin 放到 mixins 数组

### 工程约定

- 常量集中定义，避免魔法数字
- 线程共享变量使用 `volatile` 或 `AtomicXxx`
- 缓存使用 TTL + 同步防止竞态
- 大方法拆分为小方法
- 配置持久化使用 TOML 格式
- 线程池使用 `ThreadPoolExecutor` + 优雅关闭
- 内存密集对象（ByteBuffer）使用对象池复用
- LRU 使用 `ConcurrentHashMap` + `ConcurrentSkipListMap` 无锁实现
- 线程共享数据使用 `ConcurrentLinkedQueue`（非 ArrayList）防止 CME
- 字段声明使用接口类型（Map 而非 ConcurrentHashMap）
- 暴露内部数组引用时使用防御性拷贝（clone()）
- 日志告警实现令牌桶限流（≤10/10秒）防止日志风暴

---

## 26. 后续开发计划

### 已知待改进项

1. **GPU 加速后端完善**：CUDA/OpenCL/Vulkan 后端目前为实验性，需要更多真实场景测试和性能基准
2. **模型系统**：CEM/JEM 模型加载器需要更多模型格式兼容性测试
3. **声音系统**：声音变体和动画集成需要更多实体类型支持
4. **网络协议**：NetworkHandler 目前只传输 TPS/MSPT 快照，可扩展为双向配置同步
5. **远程 Schema**：可扩展支持更多模组的配置 schema 拉取

### 功能扩展方向

1. **更多兼容模组**：支持 OptiFine、Continuity、Indium 等渲染模组的深度兼容
2. **配置预设导入/导出**：允许用户分享性能配置预设
3. **历史数据图表**：在配置界面展示 FPS/TPS 历史图表
4. **自动诊断**：BottleneckAnalyzer 检测到瓶颈时自动建议配置调整
5. **服务端多世界优化**：为多世界服务器提供按世界独立的优化策略

### 技术债务

1. **CompatibilityDetector vs RenderCompatDetector**：两个检测器功能重叠，可合并
2. **MiniHudInfoLine**：已停用但未删除，可清理
3. **ModelDebugger**：开发调试工具，发布版应考虑移除或隐藏
4. **部分模块缺少单元测试**：sound、texture、model、itemrender、particle、network 模块测试覆盖不足
5. **配置冗余**：CoreSplitConfig（服务端 TOML）和 CoreSplitYaclConfig（客户端 YACL）部分配置项重叠，可统一

### 版本升级注意事项

升级到新 MC 版本时需要检查：

1. **Mixin 目标类映射**：用 `javap -p` 验证 loom 缓存中的 `minecraft-merged.jar` 类和方法签名
2. **Fabric API 变更**：检查 `fabric-rendering-v1` 等子模块的 API 是否被移除/重命名
3. **YACL API 变更**：检查 `YetAnotherConfigLib` 的 API 是否变化
4. **渲染管线变更**：MC 26.2 的 `RenderState` 提取模型可能导致渲染相关代码需要适配
5. **命令 API 变更**：检查 `ClientCommands` / `Commands` 的方法签名

---

## 附录：快速定位指南

### 常见任务 → 关键文件

| 任务 | 文件 |
|---|---|
| 添加客户端命令 | `command/CSCommands.java` |
| 添加服务端命令 | `command/CSServerCommands.java` |
| 修改 HUD 显示内容 | `hud/DefaultHudProviders.java`, `overlay/CoreSplitOverlay.java` |
| 修改 HUD 渲染方式 | `CoreSplitClientMod.java`（HudElementRegistry 注册）, `hud/CSHudRenderer.java` |
| 添加配置项 | `config/CoreSplitYaclConfig.java`（客户端）或 `config/CoreSplitConfig.java`（服务端） |
| 修改 Mixin 注入 | `mixin/` 对应文件 + `mixins.coresplit.json` |
| 添加翻译文本 | `resources/assets/coresplit/lang/en_us.json` + `zh_cn.json` |
| 修改 AI 节流策略 | `ai/EntityAiThrottle.java`, `ai/AiOptimizer.java` |
| 修改爆炸处理 | `explosion/ExplosionOptimizer.java`, `explosion/ExplosionBatcher.java` |
| 修改性能调节策略 | `governor/PerformanceGovernor.java` |
| 修改 Iris/Sodium 兼容 | `compat/IrisCompatManager.java`, `compat/SodiumCompatManager.java`, `compat/CompatCoordinator.java` |
| 修改日志行为 | `logging/CSLogger.java` |
| 修改 GPU 加速 | `renderlimiter/gpu/GpuBackendRegistry.java` + 对应后端实现 |

### 全局单例访问点

| 单例 | 获取方式 |
|---|---|
| AiOptimizer | `AiOptimizer.getInstance()` 或 `CoreSplitMod.getAiOptimizer()` |
| ExplosionOptimizer | `ExplosionOptimizer.getInstance()` 或 `CoreSplitMod.getExplosionOptimizer()` |
| MemoryOptimizer | `MemoryOptimizer.getInstance()` 或 `CoreSplitMod.getMemoryOptimizer()` |
| PrioritizedTaskScheduler | `CoreSplitMod.getPrioritizedScheduler()` |
| PerformanceGovernor | `CoreSplitClientMod.getGovernor()` |
| TaskScheduler | `CoreSplitClientMod.getScheduler()` |
| ResourceMonitor | `CoreSplitClientMod.getResourceMonitor()` |
| ChunkEngine | `ChunkEngine.getInstance()` 或 `CoreSplitClientMod.getChunkEngine()` |
| CSLogger | `CSLogger.getInstance()` |
| CSHudApi | `CSHudApi.getInstance()` |
| GpuBackendRegistry | `GpuBackendRegistry.getInstance()` |
| EntityRenderLimiter | `EntityRenderLimiter.getInstance()` |
| ParticleFilter | `ParticleFilter.getInstance()` |
| DynamicMemoryOptimizer | `DynamicMemoryOptimizer.getInstance()` |
| CompatCoordinator | `CompatCoordinator.getInstance()` |
| RenderCompatDetector | `RenderCompatDetector.getInstance()` |
| ConflictManager | `ConflictManager.getInstance()` |
| ModuleManager | `ModuleManager.getInstance()` 或 `CoreSplitMod.getModuleManager()` |

---

> **文档维护说明**：每次重大变更后请更新本文档对应章节。特别是新增/删除文件、API 迁移、架构决策变更时，务必同步更新。
