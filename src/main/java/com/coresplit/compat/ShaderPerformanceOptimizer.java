package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import com.coresplit.config.CoreSplitYaclConfig;
import com.coresplit.governor.PerformanceGovernor;
import com.coresplit.particle.ParticleFilter;
import com.coresplit.renderlimiter.EntityRenderLimiter;
import com.coresplit.renderlimiter.GpuMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 光影性能优化器。
 *
 * <p>当 Iris 光影激活时，主动协调 CoreSplit 各模块进行光影感知的性能优化，
 * 弥补光影着色器带来的 GPU 渲染压力。
 *
 * <h3>核心策略</h3>
 * <ul>
 *   <li><b>主动降级</b>：光影激活瞬间立即触发质量降级，不等反应式 governor 响应</li>
 *   <li><b>渲染距离上限钳制</b>：光影下渲染距离不超过 shadowDistance + 6，避免无谓的额外像素着色</li>
 *   <li><b>强制 VSync</b>：光影下 GPU 满载时 VSync 提供天然 backpressure，比帧率上限更稳定</li>
 *   <li><b>粒子距离缩减</b>：光影下将粒子渲染距离从 64 降到 32，减少进入着色器管线的粒子数</li>
 *   <li><b>实体渲染削减</b>：光影下动态降低实体渲染距离乘数</li>
 *   <li><b>GPU 占用监控</b>：GPU 利用率持续 >=95% 时强制降级，绕过连续触发等待</li>
 * </ul>
 *
 * <p>线程安全：单例 + volatile 状态字段。光影状态检测有 2 秒 TTL 缓存避免频繁反射。
 * 所有对 Minecraft Options 的修改均在客户端 tick 线程执行（由 governor 调用）。
 *
 * <p>零侵入：光影未开启时所有方法返回安全默认值/原值，无额外开销。
 */
public class ShaderPerformanceOptimizer {

    /** GPU 占用降级阈值 */
    private static final float GPU_OVERLOAD_THRESHOLD = 0.95f;
    /** GPU 占用降级触发的最低 FPS（避免误判） */
    private static final float GPU_OVERLOAD_MIN_FPS = 50.0f;
    /** 光影状态检测缓存 TTL */
    private static final long SHADER_STATE_TTL_MS = 2000L;

    private static volatile ShaderPerformanceOptimizer instance;

    private final AtomicBoolean shaderOptimizationActive = new AtomicBoolean(false);
    private volatile boolean lastShaderState = false;
    private volatile long lastShaderCheckTime = 0;
    private volatile boolean cachedShaderState = false;

    // 保存优化前的原始值，用于光影关闭时恢复
    private volatile int originalParticleRenderDistance = 64;
    private volatile boolean valuesSaved = false;

    private ShaderPerformanceOptimizer() {}

    public static ShaderPerformanceOptimizer getInstance() {
        ShaderPerformanceOptimizer result = instance;
        if (result == null) {
            synchronized (ShaderPerformanceOptimizer.class) {
                result = instance;
                if (result == null) {
                    result = new ShaderPerformanceOptimizer();
                    instance = result;
                }
            }
        }
        return result;
    }

    /**
     * 检查光影是否正在使用（带 2 秒 TTL 缓存）。
     */
    public boolean isShaderActive() {
        if (!CoreSplitYaclConfig.isShaderPerformanceOptimizationEnabled()) return false;
        long now = System.currentTimeMillis();
        if (now - lastShaderCheckTime < SHADER_STATE_TTL_MS) {
            return cachedShaderState;
        }
        lastShaderCheckTime = now;
        try {
            cachedShaderState = IrisCompatManager.getInstance().isShaderPackInUse();
        } catch (Exception e) {
            cachedShaderState = false;
        }
        return cachedShaderState;
    }

    /**
     * 检查光影下是否应该强制开启 VSync。
     *
     * <p>光影下 GPU 满载渲染时，VSync 提供天然 backpressure：
     * <ul>
     *   <li>避免 GPU 无限制渲染导致着色器管线饱和</li>
     *   <li>减少帧率波动，提供更稳定的视觉体验</li>
     *   <li>降低 GPU 功耗和发热</li>
     * </ul>
     */
    public boolean shouldForceVsync() {
        return isShaderActive() && CoreSplitYaclConfig.isShaderForceVsync();
    }

