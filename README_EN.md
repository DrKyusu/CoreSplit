<p align="center">
  <img src="https://img.shields.io/badge/Version-2.5.0-8A2BE2" alt="Version">
  <img src="https://img.shields.io/badge/Minecraft-%3E%3D%2026.2-00AA00" alt="Minecraft">
  <img src="https://img.shields.io/badge/Java-%3E%3D%2025-ED8B00" alt="Java">
  <img src="https://img.shields.io/badge/Fabric%20Loader-%3E%3D%200.16.0-dbd0b4" alt="Fabric Loader">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="License">
</p>

<h1 align="center">✦ CoreSplit ✦</h1>
<h3 align="center"><i>～ The magical girl who makes the world run smoothly ～</i></h3>

<p align="center">
  <img src="https://img.shields.io/badge/✨%20FPS%20Boost-Overclocked-FFD700" alt="FPS Boost">
  <img src="https://img.shields.io/badge/🎀%20Kawaii-Max%20Moe-FF69B4" alt="Kawaii">
  <img src="https://img.shields.io/badge/💜%20Moe%20Optimizer-Power%20Up%20%21-8A2BE2" alt="Moe Optimizer">
</p>

<p align="center">
  Author: DR.Kyusu &nbsp;|&nbsp;
  <b>English</b> &nbsp;|&nbsp; <a href="README.md">中文</a>
</p>

<div align="center">

```
　∧ ∧
 (´･ω･)  ── CoreSplit reporting in! ♪
 /⌒　⌒ヽ  ⚡ Lag be gone, FPS be high ⚡
```

</div>

