package com.coresplit.memory;

import com.coresplit.CoreSplitMod;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内存管理子配置，独立持久化到 coresplit_memory.toml。
 */
public class MemoryConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit_memory.toml");

    // 范围常量集中化
    private static final int MIN_POOL_SIZE = 128;
    private static final int MAX_POOL_SIZE = 16384;
    private static final int MIN_TEXTURE_CACHE = 256;
    private static final int MAX_TEXTURE_CACHE = 8192;
    private static final long MIN_EVICT_INTERVAL = 1000;
    private static final long MAX_EVICT_INTERVAL = 60000;
    private static final int MIN_AGGRESSIVE_MB = 1024;
    private static final int MAX_AGGRESSIVE_MB = 8192;

    private boolean enabled = true;
    private int entityDataPoolSize = 1024;
    private boolean byteBufferPoolEnabled = true;
    private int textureCacheMax = 2048;
    private long evictIntervalMs = 10000;
    private int aggressiveEvictBelowMb = 4096;

    private static volatile MemoryConfig instance;

    public static MemoryConfig getInstance() {
        MemoryConfig result = instance;
        if (result == null) {
            synchronized (MemoryConfig.class) {
                result = instance;
                if (result == null) {
                    result = new MemoryConfig();
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
            entityDataPoolSize = fc.getOrElse("entity_data_pool_size", 1024);
            byteBufferPoolEnabled = fc.getOrElse("bytebuffer_pool_enabled", true);
            textureCacheMax = fc.getOrElse("texture_cache_max", 2048);
            evictIntervalMs = fc.getOrElse("evict_interval_ms", 10000L);
            aggressiveEvictBelowMb = fc.getOrElse("aggressive_evict_below_mb", 4096);
            validate();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load memory config", e);
        }
    }

    public void save() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# CoreSplit Memory Optimization Configuration\n\n");
            sb.append("enabled = ").append(enabled).append("\n");
            sb.append("entity_data_pool_size = ").append(entityDataPoolSize).append("\n");
            sb.append("bytebuffer_pool_enabled = ").append(byteBufferPoolEnabled).append("\n");
            sb.append("texture_cache_max = ").append(textureCacheMax).append("\n");
            sb.append("evict_interval_ms = ").append(evictIntervalMs).append("\n");
            sb.append("aggressive_evict_below_mb = ").append(aggressiveEvictBelowMb).append("\n");
            Files.writeString(CONFIG_PATH, sb.toString());
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to save memory config", e);
        }
    }

    private void validate() {
        entityDataPoolSize = Math.max(MIN_POOL_SIZE, Math.min(MAX_POOL_SIZE, entityDataPoolSize));
        textureCacheMax = Math.max(MIN_TEXTURE_CACHE, Math.min(MAX_TEXTURE_CACHE, textureCacheMax));
        evictIntervalMs = Math.max(MIN_EVICT_INTERVAL, Math.min(MAX_EVICT_INTERVAL, evictIntervalMs));
        aggressiveEvictBelowMb = Math.max(MIN_AGGRESSIVE_MB, Math.min(MAX_AGGRESSIVE_MB, aggressiveEvictBelowMb));
    }

    public boolean isEnabled() { return enabled; }
    public int getEntityDataPoolSize() { return entityDataPoolSize; }
    public boolean isByteBufferPoolEnabled() { return byteBufferPoolEnabled; }
    public int getTextureCacheMax() { return textureCacheMax; }
    public long getEvictIntervalMs() { return evictIntervalMs; }
    public int getAggressiveEvictBelowMb() { return aggressiveEvictBelowMb; }

    public void setEnabled(boolean v) { this.enabled = v; save(); }
    public void setEntityDataPoolSize(int v) { this.entityDataPoolSize = v; validate(); save(); }
    public void setByteBufferPoolEnabled(boolean v) { this.byteBufferPoolEnabled = v; save(); }
    public void setTextureCacheMax(int v) { this.textureCacheMax = v; validate(); save(); }
    public void setEvictIntervalMs(long v) { this.evictIntervalMs = v; validate(); save(); }
    public void setAggressiveEvictBelowMb(int v) { this.aggressiveEvictBelowMb = v; validate(); save(); }
}
