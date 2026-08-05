package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import com.coresplit.compat.remote.RemoteSchemaFetcher;
import com.coresplit.config.CoreSplitYaclConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * 性能协调器。
 *
 * <p>纯函数式协调 CoreSplit 的 governor 与 Iris/Sodium 的行为：
 * <ul>
 *   <li>{@link #clampFramerateLimit(int)}：governor 写入原版 framerateLimit 前钳制。
 *       规避 Iris {@code MixinMaxFpsCrashFix}（framerateLimit==0 时 Iris 重置为 120），
 *       绝不让 0 流入；光影开启时钳制到显示器刷新率 - 5（GPU 过载保护）。</li>
 *   <li>{@link #clampRenderDistance(int)}：governor 降质时渲染距离不能低于 Iris 阴影距离 + 2，
 *       否则光影会异常。</li>
 *   <li>{@link #getEffectiveTargetFps()}：光影开启时目标帧率不超过刷新率 - 5。</li>
 * </ul>
 *
 * <p>线程安全：纯函数，依赖的 IrisCompatManager 等单例自身线程安全。
 * governor 在 tick 线程调用，F3 在渲染线程读取，无共享可变状态。
 *
 * <p>零侵入：光影未开启或深度兼容关闭时直接返回原值，无额外开销。
 */
public class CompatCoordinator {

    /** 降质时渲染距离至少高于 shadowDistance + 2，避免光影异常 */
    private static final int MIN_RENDER_DISTANCE_BELOW_SHADOW = 2;
    private static final int DEFAULT_MONITOR_REFRESH = 60;
    /** 光影下帧率留 5fps 余量 */
    private static final int SHADER_FPS_HEADROOM = 5;
    /** 光影下最低目标帧率 */
    private static final int MIN_SHADER_TARGET_FPS = 30;
    /** MC 渲染距离最大值 */
    private static final int MAX_RENDER_DISTANCE = 32;

    private static volatile CompatCoordinator instance;

    public static CompatCoordinator getInstance() {
        CompatCoordinator result = instance;
        if (result == null) {
            synchronized (CompatCoordinator.class) {
                result = instance;
                if (result == null) {
                    result = new CompatCoordinator();
                    instance = result;
                }
            }
        }
        return result;
    }

    private CompatCoordinator() {}

    /**
     * 钳制 governor 想要应用的帧率上限值。
     *
     * <p>关键约束：
     * <ul>
     *   <li>Iris {@code MixinMaxFpsCrashFix} 在 framerateLimit==0 时会重置为 120，
     *       CoreSplit 绝不能传 0；用 {@link Options#UNLIMITED_FRAMERATE_CUTOFF} 替代。</li>
     *   <li>光影开启时，帧率上限不应高于显示器刷新率 - 5（GPU 过载保护）。</li>
     * </ul>
     *
     * @param desired governor 想要应用的帧率上限
     * @return 钳制后的安全值
     */
    public int clampFramerateLimit(int desired) {
        if (!isCoordinationEnabled()) return desired;
        try {
            IrisCompatManager iris = IrisCompatManager.getInstance();
            if (!iris.isIrisPresent()) return desired;

            // 防御性：绝不能传 0（规避 Iris MixinMaxFpsCrashFix）
            if (desired <= 0) {
                return Options.UNLIMITED_FRAMERATE_CUTOFF;
            }

            // 光影开启时钳制到显示器刷新率 - 5
            if (iris.isShaderPackInUse()) {
                int refresh = getMonitorRefreshRate();
                int cap = Math.max(MIN_SHADER_TARGET_FPS, refresh - SHADER_FPS_HEADROOM);
                return Math.min(desired, cap);
            }
            return desired;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] CompatCoordinator.clampFramerateLimit failed", e);
            return desired <= 0 ? Options.UNLIMITED_FRAMERATE_CUTOFF : desired;
        }
    }

    /**
     * 钳制渲染距离。降质时不能低于 Iris shadowDistance + 2，否则光影会异常。
     * 钳制到 [max(desired, effectiveMin), min(desired, effectiveMax)]。
     *
     * <p>光影下同时进行上限钳制（通过 {@link ShaderPerformanceOptimizer#clampRenderDistanceUpper}），
     * 避免渲染距离远超阴影距离造成无谓的额外像素着色。
     *
     * @param desired governor 想要应用的渲染距离
     * @return 钳制后的安全值
     */
    public int clampRenderDistance(int desired) {
        if (!isCoordinationEnabled()) return desired;
        try {
            IrisCompatManager iris = IrisCompatManager.getInstance();
            if (!iris.isIrisPresent() || !iris.isShaderPackInUse()) return desired;

            int shadowDist = iris.getShadowDistance();
            int minRender = shadowDist + MIN_RENDER_DISTANCE_BELOW_SHADOW;
            // shadowDistance 上限 32，+2 = 34，但渲染距离本身最大 32
            int effectiveMin = Math.min(minRender, MAX_RENDER_DISTANCE);
            int result = Math.max(desired, effectiveMin);

            // 光影下上限钳制：渲染距离不应远超阴影距离
            result = ShaderPerformanceOptimizer.getInstance().clampRenderDistanceUpper(result);

            return result;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] CompatCoordinator.clampRenderDistance failed", e);
            return desired;
        }
    }

    /**
     * 光影下是否应该强制开启 VSync。
     *
     * <p>光影下 GPU 满载时 VSync 提供天然 backpressure，比单纯帧率上限更稳定，
     * 避免着色器管线饱和导致的帧率暴跌。
     */
    public boolean shouldForceVsyncUnderShaders() {
        if (!isCoordinationEnabled()) return false;
        try {
            return ShaderPerformanceOptimizer.getInstance().shouldForceVsync();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计算光影感知的目标帧率（用于 governor 决策时参考）。
     * 光影开启时建议目标帧率不超过刷新率 - 5。
     */
    public int getEffectiveTargetFps() {
        int configured = CoreSplitYaclConfig.getTargetFps();
        if (!isCoordinationEnabled()) return configured;
        try {
            IrisCompatManager iris = IrisCompatManager.getInstance();
            if (iris.isIrisPresent() && iris.isShaderPackInUse()) {
                int refresh = getMonitorRefreshRate();
                return Math.min(configured, Math.max(MIN_SHADER_TARGET_FPS, refresh - SHADER_FPS_HEADROOM));
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] CompatCoordinator.getEffectiveTargetFps failed", e);
        }
        return configured;
    }

    /**
     * 获取显示器刷新率。失败返回 60。
     */
    private int getMonitorRefreshRate() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getWindow() != null) {
                return mc.getWindow().getRefreshRate();
            }
        } catch (Exception ignored) {}
        return DEFAULT_MONITOR_REFRESH;
    }

    /**
     * 协调是否启用（深度兼容总开关 + 自动协调开关均需开启）。
     */
    private boolean isCoordinationEnabled() {
        try {
            return CoreSplitYaclConfig.isDeepCompatEnabled()
                    && CoreSplitYaclConfig.isAutoCoordinationEnabled();
        } catch (Throwable t) {
            // 配置类尚未初始化时安全降级
            return false;
        }
    }

    // === F3 信息汇总 ===

    /**
     * 构建兼容性汇总供 F3 显示。
     */
    public CompatSummary buildSummary() {
        RenderCompatDetector detector = RenderCompatDetector.getInstance();
        IrisCompatManager iris = IrisCompatManager.getInstance();
        SodiumCompatManager sodium = SodiumCompatManager.getInstance();

        boolean irisPresent = iris.isIrisPresent();
        boolean sodiumPresent = sodium.isSodiumPresent();
        boolean shadersEnabled = irisPresent && iris.isShaderPackInUse();
        String shaderPackName = irisPresent ? iris.getShaderPackName() : "(none)";
        int shadowDistance = irisPresent ? iris.getShadowDistance() : 0;
        int maxShadow = irisPresent ? iris.getMaxShadowRenderDistance() : 0;

        // 远程 schema/release 信息
        String irisSchemaVersion = "?";
        String sodiumSchemaVersion = "?";
        String irisLatestTag = "?";
        String sodiumLatestTag = "?";
        boolean irisOutdated = false;
        boolean sodiumOutdated = false;
        try {
            RemoteSchemaFetcher fetcher = RemoteSchemaFetcher.getInstance();
            irisSchemaVersion = fetcher.getIrisSchema().getSourceVersion();
            sodiumSchemaVersion = fetcher.getSodiumSchema().getSourceVersion();
            RemoteSchemaFetcher.ReleaseInfo irisRel = fetcher.getIrisLatestRelease();
            RemoteSchemaFetcher.ReleaseInfo sodiumRel = fetcher.getSodiumLatestRelease();
            irisLatestTag = irisRel.tag();
            sodiumLatestTag = sodiumRel.tag();
            irisOutdated = irisPresent && fetcher.isVersionOutdated(detector.getIrisVersion(), irisLatestTag);
            sodiumOutdated = sodiumPresent && fetcher.isVersionOutdated(detector.getSodiumVersion(), sodiumLatestTag);
        } catch (Throwable ignored) {}

        int effectiveFpsCap = shadersEnabled
                ? clampFramerateLimit(CoreSplitYaclConfig.getTargetFps())
                : CoreSplitYaclConfig.getTargetFps();
        int effectiveTargetFps = getEffectiveTargetFps();

        return new CompatSummary(
                irisPresent, sodiumPresent,
                shadersEnabled, shaderPackName,
                shadowDistance, maxShadow,
                irisSchemaVersion, irisLatestTag, irisOutdated,
                sodiumSchemaVersion, sodiumLatestTag, sodiumOutdated,
                effectiveFpsCap, effectiveTargetFps);
    }

    /**
     * 兼容性汇总记录（供 F3 overlay 使用）。
     */
    public record CompatSummary(
            boolean irisPresent, boolean sodiumPresent,
            boolean shadersEnabled, String shaderPackName,
            int shadowDistance, int maxShadowRenderDistance,
            String irisSchemaVersion, String irisLatestReleaseTag, boolean irisOutdated,
            String sodiumSchemaVersion, String sodiumLatestReleaseTag, boolean sodiumOutdated,
            int effectiveFpsCap, int effectiveTargetFps) {}
}
