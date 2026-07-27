# CoreSplit

**Version:** 2.5.0
**Author:** DR.Kyusu
**License:** AGPL-3.0
**Minecraft:** >= 26.2
**Java:** >= 25
**Fabric Loader:** >= 0.16.0

> That mod can optimize the client. If CoreSplit is installed on the server, it can provide a better experience.

CoreSplit is a comprehensive performance optimization mod for Minecraft: Java Edition built on the Fabric framework. It targets every layer of the game pipeline — AI, chunk generation, rendering, memory, GPU compute, and shader compatibility — to deliver higher frame rates, lower latency, and stable memory usage under heavy load.

---

## Table of Contents

1. [Overview](#overview)
2. [Core Modules](#core-modules)
   - [AI Optimization](#ai-optimization)
   - [Chunk Engine](#chunk-engine)
   - [Explosion Optimization](#explosion-optimization)
   - [Memory Management](#memory-management)
   - [GPU Acceleration](#gpu-acceleration)
   - [Render Limiter](#render-limiter)
   - [Performance Governor](#performance-governor)
   - [Shader Compatibility](#shader-compatibility)
   - [Model & Texture System](#model--texture-system)
   - [Entity Sound System](#entity-sound-system)
   - [Monitoring & Diagnostics](#monitoring--diagnostics)
   - [Scheduler](#scheduler)
3. [Configuration](#configuration)
4. [Compatibility](#compatibility)
5. [Installation](#installation)

---

## Overview

CoreSplit is designed as a multi-layered optimization framework. Each subsystem can be enabled, tuned, or disabled independently through a unified configuration UI. The mod automatically adapts to the host hardware via device profiling and bottleneck analysis, applying the right strategy for CPU-bound, GPU-bound, entity-bound, or memory-bound scenarios.

Key design principles:

- **Thread-safe by default** — all shared state uses `volatile`, `Atomic*`, or concurrent collections.
- **Bounded everywhere** — thread pool queues, caches, and batchers all enforce capacity limits to prevent OOM.
- **Non-blocking main thread** — heavy work (memory cleanup, schema fetch, GPU tasks) runs on dedicated background threads.
- **Program to interfaces** — field declarations prefer `Map` / `Queue` over concrete implementations.

---

## Core Modules

### AI Optimization

Reduces the CPU cost of entity AI ticking without breaking vanilla behavior.

| Feature | Description |
|---------|-------------|
| **Entity AI Throttle** | Distance-based AI tick buckets (near / mid / far / offscreen). Far entities tick less frequently; offscreen entities can be fully paused. |
| **Path Cache** | LRU + TTL cache of computed entity paths, keyed by entity type and chunk coordinates. Evicts oldest entries on capacity overflow. |
| **Shared Path Registry** | Allows entities of the same type in the same area to reuse a shared path, avoiding redundant A* recomputation. |
| **Spatial Index** | Buckets entities by distance for O(1) throttle classification instead of per-entity distance scans. |
| **Global Multiplier** | A clamped `[0, 1]` scalar that multiplies tick frequency — set to `0` to pause all AI. |

The throttle injects into `Mob.serverAiStep()` (not `tick()`), so physics, movement, and interpolation continue to run normally while only goal/brain/controls work is throttled.

### Chunk Engine

Parallelizes chunk generation and async I/O.

| Feature | Description |
|---------|-------------|
| **Parallel Chunk Generator** | Multi-threaded terrain noise generation with a prioritized task scheduler. |
| **Async Chunk I/O** | Non-blocking chunk read/write to disk, tracking active operations. |
| **Chunk Task Scheduler** | Priority queue with cancellation support and active task tracking. |
| **Entity Filter** | Range-based entity queries with managed entity sets, used by the chunk engine and AI throttle. |

### Explosion Optimization

Prevents explosions from causing frame drops and particle storms.

| Feature | Description |
|---------|-------------|
| **Explosion Batcher** | Bounded `PriorityBlockingQueue` (capped to prevent OOM) that batches explosion processing per frame and per tick. Closer explosions are processed first. |
| **Async Explosion Processor** | Offloads explosion computation off the main thread. |
| **Block Impact Skip** | Skips block-impact checks for blocks beyond a configurable 3D distance threshold. |
| **Particle Limiter** | Caps explosion particles per event at LOW (8) / MEDIUM (32) / HIGH (128) levels. |

### Memory Management

The most aggressive subsystem — directly tackles the high-memory-usage problem under shader workloads.

| Feature | Description |
|---------|-------------|
| **ByteBuffer Pool** | Bucketed pool that reuses `ByteBuffer` instances by power-of-two capacity, eliminating per-allocation GC pressure. Each bucket caps at 32 buffers. Can be enabled/disabled at runtime. |
| **Entity Data Pool** | Object pool for entity data with factory + resetter pattern; failed resets prevent recycling. Thread-safe concurrent acquire/release. |
| **Resource Evictor** | Periodic cache eviction with a configurable interval and a dynamic texture-cache target size. |
| **Heap Defragmenter** | Runs on a low-priority background thread to avoid main-thread pauses. |
| **Dynamic Memory Optimizer** | Triggers cleanup on a dedicated `CoreSplit-MemoryCleaner` thread — **never** calls `System.gc()` or `Thread.sleep()` on the client tick thread. |
| **Entity Data Unloader** | Unloads entity data for entities beyond a configurable distance. |
| **Shader Memory Optimizer** | Activates only when Iris shaders are on: halves the texture-cache target, runs aggressive eviction every 30 s, and restores original sizes when shaders are disabled. |

### GPU Acceleration

Pluggable GPU compute backends with automatic health monitoring and CPU fallback.

| Backend | Status |
|---------|--------|
| **OpenGL** | Default, always available. |
| **Vulkan** | Experimental, reflection-loaded. |
| **OpenCL** | Experimental, reflection-loaded. |
| **CUDA** | Experimental, requires NVIDIA GPU + CUDA toolkit. |
| **CPU Fallback** | Always available; used when no GPU backend initializes or when a backend degrades. |

Backends are **mutually exclusive** — enabling one automatically disables the others. The registry monitors backend health and falls back to CPU on:

- Sustained slow tasks (> 100 ms)
- 3+ consecutive failures
- Success rate below 80%

### Render Limiter

| Feature | Description |
|---------|-------------|
| **Entity Render Limiter** | Caps the number of rendered entities per frame, with a special tighter cap under Iris/Sodium. |
| **Frustum Culler** | Culls item renders outside the view frustum. |
| **Item Render Optimizer** | Tunable item-render distance and rate. |
| **GPU Monitor** | Tracks GPU task latency and success rate for health monitoring. |

### Performance Governor

Dynamically adjusts render quality based on live FPS, MSPT, and CPU usage.

- Quality levels 0–5 with monotonic transitions.
- Frame-time values are clamped to prevent NaN/Inf from corrupting FPS calculations.
- Under high-FPS scenarios (> 200 fps), adjustment interval extends to 5 s and requires consecutive trend detection to avoid frequent quality flips.
- Supports forced V-Sync and GPU-overload detection under shaders.

### Shader Compatibility

Deep integration with Iris and Sodium — only active when those mods are loaded.

| Feature | Description |
|---------|-------------|
| **Shader Performance Optimizer** | When shaders are active: clamps render distance to shadow distance + margin, forces V-Sync, reduces particle/entity render distance, and lowers the entity multiplier. All changes auto-restore when shaders are disabled. |
| **Shader Memory Optimizer** | See [Memory Management](#memory-management). |
| **Compat Coordinator** | Central coordinator that clamps render distance and orchestrates all shader-aware modules. |
| **Iris / Sodium Config Schema** | Parses Iris/Sodium config files with cached remote schema fetching (dual-switch controlled, async, rate-limited). |
| **Reflection Caching** | Sodium field reads are cached in a `ConcurrentHashMap` to avoid repeated `getDeclaredField` + `setAccessible` calls. |
| **Conflict Manager** | Detects known mod conflicts via `FabricLoader.getInstance().isModLoaded()`. |

### Model & Texture System

- **Animation Controller / State Manager / Optimizer** — manages animation variables, LOD, and interpolation.
- **JEM File Parser** — parses OptiFine-format JEM/JPM entity models.
- **Player Model Handler** — bone-transform management for player models.
- **Block Entity Model Adapter** — manages block-entity render states.
- **Texture Cache** — LRU texture cache with a configurable max size, used by the resource evictor.
- **Emissive Renderer** — processes emissive texture data.
- **Player Skin Enhancer** — clamped transparency handling for player skins.
- **OptiFine Properties Parser** — parses biome/animation/skin properties.

### Entity Sound System

- **Entity Sound System** — plays entity-aware sounds with full play → fade → stop lifecycle.
- **Sound Variant Manager** — switches sound variants based on entity texture and probabilistic conditions.
- **Sound-Driven Visual Manager** — applies and reverts visual changes (e.g. texture, animation) tied to sound events.
- **Sound Animation Integrator** — fade in/out, state transitions, and `playsound` command processing.

### Monitoring & Diagnostics

| Tool | Description |
|---------|-------------|
| **Performance Monitor** | Live FPS, MSPT, frame-time, and CPU metrics with incremental CPU-usage computation. |
| **Bottleneck Analyzer** | Classifies the current bottleneck as `NONE`, `CPU_BOUND`, `GPU_BOUND`, `ENTITY_BOUND`, or `MEMORY_BOUND` (memory takes priority). |
| **Device Profile** | Detects hardware tier, CPU cores, and memory; recommends a matching preset. |
| **Client / Server Metrics Cache** | Bounded metric caches (256–1024 entries) to prevent memory leaks. |
| **Debug Overlay** | F3 overlay with CoreSplit's own debug entries, keybinds, and a GPU bottleneck alert. |

### Scheduler

| Component | Description |
|---------|-------------|
| **Task Scheduler** | Configurable thread pool (cores × multiplier, clamped to 1–4× core count) with graceful shutdown. |
| **Prioritized Task Scheduler** | Bounded queue + `CallerRunsPolicy` for saturation handling, with completed-task counting in a `finally` block. |
| **Profile Manager** | Save / load / activate named optimization profiles; sanitizes profile names and prevents duplicates. |
| **Resource Monitor** | Tracks system resource usage for the scheduler. |

---

## Configuration

CoreSplit ships with a unified config UI powered by YACL, accessible via ModMenu. The config file is stored as `coresplit_client.toml`.

Configuration sections:

- **Engine** — core optimization toggles and mode.
- **Overlay** — debug HUD and keybinds.
- **Performance** — governor, FPS targets, and quality levels.
- **Compatibility** — Iris / Sodium deep-compat switches and remote schema fetch.
- **Scheduler** — thread pool sizing.
- **Render** — entity render limits, frustum culling, item render.
- **GPU** — backend selection, CUDA toggle, health-monitor thresholds.
- **Shader Optimization** — shader-aware performance tuning (10 options):
  - Master switch, force V-Sync, reduce particles, reduce entities, limit render distance, aggressive memory release, shadow distance margin, max render distance cap, particle render distance, entity multiplier.
- **Memory Management** — memory strategy (6 options):
  - Master switch, ByteBuffer pool toggle, texture cache limit, entity data pool size, cache eviction interval, aggressive eviction threshold.

All setters perform three actions: update the field value, call the corresponding functional class to apply the setting at runtime, and call `save()` to persist to disk. Range validation is enforced on every numeric input.

---

## Compatibility

**Required dependencies:**
- Fabric Loader >= 0.16.0
- Fabric API
- Minecraft >= 26.2
- Java >= 25

**Suggested (optional but recommended):**
- ModMenu — for the in-game config screen.
- Iris — triggers shader-aware optimizations.
- Sodium — triggers field-reflection optimizations.

CoreSplit detects loaded mods at startup and gracefully no-ops features whose target mod is absent. All client-side classes are accessed with runtime null checks and exception handling to prevent server-side crashes.

---

## Installation

1. Install **Fabric Loader** 0.16.0 or newer.
2. Install **Fabric API**.
3. Drop `coresplit-2.5.0.jar` into your `mods/` folder.
4. (Recommended) Install **ModMenu** to access the config screen.
5. (Optional) Install **Iris** and **Sodium** for shader-aware optimizations.
6. Launch the game. CoreSplit auto-profiles your hardware and applies a recommended preset.


---

## Links

- **Homepage:** https://github.com/DrKyusu/CoreSplit
- **Issues:** https://github.com/DrKyusu/CoreSplit/issues
- **Sources:** https://github.com/DrKyusu/CoreSplit

---

*CoreSplit is licensed under AGPL-3.0.*