package com.coresplit.renderlimiter;

import com.coresplit.CoreSplitMod;
import com.coresplit.compat.RenderCompatDetector;
import com.coresplit.compat.ShaderPerformanceOptimizer;
import net.minecraft.client.Minecraft;

/**
 * PERF-optimized entity render limiter.
 *
 * <p>Key optimizations (for 1000+ entity renders per frame):
 * <ul>
 *   <li>Per-frame cache: player position, compat mode, shader multiplier,
 *       adjusted render distances are computed ONCE in {@link #beginFrame()}
 *       instead of being re-fetched per entity.</li>
 *   <li>Squared-distance comparisons: Math.sqrt() is eliminated by comparing
 *       distanceSq against maxDistSq.</li>
 *   <li>Early-return short-circuit when limiter disabled or no player.</li>
 * </ul>
 */
public class EntityRenderLimiter {

    public enum EntityCategory {
        DROPPED_ITEM,
        LIVING_ENTITY
    }

    private static final float NEAR_DISTANCE = 16.0f;
    private static final float MID_DISTANCE = 32.0f;

    private static final float MID_SHOW_RATIO = 0.7f;
    private static final float FAR_SHOW_RATIO = 0.4f;

    private volatile boolean enabled = true;

    private volatile int maxDroppedItems = 200;
    private volatile int maxLivingEntities = 300;
    private volatile int itemRenderDistance = 64;
    private volatile int entityRenderDistance = 80;

    // PERF: Per-frame cache — populated by beginFrame(), read by shouldRender*.
    // Avoids per-entity Minecraft.getInstance(), compat detector, and shader
    // optimizer calls. All fields are plain (non-volatile) because they're
    // only accessed from the render thread after beginFrame() sets them.
    private float cachedPlayerX;
    private float cachedPlayerY;
    private float cachedPlayerZ;
    private boolean cachedHasPlayer;
    private float cachedItemMaxDistSq;
    private float cachedEntityMaxDistSq;
    private int cachedFrameId;

    // Incremented by beginFrame(); used to detect staleness and lazily refresh
    private int currentFrameId = 0;

    private static volatile EntityRenderLimiter instance;

    public static EntityRenderLimiter getInstance() {
        EntityRenderLimiter result = instance;
        if (result == null) {
            synchronized (EntityRenderLimiter.class) {
                result = instance;
                if (result == null) {
                    result = new EntityRenderLimiter();
                    instance = result;
                }
            }
        }
        return result;
    }

    private EntityRenderLimiter() {
    }

