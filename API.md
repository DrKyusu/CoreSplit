# CoreSplit CS HUD Integration API

> **Version:** 2.5.0 · **Minecraft:** 26.2 · **Fabric:** API v3

CoreSplit provides a MiniHUD-style always-on text overlay called **CS HUD**. This document describes how other mods can register custom text lines on the CS HUD via the public `CSHudApi`.

---

## Table of Contents

1. [Quick Start](#1-quick-start)
2. [Core API Classes](#2-core-api-classes)
3. [Implementing a Provider](#3-implementing-a-provider)
4. [Registering / Unregistering](#4-registering--unregistering)
5. [Priority & Ordering](#5-priority--ordering)
6. [HudLine Colors & Alignment](#6-hudline-colors--alignment)
7. [Thread Safety & Performance](#7-thread-safety--performance)
8. [User Configuration](#8-user-configuration)
9. [Complete Example](#9-complete-example)
10. [Built-in Providers](#10-built-in-providers)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Quick Start

```java
import com.coresplit.hud.*;

public class MyModHudProvider implements HudLineProvider {
    @Override
    public String getProviderId() { return "mymod:status"; }

    @Override
    public List<HudLine> getLines() {
        return List.of(
            HudLine.of("MyMod: Active", 0x55FF55)
        );
    }
}

// In your client initializer:
CSHudApi.getInstance().register(new MyModHudProvider());
```

That's it — your text will appear on the CS HUD overlay starting from the next frame.

---

## 2. Core API Classes

All API classes are in the `com.coresplit.hud` package:

| Class | Purpose |
|-------|---------|
| `HudLineProvider` | **Interface** — implement this to provide text lines. |
| `HudLine` | Immutable value object representing a single rendered line (text + color + alignment). |
| `CSHudApi` | Singleton registry — `register()` / `unregister()` providers. |
| `CSHudRenderer` | Internal renderer — collects lines from all providers and draws them. You don't call this directly. |
| `DefaultHudProviders` | CoreSplit's built-in providers (FPS, coordinates, etc.). Registered automatically. |

### Dependency

CoreSplit must be loaded as a mod. Add it to your `fabric.mod.json` dependencies:

```json
{
  "depends": {
    "coresplit": ">=2.5.0"
  }
}
```

---

## 3. Implementing a Provider

Implement `HudLineProvider`. There are three methods, two of which have defaults:

```java
public interface HudLineProvider {
    String getProviderId();                    // REQUIRED — unique ID
    List<HudLine> getLines();                  // REQUIRED — lines to render
    default boolean isVisible() { return true; }     // OPTIONAL — skip if false
    default String getDisplayName() { ... }          // OPTIONAL — for debug listings
}
```

### Key rules

- **`getProviderId()`** must return a non-null, non-empty, unique string. Convention: `"modid:purpose"` (e.g. `"magicmod:mana"`). Duplicate IDs are rejected at registration.
- **`getLines()`** must never return `null`. Return an empty list when there's nothing to show.
- **`isVisible()`** is checked every frame before `getLines()`. Return `false` to skip your provider entirely (e.g. when the player has no active potions).

---

## 4. Registering / Unregistering

### Register (at client init)

```java
@Override
public void onInitializeClient() {
    CSHudApi.getInstance().register(new MyModHudProvider());
}
```

### Register with custom priority

```java
CSHudApi.getInstance().register(new MyModHudProvider(), 50);  // appears above default priority
```

### Unregister (at client shutdown)

```java
// By reference:
CSHudApi.getInstance().unregister(myProviderInstance);

// By ID:
CSHudApi.getInstance().unregister("mymod:status");
```

### Unregister all (cleanup)

```java
CSHudApi.getInstance().clear();
```

> **Note:** If your mod is loaded/unloaded dynamically (e.g. via a mod menu), always unregister on removal to prevent stale providers from being called.

---

## 5. Priority & Ordering

Providers are sorted by **ascending priority** — lower numbers render first (higher on screen).

| Priority | Used by | Typical content |
|----------|---------|-----------------|
| 0 | `coresplit:performance` | FPS, frame time, memory |
| 10 | `coresplit:player` | XYZ, block pos, dimension, facing |
| 20 | `coresplit:world` | Time of day |
| 30 | `coresplit:modules` | Governor/scheduler status |
| 100 | External mods (default) | Custom info |

To appear **above** CoreSplit's built-in lines, register with priority < 50:
```java
CSHudApi.getInstance().register(provider, 5);  // appears at the very top
```

To appear **below** all built-in lines, use the default (100) or higher.

---

## 6. HudLine Colors & Alignment

### Creating lines

```java
// Simple white line
HudLine.of("Hello")

// Colored line (ARGB int)
HudLine.of("Warning", 0xFFAA00)

// Builder for more control
HudLine.builder()
    .text("Mana: 50/100")
    .color(0x55FFFF)     // ARGB color
    .rightAlign()        // right-aligned (for right-side HUD positions)
    .build()
```

### Color format

Colors are **ARGB** integers (0xAARRGGBB):
- `0xFFFFFFFF` — opaque white (default)
- `0x55FF55` — green
- `0xFFAA00` — orange
- `0xFF5555` — red
- `0x55FFFF` — cyan
- `0xAAAAFF` — light blue

### Alignment

- **Left-aligned** (default): text starts from the left edge of the HUD.
- **Right-aligned** (`.rightAlign()`): text ends at the right edge. Useful for right-side HUD positions, but the renderer automatically handles right-alignment when the HUD is positioned on the right side of the screen.

---

## 7. Thread Safety & Performance

### Thread safety

| Method | Thread | Notes |
|--------|--------|-------|
| `CSHudApi.register()` / `unregister()` | Any thread | Uses `CopyOnWriteArrayList` — safe for concurrent access. |
| `HudLineProvider.getLines()` / `isVisible()` | **Render thread only** | Called every frame on the client render thread. |

The list returned by `getLines()` is iterated on the render thread. If you cache the list and update it from another thread, use a thread-safe collection like `CopyOnWriteArrayList` or return a new immutable list each call.

### Performance contract

The CS HUD renders every frame and targets 1000+ FPS. **`getLines()` must complete in under 0.1 ms.**

**Do:**
- Cache expensive computations (world scans, NBT reads) and update them on a tick-based timer.
- Return a pre-built immutable list from cache.
- Use `isVisible()` to skip entirely when your feature is inactive.

**Don't:**
- Call `Minecraft.getInstance().level.getBlockState()` per-block in `getLines()`.
- Iterate all entities in the world.
- Allocate large objects (arrays, maps) every frame.

### Recommended caching pattern

```java
private volatile List<HudLine> cache = List.of();
private volatile long lastUpdate = 0;
private static final long TTL_MS = 200; // update 5x/sec

@Override
public List<HudLine> getLines() {
    long now = System.currentTimeMillis();
    if (now - lastUpdate < TTL_MS) return cache;
    lastUpdate = now;
    cache = buildLines();  // expensive computation here
    return cache;
}
```

---

## 8. User Configuration

Users can configure the CS HUD via the CoreSplit settings screen (`/csset` command or Mod Menu):

| Setting | Description | Range |
|---------|-------------|-------|
| Enable CS HUD | Master on/off switch | boolean |
| HUD Position | Screen corner | Top-Left / Top-Right / Bottom-Left / Bottom-Right |
| Horizontal Offset | Pixels from edge | -64 to 64 |
| Vertical Offset | Pixels from edge | -64 to 64 |
| Font Scale | Text size | 50% to 200% |
| Background Opacity | Panel transparency | 0% to 100% |
| Show Background | Toggle background panel | boolean |
| Text Shadow | Toggle drop shadow | boolean |

The HUD also auto-disables when the MiniHUD mod is loaded (configurable via "Auto-disable with MiniHUD" in the F3 Overlay settings).

---

## 9. Complete Example

Here's a complete example mod that displays the player's health and hunger on the CS HUD:

```java
package com.example.healthhud;

import com.coresplit.hud.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

public class HealthHudProvider implements HudLineProvider {

    private volatile List<HudLine> cache = List.of();
    private volatile long lastUpdate = 0;
    private static final long TTL_MS = 100;

    @Override
    public String getProviderId() { return "healthhud:stats"; }

    @Override
    public boolean isVisible() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null;
    }

    @Override
    public List<HudLine> getLines() {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < TTL_MS) return cache;
        lastUpdate = now;
        cache = build();
        return cache;
    }

    private List<HudLine> build() {
        List<HudLine> lines = new ArrayList<>(2);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return lines;

        Player player = mc.player;
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        int food = player.getFoodData().getFoodLevel();

        // Color: green > 50%, orange > 25%, red otherwise
        int hpColor = health / maxHealth > 0.5f ? 0x55FF55
                     : health / maxHealth > 0.25f ? 0xFFAA00
                     : 0xFF5555;

        lines.add(HudLine.of(
            String.format("HP: %.0f / %.0f", health, maxHealth), hpColor));
        lines.add(HudLine.of(
            String.format("Food: %d / 20", food), 0xFFAA00));

        return lines;
    }
}
```

### Registration (in your client initializer)

```java
package com.example.healthhud;

import com.coresplit.hud.CSHudApi;
import net.fabricmc.api.ClientModInitializer;

public class HealthHudMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CSHudApi.getInstance().register(new HealthHudProvider(), 15);
        // Priority 15 = appears after CoreSplit's player info (10) but before world info (20)
    }
}
```

---

## 10. Built-in Providers

CoreSplit registers these providers automatically at client init:

| Provider ID | Priority | Content |
|-------------|----------|---------|
| `coresplit:performance` | 0 | FPS, frame time (ms), memory usage (used/max/%) |
| `coresplit:player` | 10 | XYZ coordinates, block position, dimension, facing |
| `coresplit:world` | 20 | Time of day (HH:MM) |
| `coresplit:modules` | 30 | Governor status + quality level, scheduler status + cores/threads, target FPS |

To check if a provider is registered:
```java
CSHudApi.getInstance().getRegistrations()
    .stream()
    .filter(r -> r.provider.getProviderId().equals("coresplit:performance"))
    .findFirst()
    .ifPresent(reg -> System.out.println("Found: " + reg.provider.getDisplayName()));
```

---

## 11. Troubleshooting

### My lines don't appear

1. **Check `hudEnabled`** — The user may have disabled the CS HUD. Check via `CoreSplitYaclConfig.isHudEnabled()`.
2. **Check `isVisible()`** — Make sure your provider returns `true`.
3. **Check MiniHUD** — If MiniHUD is loaded and "Auto-disable with MiniHUD" is on, the CS HUD is suppressed. The user can disable this in settings.
4. **Check for exceptions** — If `getLines()` throws, the renderer logs a warning and skips your provider. Check the game log for `[CoreSplit] HUD provider '...' threw exception`.

### Duplicate registration warning

```
[CoreSplit] CSHudApi: duplicate provider id 'mymod:status'
```

A provider with the same ID is already registered. Make sure your `getProviderId()` returns a unique string. Use the `"modid:purpose"` convention.

### Performance issues

If the HUD causes FPS drops:
1. Increase your `TTL_MS` cache interval (e.g. from 100ms to 500ms).
2. Profile your `getLines()` — it should complete in <0.1ms.
3. Use `isVisible()` to skip computation entirely when your feature is inactive.
4. Avoid allocating new lists every frame — reuse a cached list and only rebuild when data changes.

### API stability

The CS HUD API (`HudLineProvider`, `HudLine`, `CSHudApi`) is designed to be stable across CoreSplit 2.x releases. Breaking changes will be announced in advance and follow semantic versioning.

---

*For questions or bug reports, see the CoreSplit source code or issue tracker.*
