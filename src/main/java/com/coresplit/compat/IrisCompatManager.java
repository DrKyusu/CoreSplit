package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Iris 兼容管理器。
 *
 * <p>通过反射访问 Iris API 与配置文件，提供光影状态查询、阴影距离读取、shader 包名获取等能力。
 * 所有访问做 null + 异常双保险，Iris 未加载时返回安全默认值。
 *
 * <p>反射句柄缓存到 volatile 字段，首次成功后复用，避免每次 Class.forName。
 * 每个句柄初始化用独立 try-catch Throwable（含 NoClassDefFoundError），单个失败不影响其他。
 *
 * <p>配置文件 {@code <configdir>/iris.properties} 作为反射失败时的回退读取路径。
 *
 * <p>线程安全：单例 + volatile 句柄；Properties 用 5s TTL 缓存避免重复 IO。
 */
public class IrisCompatManager {

    private static final int DEFAULT_SHADOW_DISTANCE = 0;
    private static final int MIN_SHADOW_DISTANCE = 0;
    private static final int MAX_SHADOW_DISTANCE = 32;
    private static final String IRIS_CONFIG_FILE = "iris.properties";
    private static final long PROPERTIES_TTL_MS = 5000L;

    // === 反射句柄缓存 ===
    private volatile Class<?> irisApiClass;
    private volatile Method irisApiGetInstance;
    private volatile Method irisApiIsShaderPackInUse;
    private volatile Method irisApiAreShadersEnabled;

    private volatile Class<?> irisClass;
    private volatile Method irisGetIrisConfig;

    private volatile Class<?> irisConfigClass;
    private volatile Method irisConfigGetShaderPackName;
    private volatile Method irisConfigAreShadersEnabled;
    private volatile Method irisConfigGetMaxShadowRenderDistance;

    private volatile Class<?> irisVideoSettingsClass;
    private volatile Field irisVideoSettingsShadowDistance;

    // === 状态 ===
    private final AtomicBoolean reflectionInitAttempted = new AtomicBoolean(false);
    private volatile boolean irisPresent;
    private volatile boolean reflectionReady = false;

    // iris.properties 文件缓存
    private volatile Properties irisPropertiesCache;
    private volatile long irisPropertiesLoadedAt;

    // === 单例 ===
    private static volatile IrisCompatManager instance;

    public static IrisCompatManager getInstance() {
        IrisCompatManager result = instance;
        if (result == null) {
            synchronized (IrisCompatManager.class) {
                result = instance;
                if (result == null) {
                    result = new IrisCompatManager();
                    instance = result;
                }
            }
        }
        return result;
    }

    private IrisCompatManager() {
        irisPresent = FabricLoader.getInstance().isModLoaded("iris");
        if (irisPresent) {
            initReflectionHandles();
        }
    }