    /**
     * PERF: Call once at the beginning of each render frame to refresh the
     * per-frame cache. All subsequent shouldRender*() calls in the same
     * frame reuse this snapshot without redundant singleton lookups.
     *
     * <p>Called from {@link com.coresplit.CoreSplitClientMod#onClientTick}.
     */
    public void beginFrame() {
        if (!enabled) return;
        currentFrameId++;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                cachedPlayerX = (float) mc.player.getX();
                cachedPlayerY = (float) (mc.player.getY() + mc.player.getEyeHeight());
                cachedPlayerZ = (float) mc.player.getZ();
                cachedHasPlayer = true;
            } else {
                cachedHasPlayer = false;
            }
        } catch (Exception e) {
            cachedHasPlayer = false;
        }

        // PERF: Compute adjusted render distances once per frame
        try {
            RenderCompatDetector.CompatMode mode = RenderCompatDetector.getInstance().getCompatMode();
            float itemMultiplier = getMultiplierForMode(mode);
            float entityMultiplier = getMultiplierForMode(mode);

            // 光影感知
            try {
                itemMultiplier = ShaderPerformanceOptimizer.getInstance().getShaderEntityMultiplier(itemMultiplier);
                entityMultiplier = ShaderPerformanceOptimizer.getInstance().getShaderEntityMultiplier(entityMultiplier);
            } catch (Exception ignored) {}

            cachedItemMaxDistSq = (float) Math.pow(itemRenderDistance * itemMultiplier, 2);
            cachedEntityMaxDistSq = (float) Math.pow(entityRenderDistance * entityMultiplier, 2);
        } catch (Exception e) {
            cachedItemMaxDistSq = (float) Math.pow(itemRenderDistance, 2);
            cachedEntityMaxDistSq = (float) Math.pow(entityRenderDistance, 2);
        }

        cachedFrameId = currentFrameId;
    }

    private static float getMultiplierForMode(RenderCompatDetector.CompatMode mode) {
        return switch (mode) {
            case SODIUM_ONLY -> 1.1f;
            case IRIS_ONLY -> 0.9f;
            case SODIUM_IRIS -> 0.85f;
            case VANILLA -> 1.0f;
        };
    }

    public boolean shouldRenderItem(double x, double y, double z) {
        if (!enabled) return true;
        // PERF: early-exit when no player cached (no entities are visible anyway)
        if (!cachedHasPlayer) return true;

        double distSq = distanceSqToPlayer(x, y, z);

        // PERF: Use squared-distance comparison — eliminates Math.sqrt()
        if (distSq > cachedItemMaxDistSq) return false;

        // PERF: NEAR_DISTANCE squared for early return
        float nearSq = NEAR_DISTANCE * NEAR_DISTANCE;
        if (distSq <= nearSq) return true;

        // At this point we need the actual distance for the ratio check
        float dist = (float) Math.sqrt(distSq);
        int hash = stableHash(x, y, z);
        float ratio = (dist <= MID_DISTANCE) ? MID_SHOW_RATIO : FAR_SHOW_RATIO;

        int hashValue = Math.abs(hash % 1000);
        int threshold = (int) (ratio * 1000);

        return hashValue < threshold;
    }

    public boolean shouldRenderLivingEntity(double x, double y, double z) {
        if (!enabled) return true;
        if (!cachedHasPlayer) return true;

        double distSq = distanceSqToPlayer(x, y, z);

        if (distSq > cachedEntityMaxDistSq) return false;

        float nearSq = NEAR_DISTANCE * NEAR_DISTANCE;
        if (distSq <= nearSq) return true;

        float dist = (float) Math.sqrt(distSq);
        int hash = stableHash(x, y, z);
        float ratio = (dist <= MID_DISTANCE) ? MID_SHOW_RATIO : FAR_SHOW_RATIO;

        int hashValue = Math.abs(hash % 1000);
        int threshold = (int) (ratio * 1000);

        return hashValue < threshold;
    }

    private int stableHash(double x, double y, double z) {
        int ix = (int) Math.floor(x / 2.0);
        int iy = (int) Math.floor(y / 2.0);
        int iz = (int) Math.floor(z / 2.0);
        int h = ix * 73856093 ^ iy * 19349663 ^ iz * 83492791;
        h ^= (h >>> 13);
        h *= 1274126177;
        h ^= (h >>> 16);
        return h;
    }

    /**
     * PERF: Uses cached player position from beginFrame() instead of
     * calling Minecraft.getInstance() per entity.
     */
    private double distanceSqToPlayer(double x, double y, double z) {
        if (!cachedHasPlayer) return 0;
        double dx = x - cachedPlayerX;
        double dy = y - cachedPlayerY;
        double dz = z - cachedPlayerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public void setMaxDroppedItems(int value) {
        this.maxDroppedItems = Math.max(0, Math.min(1000, value));
    }

    public void setMaxLivingEntities(int value) {
        this.maxLivingEntities = Math.max(0, Math.min(1000, value));
    }

    public void setItemRenderDistance(int value) {
        this.itemRenderDistance = Math.max(16, Math.min(256, value));
    }

    public void setEntityRenderDistance(int value) {
        this.entityRenderDistance = Math.max(16, Math.min(256, value));
    }

    public int getMaxDroppedItems() { return maxDroppedItems; }
    public int getMaxLivingEntities() { return maxLivingEntities; }
    public int getItemRenderDistance() { return itemRenderDistance; }
    public int getEntityRenderDistance() { return entityRenderDistance; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
}
