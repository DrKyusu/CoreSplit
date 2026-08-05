package com.coresplit.explosion;

import com.coresplit.CoreSplitMod;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 爆炸优化子配置，独立持久化到 coresplit_explosion.toml。
 * 仿照 {@link com.coresplit.chunk.ChunkEngineConfig} 模式：volatile 单例 + 双重检查锁 + 范围校验。
 */
public class ExplosionConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit_explosion.toml");

    // 范围常量集中化，符合项目约束"所有配置值必须包含范围校验"
    private static final int MIN_MAX_PER_FRAME = 1;
    private static final int MAX_MAX_PER_FRAME = 500;
    private static final int MIN_MAX_PER_TICK = 1;
    private static final int MAX_MAX_PER_TICK = 2000;
    private static final int MIN_FAR_BLOCK_DISTANCE = 8;
    private static final int MAX_FAR_BLOCK_DISTANCE = 128;

    private boolean enabled = true;
    private boolean asyncProcessing = true;
    private int maxPerFrame = 50;
    private int maxPerTick = 200;
    private String particleLevel = "MEDIUM";
    private boolean skipFarBlockImpact = true;
    private int farBlockDistance = 32;

    private static volatile ExplosionConfig instance;

    public static ExplosionConfig getInstance() {
        ExplosionConfig result = instance;
        if (result == null) {
            synchronized (ExplosionConfig.class) {
                result = instance;
                if (result == null) {
                    result = new ExplosionConfig();
                    result.load();
                    instance = result;
                }
            }
        }
        return result;
    }

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try (FileConfig fc = FileConfig.of(CONFIG_PATH)) {
            fc.load();
            enabled = fc.getOrElse("enabled", true);
            asyncProcessing = fc.getOrElse("async_processing", true);
            maxPerFrame = fc.getOrElse("max_per_frame", 50);
            maxPerTick = fc.getOrElse("max_per_tick", 200);
            particleLevel = fc.getOrElse("particle_level", "MEDIUM");
            skipFarBlockImpact = fc.getOrElse("skip_far_block_impact", true);
            farBlockDistance = fc.getOrElse("far_block_distance", 32);
            validate();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load explosion config", e);
        }
    }

    public void save() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# CoreSplit Explosion Optimization Configuration\n\n");
            sb.append("enabled = ").append(enabled).append("\n");
            sb.append("async_processing = ").append(asyncProcessing).append("\n");
            sb.append("max_per_frame = ").append(maxPerFrame).append("\n");
            sb.append("max_per_tick = ").append(maxPerTick).append("\n");
            sb.append("particle_level = \"").append(particleLevel).append("\"\n");
            sb.append("skip_far_block_impact = ").append(skipFarBlockImpact).append("\n");
            sb.append("far_block_distance = ").append(farBlockDistance).append("\n");
            Files.writeString(CONFIG_PATH, sb.toString());
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to save explosion config", e);
        }
    }

    private void validate() {
        maxPerFrame = Math.max(MIN_MAX_PER_FRAME, Math.min(MAX_MAX_PER_FRAME, maxPerFrame));
        maxPerTick = Math.max(MIN_MAX_PER_TICK, Math.min(MAX_MAX_PER_TICK, maxPerTick));
        farBlockDistance = Math.max(MIN_FAR_BLOCK_DISTANCE, Math.min(MAX_FAR_BLOCK_DISTANCE, farBlockDistance));
        // 粒子等级必须是合法枚举值
        if (!"LOW".equals(particleLevel) && !"MEDIUM".equals(particleLevel) && !"HIGH".equals(particleLevel)) {
            particleLevel = "MEDIUM";
        }
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAsyncProcessing() { return asyncProcessing; }
    public int getMaxPerFrame() { return maxPerFrame; }
    public int getMaxPerTick() { return maxPerTick; }
    public String getParticleLevel() { return particleLevel; }
    public boolean isSkipFarBlockImpact() { return skipFarBlockImpact; }
    public int getFarBlockDistance() { return farBlockDistance; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; save(); }
    public void setAsyncProcessing(boolean v) { this.asyncProcessing = v; save(); }
    public void setMaxPerFrame(int v) { this.maxPerFrame = v; validate(); save(); }
    public void setMaxPerTick(int v) { this.maxPerTick = v; validate(); save(); }
    public void setParticleLevel(String v) { this.particleLevel = v; validate(); save(); }
    public void setSkipFarBlockImpact(boolean v) { this.skipFarBlockImpact = v; save(); }
    public void setFarBlockDistance(int v) { this.farBlockDistance = v; validate(); save(); }

    public ExplosionParticleLimiter.ParticleLevel getParticleLevelEnum() {
        try {
            return ExplosionParticleLimiter.ParticleLevel.valueOf(particleLevel);
        } catch (IllegalArgumentException e) {
            return ExplosionParticleLimiter.ParticleLevel.MEDIUM;
        }
    }
}
