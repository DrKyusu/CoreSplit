package com.coresplit.ai;

import com.coresplit.CoreSplitMod;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AI 优化子配置，独立持久化到 coresplit_ai.toml。
 */
public class AiConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit_ai.toml");

    // 范围常量集中化
    private static final int MIN_BUCKET_RADIUS = 4;
    private static final int MAX_BUCKET_RADIUS = 256;
    private static final long MIN_TTL_MS = 5000;
    private static final long MAX_TTL_MS = 120000;
    private static final int MIN_CACHE_SIZE = 256;
    private static final int MAX_CACHE_SIZE = 32768;

    private boolean enabled = true;
    private boolean throttleEnabled = true;
    private int nearBucketRadius = 16;
    private int farBucketRadius = 96;
    private boolean offscreenPause = true;
    private boolean pathCacheEnabled = true;
    private long pathCacheTtlMs = 30000;
    private int pathCacheMaxSize = 4096;
    private boolean sharedPathEnabled = true;

    private static volatile AiConfig instance;

    public static AiConfig getInstance() {
        AiConfig result = instance;
        if (result == null) {
            synchronized (AiConfig.class) {
                result = instance;
                if (result == null) {
                    result = new AiConfig();
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
            throttleEnabled = fc.getOrElse("throttle_enabled", true);
            nearBucketRadius = fc.getOrElse("near_bucket_radius", 16);
            farBucketRadius = fc.getOrElse("far_bucket_radius", 96);
            offscreenPause = fc.getOrElse("offscreen_pause", true);
            pathCacheEnabled = fc.getOrElse("path_cache_enabled", true);
            pathCacheTtlMs = fc.getOrElse("path_cache_ttl_ms", 30000L);
            pathCacheMaxSize = fc.getOrElse("path_cache_max_size", 4096);
            sharedPathEnabled = fc.getOrElse("shared_path_enabled", true);
            validate();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load ai config", e);
        }
    }

    public void save() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# CoreSplit AI Optimization Configuration\n\n");
            sb.append("enabled = ").append(enabled).append("\n");
            sb.append("throttle_enabled = ").append(throttleEnabled).append("\n");
            sb.append("near_bucket_radius = ").append(nearBucketRadius).append("\n");
            sb.append("far_bucket_radius = ").append(farBucketRadius).append("\n");
            sb.append("offscreen_pause = ").append(offscreenPause).append("\n");
            sb.append("path_cache_enabled = ").append(pathCacheEnabled).append("\n");
            sb.append("path_cache_ttl_ms = ").append(pathCacheTtlMs).append("\n");
            sb.append("path_cache_max_size = ").append(pathCacheMaxSize).append("\n");
            sb.append("shared_path_enabled = ").append(sharedPathEnabled).append("\n");
            Files.writeString(CONFIG_PATH, sb.toString());
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to save ai config", e);
        }
    }

    private void validate() {
        nearBucketRadius = Math.max(MIN_BUCKET_RADIUS, Math.min(MAX_BUCKET_RADIUS, nearBucketRadius));
        farBucketRadius = Math.max(nearBucketRadius, Math.min(MAX_BUCKET_RADIUS, farBucketRadius));
        pathCacheTtlMs = Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, pathCacheTtlMs));
        pathCacheMaxSize = Math.max(MIN_CACHE_SIZE, Math.min(MAX_CACHE_SIZE, pathCacheMaxSize));
    }

    public boolean isEnabled() { return enabled; }
    public boolean isThrottleEnabled() { return throttleEnabled; }
    public int getNearBucketRadius() { return nearBucketRadius; }
    public int getFarBucketRadius() { return farBucketRadius; }
    public boolean isOffscreenPause() { return offscreenPause; }
    public boolean isPathCacheEnabled() { return pathCacheEnabled; }
    public long getPathCacheTtlMs() { return pathCacheTtlMs; }
    public int getPathCacheMaxSize() { return pathCacheMaxSize; }
    public boolean isSharedPathEnabled() { return sharedPathEnabled; }

    public void setEnabled(boolean v) { this.enabled = v; save(); }
    public void setThrottleEnabled(boolean v) { this.throttleEnabled = v; save(); }
    public void setNearBucketRadius(int v) { this.nearBucketRadius = v; validate(); save(); }
    public void setFarBucketRadius(int v) { this.farBucketRadius = v; validate(); save(); }
    public void setOffscreenPause(boolean v) { this.offscreenPause = v; save(); }
    public void setPathCacheEnabled(boolean v) { this.pathCacheEnabled = v; save(); }
    public void setPathCacheTtlMs(long v) { this.pathCacheTtlMs = v; validate(); save(); }
    public void setPathCacheMaxSize(int v) { this.pathCacheMaxSize = v; validate(); save(); }
    public void setSharedPathEnabled(boolean v) { this.sharedPathEnabled = v; save(); }
}
