package com.coresplit.governor;

import com.coresplit.CoreSplitMod;
import com.coresplit.compat.CompatCoordinator;
import com.coresplit.compat.ShaderPerformanceOptimizer;
import com.coresplit.config.CoreSplitYaclConfig;
import com.coresplit.renderlimiter.GpuMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

public class PerformanceGovernor {

    private static final int FRAME_HISTORY_SIZE = 60;
    private static final long ADJUSTMENT_INTERVAL_MS = 2000;
    private static final long HIGH_FPS_ADJUSTMENT_INTERVAL_MS = 5000;
    private static final float HIGH_FPS_THRESHOLD = 200.0f;
    private static final int MIN_RENDER_DISTANCE = 2;
    private static final int MIN_SIMULATION_DISTANCE = 4;
    private static final int MAX_QUALITY_LEVEL = 5;
    private static final int RENDER_DISTANCE_STEP = 2;
    private static final int SIMULATION_DISTANCE_STEP = 2;

    // 连续触发计数：需要连续多次检测到相同方向的趋势才进行调整，避免瞬时波动导致频繁切换
    private static final int CONSECUTIVE_TRIGGERS_REQUIRED = 2;
    private volatile int consecutiveLowFpsCount = 0;
    private volatile int consecutiveHighFpsCount = 0;

    private static final long MAX_FRAME_TIME_NS = 50_000_000L;
    private static final long MIN_FRAME_TIME_NS = 1_000_000L;
    // 修复BUG: 添加最小有效FPS阈值，防止avgFps为0或异常低值时误触发降质量
    private static final float MIN_FPS_THRESHOLD = 1.0f;

    private final long[] frameTimes = new long[FRAME_HISTORY_SIZE];
    private volatile int frameIdx = 0;
    // 修复BUG: frameTimes数组元素的读写需要同步保护；volatile只能保护数组引用，不能保护内部元素
    private final Object frameTimesLock = new Object();

    // PERF: 缓存平均 FPS，由 onRenderFrame（每客户端 tick）写入 frameTimes 后在锁内重算；
    // getCurrentFps()（HUD/governor 高频调用）直接返回缓存值，避免每次扫描 60 项数组。
    private volatile float cachedAvgFps = 0;

    private volatile int currentQualityLevel = 0;
    private volatile long lastAdjustmentTime = 0;
    private volatile boolean enabled = true;

    // 修复BUG: base*字段在init/reinit写入、在applyQualitySettings读取，可能跨线程访问，加volatile保证可见性
    private volatile int baseRenderDistance;
    private volatile int baseSimulationDistance;
    private volatile ParticleStatus baseParticleStatus;
    private volatile boolean baseEntityShadows;
    private volatile boolean baseAmbientOcclusion;

    private volatile boolean initialized = false;

    // 修复BUG: 帧率上限管理。governor 启用时将 MC 的 framerateLimit 设为 targetFps，使"实际帧数=目标帧数"；
    // 禁用/重置时恢复用户原始值。baseFramerateLimit 在 init 时捕获（应用上限之前）。
    private volatile int baseFramerateLimit = 260;
    // 跟踪已应用的上限值，避免每 tick 重复调用 OptionInstance.set()
    private volatile int lastAppliedFpsLimit = -1;

    // 修复BUG: vsync 管理。disableVsync 配置开启时强制关闭 vsync，使目标帧率上限精确生效。
    // 注意：enableVsync().set() 会触发 invalidateSurfaceConfiguration（surface 重建，开销大），
    // 故必须用 lastAppliedVsyncSet/Value 跟踪，仅在值变化时才调用 set()。
    private volatile boolean baseVsync = true;
    private volatile boolean lastAppliedVsyncSet = false;
    private volatile boolean lastAppliedVsyncValue = true;