    /**
     * 初始化反射句柄。每个类/方法独立 try-catch Throwable，单个失败不影响其他。
     * 用 Throwable 而非 Exception，因为 NoClassDefFoundError 是 Error 子类。
     */
    private void initReflectionHandles() {
        if (!reflectionInitAttempted.compareAndSet(false, true)) return;

        // IrisApi 类
        try {
            irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApiGetInstance = irisApiClass.getDeclaredMethod("getInstance");
            irisApiGetInstance.setAccessible(true);
            irisApiIsShaderPackInUse = irisApiClass.getDeclaredMethod("isShaderPackInUse");
            irisApiIsShaderPackInUse.setAccessible(true);
            irisApiAreShadersEnabled = irisApiClass.getDeclaredMethod("areShadersEnabled");
            irisApiAreShadersEnabled.setAccessible(true);
        } catch (Throwable t) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] IrisApi reflection init failed", t);
        }

        // Iris 类（独立 try-catch）
        try {
            irisClass = Class.forName("net.irisshaders.iris.Iris");
            irisGetIrisConfig = irisClass.getDeclaredMethod("getIrisConfig");
            irisGetIrisConfig.setAccessible(true);
        } catch (Throwable t) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Iris class reflection init failed", t);
        }

        // IrisConfig 类
        try {
            irisConfigClass = Class.forName("net.irisshaders.iris.config.IrisConfig");
            try {
                irisConfigGetShaderPackName = irisConfigClass.getDeclaredMethod("getShaderPackName");
                irisConfigGetShaderPackName.setAccessible(true);
            } catch (Throwable ignored) {}
            try {
                irisConfigAreShadersEnabled = irisConfigClass.getDeclaredMethod("areShadersEnabled");
                irisConfigAreShadersEnabled.setAccessible(true);
            } catch (Throwable ignored) {}
            try {
                irisConfigGetMaxShadowRenderDistance = irisConfigClass.getDeclaredMethod("getMaxShadowRenderDistance");
                irisConfigGetMaxShadowRenderDistance.setAccessible(true);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] IrisConfig reflection init failed", t);
        }

        // IrisVideoSettings 静态字段
        try {
            irisVideoSettingsClass = Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings");
            try {
                irisVideoSettingsShadowDistance = irisVideoSettingsClass.getDeclaredField("shadowDistance");
                irisVideoSettingsShadowDistance.setAccessible(true);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] IrisVideoSettings reflection init failed", t);
        }

        reflectionReady = true;
    }

    // === 公开 API ===

    public boolean isIrisPresent() { return irisPresent; }

    public boolean isReflectionReady() { return reflectionReady; }

    /**
     * 当前是否有光影包正在使用（比 areShadersEnabled 更准确）。
     * Iris 未加载或反射失败返回 false。
     */
    public boolean isShaderPackInUse() {
        if (!irisPresent || !reflectionReady) return false;
        try {
            Object api = safeInvoke(irisApiGetInstance, null);
            if (api == null) return false;
            Object result = safeInvoke(irisApiIsShaderPackInUse, api);
            return result instanceof Boolean b ? b : false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 光影是否启用。
     */
    public boolean areShadersEnabled() {
        if (!irisPresent || !reflectionReady) return false;
        // 优先用 IrisApi.areShadersEnabled()
        try {
            Object api = safeInvoke(irisApiGetInstance, null);
            if (api != null) {
                Object result = safeInvoke(irisApiAreShadersEnabled, api);
                if (result instanceof Boolean b) return b;
            }
        } catch (Exception ignored) {}
        // 回退：IrisConfig.areShadersEnabled()
        try {
            Object config = safeInvoke(irisGetIrisConfig, null);
            if (config != null) {
                Object result = safeInvoke(irisConfigAreShadersEnabled, config);
                if (result instanceof Boolean b) return b;
            }
        } catch (Exception ignored) {}
        // 回退：iris.properties 的 enableShaders
        Properties props = loadIrisProperties();
        if (props != null) {
            return "true".equalsIgnoreCase(props.getProperty("enableShaders", "true"));
        }
        return false;
    }

    /**
     * 获取当前光影包名。多级回退：IrisConfig.getShaderPackName() → iris.properties → "(unknown)"。
     */
    public String getShaderPackName() {
        if (!irisPresent) return "(iris not loaded)";
        // 1. 反射 IrisConfig.getShaderPackName()
        try {
            Object config = safeInvoke(irisGetIrisConfig, null);
            if (config != null) {
                Object name = safeInvoke(irisConfigGetShaderPackName, config);
                if (name instanceof String s && !s.isEmpty()) return s;
                // getShaderPackName 可能返回 Optional<String>
                if (name != null) {
                    String str = name.toString();
                    if (!"Optional.empty".equals(str) && !str.isEmpty()) {
                        return str;
                    }
                }
            }
        } catch (Exception ignored) {}
        // 2. 回退 iris.properties: shaderPack
        Properties props = loadIrisProperties();
        if (props != null) {
            String name = props.getProperty("shaderPack");
            if (name != null && !name.isEmpty()) return name;
        }
        return "(unknown)";
    }

    /**
     * 获取阴影渲染距离（区块）。优先读 IrisVideoSettings.shadowDistance 静态字段，
     * 回退到 iris.properties 的 maxShadowRenderDistance。钳制到 [0, 32]。
     */
    public int getShadowDistance() {
        if (!irisPresent || !reflectionReady) return DEFAULT_SHADOW_DISTANCE;
        try {
            if (irisVideoSettingsShadowDistance != null) {
                Object val = irisVideoSettingsShadowDistance.get(null);
                if (val instanceof Integer i) {
                    return clampShadow(i);
                }
                if (val instanceof Number n) {
                    return clampShadow(n.intValue());
                }
            }
        } catch (Exception ignored) {}
        // 回退：iris.properties maxShadowRenderDistance
        Properties props = loadIrisProperties();
        if (props != null) {
            String raw = props.getProperty("maxShadowRenderDistance");
            if (raw != null) {
                try { return clampShadow(Integer.parseInt(raw)); } catch (NumberFormatException ignored) {}
            }
        }
        return DEFAULT_SHADOW_DISTANCE;
    }

    /**
     * 获取最大阴影渲染距离上限。默认 32。
     */
    public int getMaxShadowRenderDistance() {
        if (!irisPresent || !reflectionReady) return MAX_SHADOW_DISTANCE;
        try {
            Object config = safeInvoke(irisGetIrisConfig, null);
            if (config != null) {
                Object result = safeInvoke(irisConfigGetMaxShadowRenderDistance, config);
                if (result instanceof Integer i) return clampShadow(i);
                if (result instanceof Number n) return clampShadow(n.intValue());
            }
        } catch (Exception ignored) {}
        // 回退：iris.properties
        Properties props = loadIrisProperties();
        if (props != null) {
            String raw = props.getProperty("maxShadowRenderDistance");
            if (raw != null) {
                try { return clampShadow(Integer.parseInt(raw)); } catch (NumberFormatException ignored) {}
            }
        }
        return MAX_SHADOW_DISTANCE;
    }

    // === iris.properties 文件读取（5s TTL 缓存） ===

    private Properties loadIrisProperties() {
        long now = System.currentTimeMillis();
        Properties cached = irisPropertiesCache;
        if (cached != null && (now - irisPropertiesLoadedAt) < PROPERTIES_TTL_MS) {
            return cached;
        }
        try {
            Path path = getIrisConfigPath();
            if (!Files.exists(path)) return cached;
            Properties props = new Properties();
            try (var in = Files.newInputStream(path)) {
                props.load(in);
            }
            irisPropertiesCache = props;
            irisPropertiesLoadedAt = now;
            return props;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load iris.properties", e);
            return cached;
        }
    }

    private Path getIrisConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(IRIS_CONFIG_FILE);
    }

    // === 工具方法 ===

    private int clampShadow(int value) {
        return Math.max(MIN_SHADOW_DISTANCE, Math.min(MAX_SHADOW_DISTANCE, value));
    }

    private Object safeInvoke(Method m, Object target, Object... args) {
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Exception e) {
            return null;
        }
    }
}