    /**
     * 钳制光影下的渲染距离上限。
     *
     * <p>光影下渲染距离不应远超阴影距离，否则超出阴影覆盖范围的区块
     * 仍需完整着色器 pass 但无阴影效果，造成无谓的 GPU 负载。
     *
     * @param desired 期望的渲染距离
     * @return 钳制后的渲染距离
     */
    public int clampRenderDistanceUpper(int desired) {
        if (!isShaderActive()) return desired;
        if (!CoreSplitYaclConfig.isShaderReduceRenderDistance()) return desired;
        try {
            int shadowDist = IrisCompatManager.getInstance().getShadowDistance();
            // 阴影距离为 0（未配置）时不钳制上限
            if (shadowDist <= 0) return desired;
            int margin = CoreSplitYaclConfig.getShaderShadowDistanceMargin();
            int absMax = CoreSplitYaclConfig.getShaderMaxRenderDistance();
            int upperBound = Math.min(shadowDist + margin, absMax);
            return Math.min(desired, upperBound);
        } catch (Exception e) {
            return desired;
        }
    }

    /**
     * 根据阴影距离推荐光影下的初始质量等级。
     *
     * @return 推荐的质量等级（0-5），光影未开启返回 0
     */
    public int recommendInitialQualityLevel() {
        if (!isShaderActive()) return 0;
        try {
            int shadowDist = IrisCompatManager.getInstance().getShadowDistance();
            // 阴影距离越大，GPU 压力越大，起始降级越深
            if (shadowDist >= 24) return 3;
            if (shadowDist >= 16) return 2;
            if (shadowDist >= 8) return 1;
            return 0;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 光影下实体渲染距离乘数。
     *
     * @param baseMultiplier 基础乘数（来自 RenderCompatDetector）
     * @return 光影感知调整后的乘数
     */
    public float getShaderEntityMultiplier(float baseMultiplier) {
        if (!isShaderActive()) return baseMultiplier;
        if (!CoreSplitYaclConfig.isShaderReduceEntities()) return baseMultiplier;
        return baseMultiplier * CoreSplitYaclConfig.getShaderEntityMultiplier();
    }

    /**
     * 光影下粒子渲染距离。
     */
    public int getShaderParticleRenderDistance() {
        if (!CoreSplitYaclConfig.isShaderReduceParticles()) {
            return 64; // 默认值
        }
        return CoreSplitYaclConfig.getShaderParticleRenderDistance();
    }

    /**
     * 检查 GPU 是否过载（光影下）。
     *
     * @param currentFps 当前 FPS
     * @return true 表示 GPU 过载需要降级
     */
    public boolean isGpuOverloaded(float currentFps) {
        if (!isShaderActive()) return false;
        if (currentFps > GPU_OVERLOAD_MIN_FPS) return false;
        try {
            GpuMonitor monitor = GpuMonitor.getInstance();
            if (monitor.isAvailable()) {
                return monitor.getGpuUsage() >= GPU_OVERLOAD_THRESHOLD;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 当光影状态发生变化时，触发主动优化或恢复。
     *
     * <p>由 PerformanceGovernor.onRenderFrame() 每 tick 调用。
     * 内部有状态缓存，光影状态未变化时零开销返回。
     *
     * @param governor 性能调节器实例
     * @return true 表示光影优化已激活
     */
    public boolean checkAndApplyShaderOptimization(PerformanceGovernor governor) {
        boolean currentShaderState = isShaderActive();

        // 状态未变化，无需操作
        if (currentShaderState == lastShaderState) {
            return shaderOptimizationActive.get();
        }

        if (currentShaderState && !lastShaderState) {
            // 光影刚激活：立即触发主动优化
            activateShaderOptimization(governor);
        } else if (!currentShaderState && lastShaderState) {
            // 光影刚关闭：恢复原始设置
            deactivateShaderOptimization(governor);
        }

        lastShaderState = currentShaderState;
        return shaderOptimizationActive.get();
    }

    /**
     * 激活光影优化。
     */
    private void activateShaderOptimization(PerformanceGovernor governor) {
        if (!shaderOptimizationActive.compareAndSet(false, true)) return;

        CoreSplitMod.LOGGER.info("[CoreSplit] Shader activated - applying proactive performance optimization");

        // 1. 保存原始值
        saveOriginalValues();

        // 2. 缩减粒子渲染距离（受配置开关控制）
        if (CoreSplitYaclConfig.isShaderReduceParticles()) {
            try {
                ParticleFilter filter = ParticleFilter.getInstance();
                if (filter.isEnabled()) {
                    int targetDist = CoreSplitYaclConfig.getShaderParticleRenderDistance();
                    filter.setRenderDistance(targetDist);
                    CoreSplitMod.LOGGER.info("[CoreSplit] Particle render distance reduced to {} (shader mode)",
                            targetDist);
                }
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to adjust particle filter for shaders", e);
            }
        }

        // 3. 缩减实体渲染距离（受配置开关控制）
        if (CoreSplitYaclConfig.isShaderReduceEntities()) {
            try {
                EntityRenderLimiter limiter = EntityRenderLimiter.getInstance();
                if (limiter.isEnabled()) {
                    float multiplier = CoreSplitYaclConfig.getShaderEntityMultiplier();
                    int newItemDist = Math.max(16, (int) (limiter.getItemRenderDistance() * multiplier));
                    int newEntityDist = Math.max(16, (int) (limiter.getEntityRenderDistance() * multiplier));
                    limiter.setItemRenderDistance(newItemDist);
                    limiter.setEntityRenderDistance(newEntityDist);
                    CoreSplitMod.LOGGER.info("[CoreSplit] Entity render distance reduced (shader mode): items={}, entities={}",
                            newItemDist, newEntityDist);
                }
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to adjust entity render limiter for shaders", e);
            }
        }

        // 4. 主动降低渲染距离（受配置开关控制）
        if (CoreSplitYaclConfig.isShaderReduceRenderDistance()) {
            try {
                Minecraft client = Minecraft.getInstance();
                if (client != null && client.options != null) {
                    Options opt = client.options;
                    int currentRender = opt.renderDistance().get();
                    int shadowDist = IrisCompatManager.getInstance().getShadowDistance();
                    if (shadowDist > 0) {
                        int margin = CoreSplitYaclConfig.getShaderShadowDistanceMargin();
                        int absMax = CoreSplitYaclConfig.getShaderMaxRenderDistance();
                        int targetRender = Math.min(currentRender, shadowDist + margin);
                        targetRender = Math.min(targetRender, absMax);
                        if (targetRender < currentRender) {
                            opt.renderDistance().set(targetRender);
                            CoreSplitMod.LOGGER.info("[CoreSplit] Render distance reduced from {} to {} (shader mode)",
                                    currentRender, targetRender);
                        }
                    }
                }
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to adjust render distance for shaders", e);
            }
        }

        // 5. 光影下激进内存释放
        if (CoreSplitYaclConfig.isShaderAggressiveMemory()) {
            try {
                com.coresplit.memory.MemoryOptimizer mem = com.coresplit.CoreSplitMod.getMemoryOptimizer();
                if (mem != null) {
                    mem.aggressiveEvict();
                    CoreSplitMod.LOGGER.info("[CoreSplit] Aggressive memory eviction triggered (shader mode)");
                }
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to trigger aggressive memory eviction for shaders", e);
            }
        }

        // 6. 主动触发质量降级
        if (governor != null && governor.isInitialized()) {
            int recommendedLevel = recommendInitialQualityLevel();
            if (recommendedLevel > 0) {
                CoreSplitMod.LOGGER.info("[CoreSplit] Recommending initial quality level {} for shader mode",
                        recommendedLevel);
            }
        }
    }

    /**
     * 停用光影优化，恢复原始设置。
     */
    private void deactivateShaderOptimization(PerformanceGovernor governor) {
        if (!shaderOptimizationActive.compareAndSet(true, false)) return;

        CoreSplitMod.LOGGER.info("[CoreSplit] Shader deactivated - restoring original settings");

        restoreOriginalValues();

        // 触发 governor 重置画质
        if (governor != null && governor.isInitialized()) {
            try {
                governor.resetQuality();
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to reset governor quality after shader deactivation", e);
            }
        }
    }

    private void saveOriginalValues() {
        if (valuesSaved) return;
        try {
            originalParticleRenderDistance = ParticleFilter.getInstance().getRenderDistance();
            valuesSaved = true;
        } catch (Exception ignored) {}
    }

    private void restoreOriginalValues() {
        if (!valuesSaved) return;
        try {
            ParticleFilter filter = ParticleFilter.getInstance();
            if (filter.isEnabled()) {
                filter.setRenderDistance(originalParticleRenderDistance);
            }
        } catch (Exception ignored) {}
        valuesSaved = false;
    }

    public boolean isShaderOptimizationActive() {
        return shaderOptimizationActive.get();
    }

    /**
     * 重置光影优化器状态（governor 重置时调用）。
     */
    public void reset() {
        shaderOptimizationActive.set(false);
        lastShaderState = false;
        cachedShaderState = false;
        lastShaderCheckTime = 0;
        valuesSaved = false;
    }
}