    public void init() {
        Minecraft client = getClient();
        if (client == null || client.options == null) return;
        // 修复BUG: 原代码访问客户端Options未做异常捕获，客户端异常状态下会抛RuntimeException导致崩溃
        try {
            Options opt = client.options;
            baseRenderDistance = opt.renderDistance().get();
            baseSimulationDistance = opt.simulationDistance().get();
            baseParticleStatus = opt.particles().get();
            baseEntityShadows = opt.entityShadows().get();
            baseAmbientOcclusion = opt.ambientOcclusion().get();
            // 修复BUG: 捕获用户原始帧率上限，用于 governor 禁用时恢复
            baseFramerateLimit = opt.framerateLimit().get();
            // 修复BUG: 捕获用户原始 vsync 设置，用于 governor 禁用时恢复
            baseVsync = opt.enableVsync().get();
            initialized = true;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] PerformanceGovernor init failed", e);
            return;
        }
        CoreSplitMod.LOGGER.info("[CoreSplit] PerformanceGovernor initialized");
    }

    public void onRenderFrame() {
        if (!enabled) return;

        // 修复BUG: 本方法由 END_CLIENT_TICK（20次/秒）调用，原代码用 now-lastFrameTime 测量"帧时间"，
        // 实际测得的是 tick 间隔（~50ms），导致 cachedAvgFps 恒为 ~20（TPS）而非真实帧率。
        // 这使 governor 永远误判帧数过低而持续降画质，并连带破坏 DynamicMemoryOptimizer 的低负载检测。
        // 改为读取 Minecraft 自身的真实帧率计数器（getFps()，已是平滑值），将其倒数作为帧时间样本喂入环形缓冲。
        Minecraft client = getClient();
        if (client != null) {
            int realFps = client.getFps();
            if (realFps > 0) {
                recordFrameTimeNanos((long) (1_000_000_000.0 / realFps));
            }
        }

        if (initialized) {
            // 光影感知优化：检测光影激活/关闭，主动触发性能优化或恢复
            // 内部有状态缓存，光影状态未变化时零开销
            ShaderPerformanceOptimizer.getInstance().checkAndApplyShaderOptimization(this);

            // 修复BUG: applyDisplaySettings 必须被调用才会真正应用帧率上限 + vsync 设置。
            // 方法幂等（仅值变化时才 set()），每 tick 调用可即时响应用户在配置 UI 中切换 disableVsync。
            applyDisplaySettings();
            checkAndAdjustQuality();
        }
    }

    /**
     * 记录一帧的帧时间（纳秒）并刷新缓存的平均 FPS。
     *
     * <p>生产环境由 {@link #onRenderFrame()} 从 {@code Minecraft.getFps()} 读取真实帧率后调用。
     * 包级可见以支持单元测试直接喂入帧时间数据，绕过 Minecraft 客户端依赖
     * （单元测试环境无客户端实例，{@code onRenderFrame} 在 {@code client==null} 时不记录帧时间，
     * 导致 {@code getCurrentFps()} 恒为 0，FPS 计算逻辑无法被测试覆盖）。
     *
     * <p>钳制到 [{@link #MIN_FRAME_TIME_NS}, {@link #MAX_FRAME_TIME_NS}] 防止异常值 corrupt 计算。
     * 线程安全：通过 {@code frameTimesLock} 保护环形缓冲写入与缓存重算。
     *
     * @param rawFrameTimeNanos 原始帧时间（纳秒）
     */
    void recordFrameTimeNanos(long rawFrameTimeNanos) {
        long frameTime = rawFrameTimeNanos;
        if (frameTime < MIN_FRAME_TIME_NS) {
            frameTime = MIN_FRAME_TIME_NS;
        } else if (frameTime > MAX_FRAME_TIME_NS) {
            frameTime = MAX_FRAME_TIME_NS;
        }
        synchronized (frameTimesLock) {
            int idx = frameIdx % FRAME_HISTORY_SIZE;
            frameTimes[idx] = frameTime;
            frameIdx++;
            // PERF: 在持有锁时重算并缓存平均 FPS，getCurrentFps() 直接返回缓存值，避免高频调用重复扫描数组
            cachedAvgFps = computeAvgFpsLocked();
        }
    }

    private void checkAndAdjustQuality() {
        long now = System.currentTimeMillis();
        float avgFps = calculateAverageFps();

        // 光影下使用更短的调整间隔，快速响应 GPU 瓶颈
        boolean shaderActive = ShaderPerformanceOptimizer.getInstance().isShaderActive();
        long adjustmentInterval;
        if (shaderActive) {
            adjustmentInterval = 1000L; // 光影下 1 秒调整一次，快速降级
        } else if (avgFps > HIGH_FPS_THRESHOLD) {
            adjustmentInterval = HIGH_FPS_ADJUSTMENT_INTERVAL_MS;
        } else {
            adjustmentInterval = ADJUSTMENT_INTERVAL_MS;
        }
        if (now - lastAdjustmentTime < adjustmentInterval) return;

        // Iris 光影开启时通过 CompatCoordinator 钳制目标帧率到显示器刷新率 - 5，避免 GPU 过载
        int targetFps = CompatCoordinator.getInstance().getEffectiveTargetFps();
        float tolerance = CoreSplitYaclConfig.getFpsTolerance();

        // 修复BUG: 原代码仅校验targetFps/tolerance，未校验avgFps有效性（NaN/Infinity/0会导致误判）
        if (targetFps <= 0 || tolerance <= 0 || tolerance >= 1) return;
        if (avgFps <= MIN_FPS_THRESHOLD || Float.isNaN(avgFps) || Float.isInfinite(avgFps)) return;

        // 无限制帧率模式下（targetFps=0），不进行自动质量调整，避免高帧率下频繁波动
        // 但光影下即使无限制帧率也允许降级（GPU 瓶颈场景）
        if (CoreSplitYaclConfig.isUnlimitedFps() && !shaderActive) {
            return;
        }

        float lowerBound = targetFps * (1.0f - tolerance);
        float upperBound = targetFps * (1.0f + tolerance);

        Minecraft client = getClient();
        if (client == null || client.options == null) return;

        // 光影下 GPU 过载检测：GPU 利用率持续 >=95% 且 FPS 低于 50 时，绕过连续触发等待直接降级
        boolean gpuOverloaded = ShaderPerformanceOptimizer.getInstance().isGpuOverloaded(avgFps);

        // 修复BUG: 原代码对Minecraft Options的修改未做异常捕获，客户端异常会导致崩溃
        try {
            if (avgFps < lowerBound && currentQualityLevel < MAX_QUALITY_LEVEL) {
                consecutiveLowFpsCount++;
                consecutiveHighFpsCount = 0;
                // GPU 过载时绕过连续触发等待，立即降级
                if (gpuOverloaded || consecutiveLowFpsCount >= CONSECUTIVE_TRIGGERS_REQUIRED) {
                    lowerQuality(client.options);
                    lastAdjustmentTime = now;
                    consecutiveLowFpsCount = 0;
                }
            } else if (avgFps > upperBound && currentQualityLevel > 0) {
                consecutiveHighFpsCount++;
                consecutiveLowFpsCount = 0;
                if (consecutiveHighFpsCount >= CONSECUTIVE_TRIGGERS_REQUIRED) {
                    raiseQuality(client.options);
                    lastAdjustmentTime = now;
                    consecutiveHighFpsCount = 0;
                }
            } else {
                // FPS 在正常范围内，重置计数器
                consecutiveLowFpsCount = 0;
                consecutiveHighFpsCount = 0;
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] PerformanceGovernor quality adjustment failed", e);
        }
    }

    // PERF: 计算逻辑抽出到此方法，调用方必须持有 frameTimesLock。onRenderFrame 每帧写入后调用以刷新缓存。
    private float computeAvgFpsLocked() {
        int count = Math.min(frameIdx, FRAME_HISTORY_SIZE);
        if (count == 0) return 0;

        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += frameTimes[i];
        }
        double avgNs = sum / (double) count;
        if (avgNs <= 0) return 0;
        return (float) (1_000_000_000.0 / avgNs);
    }

    private float calculateAverageFps() {
        // PERF: 直接返回 onRenderFrame 每帧刷新的缓存值，避免重复扫描 60 项数组；
        // 缓存值最多滞后 1 帧，对质量调节与 HUD 显示均可接受。
        return cachedAvgFps;
    }

    private void lowerQuality(Options opt) {
        int newLevel = Math.min(currentQualityLevel + 1, MAX_QUALITY_LEVEL);
        if (newLevel == currentQualityLevel) return;

        int prevLevel = currentQualityLevel;
        // 修复BUG: 原代码先更新currentQualityLevel再调用applyQualitySettings，若后者抛异常会导致状态不一致（记录已升级但实际未应用），改为先应用再更新
        applyQualitySettings(opt, newLevel, true);
        currentQualityLevel = newLevel;

        // Governor 联动：根据质量等级动态调整优化模块行为
        applyOptimizationLevel(newLevel);

        CoreSplitMod.LOGGER.info("[CoreSplit] Governor: Quality decreased from {} to {}", prevLevel, currentQualityLevel);
    }

    private void raiseQuality(Options opt) {
        int newLevel = Math.max(currentQualityLevel - 1, 0);
        if (newLevel == currentQualityLevel) return;

        int prevLevel = currentQualityLevel;
        // 修复BUG: 同lowerQuality，先应用再更新状态，避免异常导致状态不一致
        applyQualitySettings(opt, newLevel, false);
        currentQualityLevel = newLevel;

        // Governor 联动：恢复优化模块行为
        applyOptimizationLevel(newLevel);

        CoreSplitMod.LOGGER.info("[CoreSplit] Governor: Quality increased from {} to {}", prevLevel, currentQualityLevel);
    }

    /**
     * 根据质量等级联动调整优化模块行为，形成性能闭环。
     * level≥2：降低 AI tick 频率
     * level≥3：降低爆炸粒子等级
     * level≥4：触发内存激进驱逐
     * level≤1：恢复正常模式
     */
    private void applyOptimizationLevel(int level) {
        try {
            // 光影下提前触发更激进的优化
            boolean shaderActive = ShaderPerformanceOptimizer.getInstance().isShaderActive();

            // AI 节流联动
            com.coresplit.ai.AiOptimizer ai = com.coresplit.CoreSplitMod.getAiOptimizer();
            if (ai != null) {
                float mult = level >= 2 ? 0.5f : 1.0f;
                if (level >= 4) mult = 0.25f;
                // 光影下额外降低 AI 频率，释放 CPU 给渲染线程
                if (shaderActive && level >= 1) mult = Math.min(mult, 0.5f);
                ai.getThrottle().setGlobalMultiplier(mult);
            }

            // 爆炸粒子联动
            com.coresplit.explosion.ExplosionOptimizer explosion = com.coresplit.CoreSplitMod.getExplosionOptimizer();
            if (explosion != null) {
                com.coresplit.explosion.ExplosionParticleLimiter.ParticleLevel pLevel =
                        level >= 3 ? com.coresplit.explosion.ExplosionParticleLimiter.ParticleLevel.LOW
                                : level >= 2 ? com.coresplit.explosion.ExplosionParticleLimiter.ParticleLevel.MEDIUM
                                        : com.coresplit.explosion.ExplosionParticleLimiter.ParticleLevel.HIGH;
                // 光影下爆炸粒子直接降到 LOW，减少着色器 pass
                if (shaderActive && level >= 1) {
                    pLevel = com.coresplit.explosion.ExplosionParticleLimiter.ParticleLevel.LOW;
                }
                explosion.getParticleLimiter().setLevel(pLevel);
            }

            // 内存驱逐联动
            // 光影下将 aggressiveEvict 门槛从 L4 降到 L2，提前释放纹理缓存
            com.coresplit.memory.MemoryOptimizer memory = com.coresplit.CoreSplitMod.getMemoryOptimizer();
            if (memory != null) {
                int aggressiveThreshold = shaderActive ? 2 : 4;
                if (level >= aggressiveThreshold) {
                    memory.aggressiveEvict();
                } else {
                    memory.normalEvict();
                }
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Governor optimization linkage failed", e);
        }
    }

    /**
     * 应用渲染距离（经 CompatCoordinator 钳制）。
     * Iris 光影开启时，渲染距离不能低于 shadowDistance + 2，否则光影异常。
     * 光影未开启时 CompatCoordinator 直接返回原值，零开销。
     */
    private void applyRenderDistance(Options opt, int desired) {
        opt.renderDistance().set(CompatCoordinator.getInstance().clampRenderDistance(desired));
    }

    private void applyQualitySettings(Options opt, int level, boolean lowering) {
        switch (level) {
            case 0 -> {
                if (!lowering) {
                    opt.entityShadows().set(baseEntityShadows);
                    opt.particles().set(baseParticleStatus);
                    opt.ambientOcclusion().set(baseAmbientOcclusion);
                    applyRenderDistance(opt,baseRenderDistance);
                    opt.simulationDistance().set(baseSimulationDistance);
                }
            }
            case 1 -> {
                if (lowering) {
                    opt.entityShadows().set(false);
                } else {
                    opt.entityShadows().set(baseEntityShadows);
                    opt.particles().set(baseParticleStatus);
                    opt.ambientOcclusion().set(baseAmbientOcclusion);
                    applyRenderDistance(opt,Math.min(baseRenderDistance, opt.renderDistance().get() + RENDER_DISTANCE_STEP));
                    opt.simulationDistance().set(Math.min(baseSimulationDistance, opt.simulationDistance().get() + SIMULATION_DISTANCE_STEP));
                }
            }
            case 2 -> {
                if (lowering) {
                    opt.particles().set(ParticleStatus.MINIMAL);
                } else {
                    opt.particles().set(baseParticleStatus);
                    opt.ambientOcclusion().set(baseAmbientOcclusion);
                    applyRenderDistance(opt,Math.min(baseRenderDistance, opt.renderDistance().get() + RENDER_DISTANCE_STEP));
                    opt.simulationDistance().set(Math.min(baseSimulationDistance, opt.simulationDistance().get() + SIMULATION_DISTANCE_STEP));
                }
            }
            case 3 -> {
                if (lowering) {
                    opt.ambientOcclusion().set(false);
                    int newRender = Math.max(MIN_RENDER_DISTANCE, opt.renderDistance().get() - RENDER_DISTANCE_STEP);
                    applyRenderDistance(opt,newRender);
                } else {
                    opt.ambientOcclusion().set(baseAmbientOcclusion);
                    int newRender = Math.min(baseRenderDistance, opt.renderDistance().get() + RENDER_DISTANCE_STEP);
                    applyRenderDistance(opt,newRender);
                    int newSim = Math.min(baseSimulationDistance, opt.simulationDistance().get() + SIMULATION_DISTANCE_STEP);
                    opt.simulationDistance().set(newSim);
                }
            }
            case 4 -> {
                if (lowering) {
                    int newSim = Math.max(MIN_SIMULATION_DISTANCE, opt.simulationDistance().get() - SIMULATION_DISTANCE_STEP);
                    opt.simulationDistance().set(newSim);
                } else {
                    int newSim = Math.min(baseSimulationDistance, opt.simulationDistance().get() + SIMULATION_DISTANCE_STEP);
                    opt.simulationDistance().set(newSim);
                    int newRender = Math.min(baseRenderDistance, opt.renderDistance().get() + RENDER_DISTANCE_STEP);
                    applyRenderDistance(opt,newRender);
                    opt.ambientOcclusion().set(baseAmbientOcclusion);
                }
            }
            case 5 -> {
                if (lowering) {
                    int newRender = Math.max(MIN_RENDER_DISTANCE, opt.renderDistance().get() - RENDER_DISTANCE_STEP);
                    applyRenderDistance(opt,newRender);
                } else {
                    int newRender = Math.min(baseRenderDistance, opt.renderDistance().get() + RENDER_DISTANCE_STEP);
                    applyRenderDistance(opt,newRender);
                    int newSim = Math.min(baseSimulationDistance, opt.simulationDistance().get() + SIMULATION_DISTANCE_STEP);
                    opt.simulationDistance().set(newSim);
                    opt.ambientOcclusion().set(baseAmbientOcclusion);
                    opt.particles().set(baseParticleStatus);
                }
            }
        }
    }

    public void resetQuality() {
        if (!initialized) return;

        Minecraft client = getClient();
        if (client == null || client.options == null) return;
        // 修复BUG: 原代码修改客户端Options未做异常捕获，客户端异常状态下会崩溃
        try {
            Options opt = client.options;
            // resetQuality 恢复用户原始值，不经过 CompatCoordinator 钳制（governor 禁用时应精确还原）
            opt.renderDistance().set(baseRenderDistance);
            opt.simulationDistance().set(baseSimulationDistance);
            opt.particles().set(baseParticleStatus);
            opt.entityShadows().set(baseEntityShadows);
            opt.ambientOcclusion().set(baseAmbientOcclusion);
            currentQualityLevel = 0;
            consecutiveLowFpsCount = 0;
            consecutiveHighFpsCount = 0;
            CoreSplitMod.LOGGER.info("[CoreSplit] Governor: Quality reset to base settings");
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] PerformanceGovernor resetQuality failed", e);
        }
        // 修复BUG: 重置画质时一并恢复用户原始帧率上限 + vsync 设置
        restoreDisplaySettings();
    }

    public void reinitBaseSettings() {
        Minecraft client = getClient();
        if (client == null || client.options == null) return;
        // 修复BUG: 若 governor 正在应用帧率上限/vsync 改动，需先恢复用户原始值，否则会把 targetFps/强制值 误当作 base 捕获
        restoreDisplaySettings();
        // 修复BUG: 原代码读取客户端Options未做异常捕获，客户端异常状态下会崩溃
        try {
            Options opt = client.options;
            baseRenderDistance = opt.renderDistance().get();
            baseSimulationDistance = opt.simulationDistance().get();
            baseParticleStatus = opt.particles().get();
            baseEntityShadows = opt.entityShadows().get();
            baseAmbientOcclusion = opt.ambientOcclusion().get();
            baseFramerateLimit = opt.framerateLimit().get();
            // 修复BUG: 与 init() 保持一致，重新捕获用户原始 vsync 设置
            baseVsync = opt.enableVsync().get();
            initialized = true;
            CoreSplitMod.LOGGER.info("[CoreSplit] Governor: Base settings reinitialized");
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] PerformanceGovernor reinitBaseSettings failed", e);
        }
    }

    public float getCurrentFps() {
        return calculateAverageFps();
    }

    public int getQualityLevel() {
        return currentQualityLevel;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setEnabled(boolean enabled) {
        boolean wasEnabled = this.enabled;
        this.enabled = enabled;
        if (enabled) {
            if (!initialized) {
                init();
            }
            // 修复BUG: 启用时应用目标帧率上限 + vsync 设置（幂等，仅值变化时才真正 set）
            applyDisplaySettings();
        } else if (wasEnabled) {
            // 修复BUG: 仅在 启用→禁用 的跳变时恢复一次，避免每 tick 重复 set
            restoreDisplaySettings();
        }
    }

    /**
     * 应用显示相关设置：目标帧率上限 + vsync。
     * 幂等：仅当目标值与上次应用值不同时才调用 OptionInstance.set()。
     * 注意 vsync 的 set() 会触发 surface 重建，必须避免重复调用。
     *
     * <p>光影优化：光影激活时强制开启 VSync，提供 GPU backpressure，
     * 避免着色器管线饱和导致的帧率暴跌（1000fps→30fps）。
     */
    private void applyDisplaySettings() {
        if (!enabled || !initialized) return;
        Minecraft client = getClient();
        if (client == null || client.options == null) return;

        // 1) 帧率上限
        int target = CoreSplitYaclConfig.getTargetFps();
        int value;
        if (target <= 0) {
            value = Options.UNLIMITED_FRAMERATE_CUTOFF;
        } else {
            value = Math.min(target, Options.UNLIMITED_FRAMERATE_CUTOFF);
            value = CompatCoordinator.getInstance().clampFramerateLimit(value);
        }
        if (value != lastAppliedFpsLimit) {
            try {
                client.options.framerateLimit().set(value);
                lastAppliedFpsLimit = value;
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to apply framerate limit {}", value, e);
            }
        }

        // 2) vsync
        // 光影下强制开启 vsync：GPU 满载时 vsync 提供天然 backpressure，比帧率上限更稳定
        // 非光影下：配置 disableVsync 开启时关闭 vsync，使帧率上限精确生效
        boolean desiredVsync;
        if (CompatCoordinator.getInstance().shouldForceVsyncUnderShaders()) {
            desiredVsync = true;
        } else {
            desiredVsync = !CoreSplitYaclConfig.isDisableVsync();
        }
        if (!lastAppliedVsyncSet || lastAppliedVsyncValue != desiredVsync) {
            try {
                client.options.enableVsync().set(desiredVsync);
                lastAppliedVsyncValue = desiredVsync;
                lastAppliedVsyncSet = true;
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to apply vsync setting {}", desiredVsync, e);
            }
        }
    }

    /**
     * 恢复用户原始显示设置（帧率上限 + vsync）。在 governor 禁用或重置时调用。
     */
    private void restoreDisplaySettings() {
        Minecraft client = getClient();
        if (client == null || client.options == null) return;
        try {
            client.options.framerateLimit().set(baseFramerateLimit);
            lastAppliedFpsLimit = -1;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to restore framerate limit", e);
        }
        // 仅当曾经应用过 vsync 改动时才恢复，避免无谓的 surface 重建
        if (lastAppliedVsyncSet) {
            try {
                client.options.enableVsync().set(baseVsync);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to restore vsync setting", e);
            }
            lastAppliedVsyncSet = false;
            lastAppliedVsyncValue = baseVsync;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void refreshDisplaySettings() {
        applyDisplaySettings();
    }

    private static Minecraft getClient() {
        try {
            return Minecraft.getInstance();
        } catch (Exception e) {
            return null;
        }
    }
}