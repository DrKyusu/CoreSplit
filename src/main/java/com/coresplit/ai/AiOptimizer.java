package com.coresplit.ai;

import com.coresplit.CoreSplitMod;
import com.coresplit.chunk.ChunkEngine;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * AI 优化模块门面（单例）。
 *
 * <p>职责：
 * <ol>
 *   <li>管理 {@link EntityAiThrottle}（距离分级节流）</li>
 *   <li>管理 {@link PathCache}（路径缓存）</li>
 *   <li>管理 {@link SharedPathRegistry}（路径共享）</li>
 *   <li>管理 {@link AiSpatialIndex}（空间分区查询）</li>
 *   <li>在 tick 回调中驱动节流计数器递增</li>
 * </ol>
 *
 * <p>PERF: Nearest-player position is refreshed ONCE per server tick via
 * {@link #refreshNearestPlayer(Server)}, then reused across all mob AI throttle
 * checks in the same tick. This eliminates the O(mobs × players) per-tick scan
 * that previously dominated the CPU cost.
 */
public class AiOptimizer {

    private static volatile AiOptimizer instance;

    private final EntityAiThrottle throttle;
    private final PathCache pathCache;
    private final SharedPathRegistry sharedPathRegistry;
    private final AiSpatialIndex spatialIndex;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    // PERF: Cached nearest-player position snapshot, refreshed once per server tick.
    // MobMixin reads these volatile fields directly instead of scanning the player
    // list for every entity — converting O(mobs × players) to O(players) per tick.
    private volatile float nearestPlayerX = 0;
    private volatile float nearestPlayerY = 0;
    private volatile float nearestPlayerZ = 0;
    private volatile boolean hasNearestPlayer = false;
    // Last tick counter when snapshot was taken — allows MobMixin to detect staleness
    private volatile long snapshotTick = -1;

    private AiOptimizer() {
        AiConfig config = AiConfig.getInstance();
        this.throttle = new EntityAiThrottle(
                config.getNearBucketRadius(),
                config.getFarBucketRadius(),
                config.isOffscreenPause());
        this.throttle.setEnabled(config.isThrottleEnabled());
        this.pathCache = new PathCache(config.getPathCacheTtlMs(), config.getPathCacheMaxSize());
        this.sharedPathRegistry = new SharedPathRegistry(config.getPathCacheTtlMs());
        // 复用 ChunkEngine 的 EntityFilter
        this.spatialIndex = new AiSpatialIndex(ChunkEngine.getInstance().getEntityFilter());
        this.enabled.set(config.isEnabled());
    }

    public static AiOptimizer getInstance() {
        AiOptimizer result = instance;
        if (result == null) {
            synchronized (AiOptimizer.class) {
                result = instance;
                if (result == null) {
                    result = new AiOptimizer();
                    instance = result;
                }
            }
        }
        return result;
    }

    /**
     * 服务端 tick 回调，递增节流计数器并刷新玩家位置缓存。
     *
     * @param server 服务端实例，用于查询玩家列表
     */
    public void onServerTick(MinecraftServer server) {
        if (!enabled.get()) return;
        throttle.onTick();
        refreshNearestPlayer(server);
    }

    /**
     * PERF: 遍历玩家列表一次以获取最近玩家位置。
     * 该方法在每 tick 仅调用一次，后续所有 MobMixin 钩子复用结果。
     * 对于多玩家场景，选择距离原点最近的玩家作为参考点，
     * 使得所有实体使用相同参考点进行节流判断。
     */
    private void refreshNearestPlayer(MinecraftServer server) {
        if (server == null) {
            hasNearestPlayer = false;
            return;
        }
        try {
            Iterable<net.minecraft.server.level.ServerLevel> levels = server.getAllLevels();
            if (levels == null) {
                hasNearestPlayer = false;
                return;
            }
            Player nearest = null;
            double minDistSq = Double.MAX_VALUE;
            for (net.minecraft.server.level.ServerLevel level : levels) {
                if (level == null) continue;
                for (Player p : level.players()) {
                    if (p == null || !p.isAlive()) continue;
                    double dx = p.getX();
                    double dz = p.getZ();
                    double distSq = dx * dx + dz * dz;
                    if (distSq < minDistSq) {
                        minDistSq = distSq;
                        nearest = p;
                    }
                }
            }
            if (nearest != null) {
                nearestPlayerX = (float) nearest.getX();
                nearestPlayerY = (float) nearest.getY();
                nearestPlayerZ = (float) nearest.getZ();
                hasNearestPlayer = true;
            } else {
                hasNearestPlayer = false;
            }
        } catch (Exception e) {
            hasNearestPlayer = false;
        }
    }

    /**
     * 由 MobMixin 调用，使用缓存的玩家位置判断实体是否应执行 AI。
     * 避免每实体调用 getNearestPlayer() 的 O(mobs×players) 开销。
     */
    public boolean shouldTickAi(double entityX, double entityY, double entityZ) {
        if (!enabled.get()) return true;
        if (!hasNearestPlayer) return true;

        return throttle.shouldTickAi(entityX, entityY, entityZ,
                nearestPlayerX, nearestPlayerY, nearestPlayerZ);
    }

    /**
     * 由 MobMixin 调用，获取最近玩家位置（已缓存）。
     */
    public float getNearestPlayerX() { return nearestPlayerX; }
    public float getNearestPlayerY() { return nearestPlayerY; }
    public float getNearestPlayerZ() { return nearestPlayerZ; }
    public boolean hasNearestPlayer() { return hasNearestPlayer; }
    public long getSnapshotTick() { return snapshotTick; }

    public void reloadConfig() {
        AiConfig config = AiConfig.getInstance();
        enabled.set(config.isEnabled());
        throttle.setEnabled(config.isThrottleEnabled());
        throttle.setNearRadius(config.getNearBucketRadius());
        throttle.setFarRadius(config.getFarBucketRadius());
        throttle.setOffscreenPause(config.isOffscreenPause());
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public EntityAiThrottle getThrottle() { return throttle; }
    public PathCache getPathCache() { return pathCache; }
    public SharedPathRegistry getSharedPathRegistry() { return sharedPathRegistry; }
    public AiSpatialIndex getSpatialIndex() { return spatialIndex; }

    public void shutdown() {
        pathCache.clear();
        sharedPathRegistry.clear();
        throttle.resetStats();
        CoreSplitMod.LOGGER.info("[CoreSplit] AiOptimizer shutdown");
    }
}