> <sub>（o´▽`o）ノ Hi there! I'm CoreSplit, a little maid living quietly inside your blocky world, chasing all the lag away♪<br>As long as I'm here, the magic of high FPS and low latency will keep watching over you!</sub>

---

CoreSplit is a comprehensive performance optimization mod for **Minecraft: Java Edition** built on the Fabric framework. It targets every layer of the game pipeline — AI, chunk generation, rendering, memory, GPU compute, and shader compatibility — to deliver higher frame rates, lower latency, and stable memory usage under heavy load.

---

## 📖 Table of Contents

| | |
|------|------|
| [1. Overview](#1-overview) | [4. Compatibility](#4-compatibility) |
| [2. Core Modules](#2-core-modules) | [5. Installation](#5-installation) |
| [3. Configuration](#3-configuration) | [🔗 Links](#-links) |

---

## 1. 🌙 Overview

CoreSplit is designed as a **multi-layered optimization framework**. Each subsystem can be enabled, tuned, or disabled independently through a unified configuration UI. The mod automatically adapts to the host hardware via device profiling and bottleneck analysis, applying the right strategy for different scenarios.

**✨ Key design principles:**

| Principle | Description |
|-----------|-------------|
| 🛡️ Thread-safe by default | All shared state uses `volatile`, `Atomic*`, or concurrent collections |
| ⛓️ Bounded everywhere | Thread pool queues, caches, and batchers enforce capacity limits to prevent OOM |
| 🧵 Non-blocking main thread | Heavy work runs on dedicated background threads |
| 🎭 Program to interfaces | Field declarations prefer `Map`/`Queue` over concrete implementations |

---

## 2. 🎀 Core Modules

### 2.1 🧠 AI Optimization

Reduces the CPU cost of entity AI ticking without breaking vanilla behavior.

| Feature | Description |
|---------|-------------|
| **Entity AI Throttle** | Distance-based tick buckets (near/mid/far/offscreen). Far entities tick less; offscreen entities can be fully paused. |
| **Path Cache** | LRU + TTL cache keyed by entity type and chunk coordinates. Evicts oldest on overflow. |
| **Shared Path Registry** | Same-type entities in the same area share paths, avoiding redundant A\* computation. |
| **Spatial Index** | Buckets entities by distance for O(1) throttle classification. |
| **Global Multiplier** | Clamped `[0, 1]` scalar multiplied by tick frequency — set to `0` to pause all AI. |

> 💡 The throttle injects into `Mob.serverAiStep()` (not `tick()`), so physics, movement, and interpolation continue normally while only goal/brain/controls work is throttled.

### 2.2 🗺️ Chunk Engine

Parallelizes chunk generation and async I/O.

| Feature | Description |
|---------|-------------|
| **Parallel Chunk Generator** | Multi-threaded terrain noise generation with a prioritized task scheduler. |
| **Async Chunk I/O** | Non-blocking chunk read/write to disk, tracking active operations. |
| **Chunk Task Scheduler** | Priority queue with cancellation support and active task tracking. |
| **Entity Filter** | Range-based entity queries used by the chunk engine and AI throttle. |

### 2.3 💥 Explosion Optimization

Prevents explosions from causing frame drops and particle storms.

| Feature | Description |
|---------|-------------|
| **Explosion Batcher** | Bounded `PriorityBlockingQueue` processing per frame and per tick, nearest first. |
| **Async Explosion Processor** | Offloads explosion computation off the main thread. |
| **Block Impact Skip** | Skips impact checks for blocks beyond a configurable 3D distance. |
| **Particle Limiter** | Caps particles per event at LOW(8) / MEDIUM(32) / HIGH(128). |

### 2.4 💾 Memory Management

The most aggressive subsystem — directly tackles high memory usage under shader workloads.

| Feature | Description |
|---------|-------------|
| **ByteBuffer Pool** | Bucketed pool reusing `ByteBuffer` instances by power-of-two capacity, eliminating GC pressure. 32 per bucket max. Togglable at runtime. |
| **Entity Data Pool** | Object pool with factory + resetter pattern, thread-safe. |
| **Resource Evictor** | Periodic cache eviction with configurable interval and dynamic target size. |
| **Heap Defragmenter** | Runs on a low-priority background thread to avoid main-thread pauses. |
| **Dynamic Memory Optimizer** | Triggers cleanup on `CoreSplit-MemoryCleaner` — never calls `System.gc()` or `Thread.sleep()`. |
| **Entity Data Unloader** | Unloads entity data beyond a configurable distance. |
| **Shader Memory Optimizer** | Activates only with Iris shaders: halves texture cache, evicts every 30s, restores on disable. |

### 2.5 ⚡ GPU Acceleration

Pluggable GPU compute backends with automatic health monitoring and CPU fallback.

| Backend | Status | Notes |
|---------|--------|-------|
| **OpenGL** | Default | Always available |
| **Vulkan** | Experimental | Reflection-loaded |
| **OpenCL** | Experimental | Reflection-loaded |
| **CUDA** | Experimental | Requires NVIDIA GPU + CUDA toolkit |
| **CPU Fallback** | Fallback | Used when no GPU or backend degrades |

> ⚠️ Backends are **mutually exclusive** — enabling one disables others. The registry falls back to CPU on:
> - 🔻 Sustained slow tasks (> 100ms)
> - 💔 3+ consecutive failures
> - 📉 Success rate below 80%

### 2.6 🎨 Render Limiter

| Feature | Description |
|---------|-------------|
| **Entity Render Limiter** | Caps rendered entities per frame; tighter under Iris/Sodium. |
| **Frustum Culler** | Culls item renders outside the view frustum. |
| **Item Render Optimizer** | Tunable item-render distance and rate. |
| **GPU Monitor** | Tracks GPU task latency and success rate. |

### 2.7 🎚️ Performance Governor

Dynamically adjusts render quality based on live FPS, MSPT, and CPU usage.

- ⭐ **Quality levels** 0–5 with monotonic transitions
- 🧮 **Frame-time values** clamped to prevent NaN/Inf corruption
- 🚀 **High FPS** (> 200): interval extends to 5s with consecutive trend detection
- 🌈 **Under shaders**: supports forced V-Sync and GPU-overload detection

### 2.8 🌟 Shader Compatibility

Deep integration with Iris and Sodium — only active when those mods are loaded.

| Feature | Description |
|---------|-------------|
| **Shader Performance Optimizer** | Clamps render distance, forces V-Sync, reduces particles/entities. Auto-restores on disable. |
| **Shader Memory Optimizer** | See [Memory Management](#24-memory-management) |
| **Compat Coordinator** | Central coordinator clamping render distance across all shader-aware modules. |
| **Iris/Sodium Config Schema** | Parses config files with cached remote schema fetching (dual-switch, async, rate-limited). |
| **Reflection Caching** | Caches Sodium field reflections in `ConcurrentHashMap` to avoid repeated calls. |
| **Conflict Manager** | Detects known mod conflicts via `FabricLoader.getInstance().isModLoaded()`. |

### 2.9 🧸 Model & Texture System

| Component | Description |
|-----------|-------------|
| **Animation Controller / State Manager / Optimizer** | Manages animation variables, LOD, and interpolation |
| **JEM File Parser** | Parses OptiFine-format JEM/JPM entity models |
| **Player Model Handler** | Bone-transform management for player models |
| **Block Entity Model Adapter** | Manages block-entity render states |
| **Texture Cache** | LRU cache with configurable max size |
| **Emissive Renderer** | Processes emissive texture data |
| **Player Skin Enhancer** | Clamped transparency handling for player skins |
| **OptiFine Properties Parser** | Parses biome/animation/skin properties |

### 2.10 🎵 Entity Sound System

| Component | Description |
|-----------|-------------|
| **Entity Sound System** | Full play → fade → stop lifecycle |
| **Sound Variant Manager** | Switches variants based on entity texture and probabilistic conditions |
| **Sound-Driven Visual Manager** | Applies/reverts visual changes tied to sound events |
| **Sound Animation Integrator** | Fade in/out, state transitions, and `playsound` command processing |

### 2.11 📊 Monitoring & Diagnostics

| Tool | Description |
|------|-------------|
| **Performance Monitor** | Live FPS, MSPT, frame-time, and CPU metrics |
| **Bottleneck Analyzer** | Classifies as `NONE` / `CPU_BOUND` / `GPU_BOUND` / `ENTITY_BOUND` / `MEMORY_BOUND` |
| **Device Profile** | Detects hardware tier, CPU cores, and memory; recommends a preset |
| **Client/Server Metrics Cache** | Bounded caches (256–1024 entries) to prevent leaks |
| **Debug Overlay** | F3 overlay with CoreSplit debug entries, keybinds, and GPU bottleneck alert |

### 2.12 ⏰ Scheduler

| Component | Description |
|-----------|-------------|
| **Task Scheduler** | Configurable thread pool (cores × multiplier, clamped 1–4×), graceful shutdown |
| **Prioritized Task Scheduler** | Bounded queue + `CallerRunsPolicy`, counted in `finally` block |
| **Profile Manager** | Save/load/activate named profiles; sanitizes names and prevents duplicates |
| **Resource Monitor** | Tracks system resource usage |

---

## 3. ⚙️ Configuration

CoreSplit ships with a unified config UI powered by **YACL**, accessible via **ModMenu**. The config file is stored as `coresplit_client.toml`.

| Category | Options |
|----------|---------|
| **Engine** | Core optimization toggles and mode |
| **Overlay** | Debug HUD and keybinds |
| **Performance** | Governor, FPS targets, and quality levels |
| **Compatibility** | Iris/Sodium deep-compat switches and remote schema fetch |
| **Scheduler** | Thread pool sizing |
| **Render** | Entity render limits, frustum culling, item render |
| **GPU** | Backend selection, CUDA toggle, health-monitor thresholds |
| **Shader Optimization** (10) | Master switch, force V-Sync, reduce particles, reduce entities, limit render distance, aggressive memory release, shadow distance margin, max render distance cap, particle render distance, entity multiplier |
| **Memory Management** (6) | Master switch, ByteBuffer pool toggle, texture cache limit, entity data pool size, cache eviction interval, aggressive eviction threshold |

> 📝 All setters perform three actions: update the field → apply at runtime → `save()` to disk. Range validation is enforced on every numeric input.

---

## 4. 🤝 Compatibility

### Required dependencies

| Dependency | Version |
|------------|---------|
| Fabric Loader | >= 0.16.0 |
| Fabric API | Latest |
| Minecraft | >= 26.2 |
| Java | >= 25 |

### Suggested (optional but recommended)

| Mod | Purpose |
|-----|---------|
| 💜 ModMenu | In-game config screen |
| 🌌 Iris | Triggers shader-aware optimizations |
| 🔮 Sodium | Triggers field-reflection optimizations |

> 🎯 CoreSplit detects loaded mods at startup and gracefully no-ops features whose target mod is absent. All client-side classes use null checks and exception handling to prevent server-side crashes.

---

## 5. 📦 Installation

1. 🧱 Install **Fabric Loader** 0.16.0 or newer
2. 🔧 Install **Fabric API**
3. 🎒 Drop `coresplit-2.5.0.jar` into your `mods/` folder
4. 💜 (Recommended) Install **ModMenu** to access the config screen
5. 🌌 (Optional) Install **Iris** and **Sodium** for shader-aware optimizations
6. ▶️ Launch the game — CoreSplit auto-profiles your hardware and applies a recommended preset

---

## 🔗 Links

| | |
|------|------|
| 🏠 **Homepage** | <https://github.com/DrKyusu/CoreSplit> |
| 📮 **Issues** | <https://github.com/DrKyusu/CoreSplit/issues> |
| 📜 **Source** | <https://github.com/DrKyusu/CoreSplit> |

---

<div align="center">

```
　 ／￣￣￣＼
 ／　(´・ω・)　＼  Today's optimization is done~
￣￣￣￣￣￣￣￣￣￣
　　 ｜　　｜
```

**~ May you enjoy every journey with buttery-smooth FPS ~**

</div>

<p align="center">
  <i>CoreSplit is licensed under AGPL-3.0</i><br>
  <b>English</b> &nbsp;|&nbsp; <a href="README.md">中文</a>
</p>
