package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sodium 兼容管理器。
 *
 * <p>通过反射访问 {@code net.caffeinemc.mods.sodium.client.SodiumClientMod.options()}
 * 返回的 {@code SodiumOptions} 实例。Sodium 没有独立帧率限制，本类用于读取其画质/性能设置
 * 供 F3 显示与协调参考。
 *
 * <p>所有访问做 null + 异常双保险，Sodium 未加载时返回安全默认值。
 * 反射句柄缓存到 volatile 字段，每个独立 try-catch Throwable。
 */
public class SodiumCompatManager {

    private static final String SODIUM_MOD_ID = "sodium";
    private static final String SODIUM_CLIENT_MOD_CLASS = "net.caffeinemc.mods.sodium.client.SodiumClientMod";
    private static final String SODIUM_OPTIONS_CLASS = "net.caffeinemc.mods.sodium.client.gui.SodiumOptions";

    // === 反射句柄缓存 ===
    private volatile Class<?> sodiumClientModClass;
    private volatile Method sodiumOptionsMethod;       // SodiumClientMod.options()
    private volatile Class<?> sodiumOptionsClass;      // SodiumOptions

    // === 状态 ===
    private final AtomicBoolean reflectionInitAttempted = new AtomicBoolean(false);
    private volatile boolean sodiumPresent;
    private volatile boolean reflectionReady = false;

    // === 反射字段缓存：避免每次读字段都重新查找 ===
    // key = "ClassName.fieldName"，避免不同类的同名字段冲突
    private final ConcurrentHashMap<String, Field> fieldCache = new ConcurrentHashMap<>();

    // === 单例 ===
    private static volatile SodiumCompatManager instance;

    public static SodiumCompatManager getInstance() {
        SodiumCompatManager result = instance;
        if (result == null) {
            synchronized (SodiumCompatManager.class) {
                result = instance;
                if (result == null) {
                    result = new SodiumCompatManager();
                    instance = result;
                }
            }
        }
        return result;
    }

    private SodiumCompatManager() {
        sodiumPresent = FabricLoader.getInstance().isModLoaded(SODIUM_MOD_ID);
        if (sodiumPresent) {
            initReflectionHandles();
        }
    }

    private void initReflectionHandles() {
        if (!reflectionInitAttempted.compareAndSet(false, true)) return;

        try {
            sodiumClientModClass = Class.forName(SODIUM_CLIENT_MOD_CLASS);
            try {
                sodiumOptionsMethod = sodiumClientModClass.getDeclaredMethod("options");
                sodiumOptionsMethod.setAccessible(true);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] SodiumClientMod reflection init failed", t);
        }

        try {
            sodiumOptionsClass = Class.forName(SODIUM_OPTIONS_CLASS);
        } catch (Throwable t) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] SodiumOptions reflection init failed", t);
        }

        reflectionReady = true;
    }

    // === 公开 API ===

    public boolean isSodiumPresent() { return sodiumPresent; }

    public boolean isReflectionReady() { return reflectionReady; }

    /**
     * 获取 SodiumOptions 实例。Sodium 未加载或反射失败返回 null。
     */
    public Object getSodiumOptionsInstance() {
        if (!sodiumPresent || !reflectionReady) return null;
        if (sodiumOptionsMethod == null) return null;
        try {
            return sodiumOptionsMethod.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 SodiumOptions 实例读取整数字段（嵌套字段用点号分隔，如 "quality.cloudHeight"）。
     * 失败返回 null。
     */
    public Integer getIntegerField(String fieldPath) {
        Object target = resolveFieldTarget(fieldPath);
        if (target == null || target instanceof Integer) {
            return (Integer) target;
        }
        return null;
    }

    /**
     * 从 SodiumOptions 实例读取布尔字段（嵌套字段用点号分隔）。
     * 失败返回 null。
     */
    public Boolean getBooleanField(String fieldPath) {
        Object target = resolveFieldTarget(fieldPath);
        if (target instanceof Boolean b) return b;
        return null;
    }

    /**
     * 解析嵌套字段路径，返回叶子字段的值。
     * 例如 "quality.biomeBlend" → options.quality.biomeBlend
     */
    private Object resolveFieldTarget(String fieldPath) {
        if (!sodiumPresent || fieldPath == null || fieldPath.isEmpty()) return null;
        Object current = getSodiumOptionsInstance();
        if (current == null) return null;
        String[] parts = fieldPath.split("\\.");
        for (String part : parts) {
            if (current == null) return null;
            current = readField(current, part);
        }
        return current;
    }

    private Object readField(Object target, String fieldName) {
        Class<?> targetClass = target.getClass();
        String cacheKey = targetClass.getName() + "." + fieldName;
        Field cachedField = fieldCache.get(cacheKey);

        if (cachedField != null) {
            try {
                return cachedField.get(target);
            } catch (Exception e) {
                fieldCache.remove(cacheKey);
            }
        }

        // 修复BUG: 原内层 try 的 catch 仅捕获 NoSuchFieldException，但 Field.get() 抛出的
        // IllegalAccessException 是受检异常，未被捕获导致编译失败。重构为统一遍历：
        // - NoSuchFieldException: 当前类无此字段，继续找父类
        // - 其他异常（IllegalAccessException 等）: 字段已找到但访问失败，返回 null
        for (Class<?> c = targetClass; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                fieldCache.put(cacheKey, f);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // 当前类无此字段，继续向父类查找
            } catch (Exception e) {
                // 字段已找到但访问失败（IllegalAccessException 等），直接返回 null
                return null;
            }
        }
        return null;
    }

    /**
     * 汇总 Sodium 关键配置供 F3 显示。
     */
    public String getSodiumOptionsSummary() {
        if (!sodiumPresent) return "Sodium not loaded";
        Object opts = getSodiumOptionsInstance();
        if (opts == null) return "Sodium (options unavailable)";
        StringBuilder sb = new StringBuilder("Sodium: ");
        // 尝试读取几个已知字段（不强制存在）
        appendField(sb, opts, "cloudHeight");
        appendField(sb, opts, "biomeBlend");
        return sb.toString().strip();
    }

    private void appendField(StringBuilder sb, Object target, String fieldName) {
        Object val = readField(target, fieldName);
        if (val != null) {
            if (sb.length() > 8 && !sb.toString().endsWith(": ")) sb.append(", ");
            sb.append(fieldName).append("=").append(val);
        }
    }
}
