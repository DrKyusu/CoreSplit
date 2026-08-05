package com.coresplit.itemrender;

import com.coresplit.CoreSplitMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ItemRenderOptimizer {

    public enum ItemRenderLevel {
        FULL(0),
        HIDDEN(1);

        public final int level;

        ItemRenderLevel(int level) {
            this.level = level;
        }
    }

    public static class ItemRenderState {
        public final int entityId;
        public volatile ItemRenderLevel targetLevel = ItemRenderLevel.FULL;
        public volatile ItemRenderLevel currentLevel = ItemRenderLevel.FULL;
        public volatile float fadeAlpha = 1.0f;
        public volatile boolean animationPaused = false;
        public volatile long lastUpdateTime = 0;
        public volatile double lastDistance = 0;

        public ItemRenderState(int entityId) {
            this.entityId = entityId;
        }
    }

    private static final int MAX_TRACKED_ITEMS = 4096;
    private static final long STATE_TTL_MS = 10_000L;
    private static final float FADE_SPEED_PER_FRAME = 0.08f;

    private final ItemRenderConfig config;
    private final FrustumCuller frustumCuller;
    private final ConcurrentHashMap<Integer, ItemRenderState> itemStates = new ConcurrentHashMap<>();

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger lastCleanupTick = new AtomicInteger(0);
    private volatile long lastFrameUpdateTime = 0;
    private static final long FRAME_UPDATE_INTERVAL_NS = 1_000_000L;

    private static volatile ItemRenderOptimizer instance;

    public static ItemRenderOptimizer getInstance() {
        ItemRenderOptimizer result = instance;
        if (result == null) {
            synchronized (ItemRenderOptimizer.class) {
                result = instance;
                if (result == null) {
                    result = new ItemRenderOptimizer();
                    instance = result;
                }
            }
        }
        return result;
    }

    private ItemRenderOptimizer() {
        this.config = ItemRenderConfig.getInstance();
        this.frustumCuller = new FrustumCuller(config);
        this.enabled.set(config.isEnabled());
    }

    public void onClientTick(Minecraft mc) {
        if (!enabled.get() || mc.player == null || mc.level == null) return;

        int tick = (int) (mc.level.getGameTime() % 20000);
        if (tick - lastCleanupTick.get() >= 20 || tick < lastCleanupTick.get()) {
            lastCleanupTick.set(tick);
            cleanupExpiredStates();
        }
    }

    public void preRenderFrame(Minecraft mc, float partialTick) {
        if (!enabled.get()) return;
        long now = System.nanoTime();
        if (now - lastFrameUpdateTime > 500_000L) {
            lastFrameUpdateTime = now;
            frustumCuller.updateCamera(mc, partialTick);
        }
    }

    public ItemRenderState getOrCreateState(int entityId) {
        ItemRenderState state = itemStates.get(entityId);
        if (state != null) return state;

        if (itemStates.size() >= MAX_TRACKED_ITEMS) {
            evictOldestStates();
        }

        state = new ItemRenderState(entityId);
        state.lastUpdateTime = System.currentTimeMillis();
        ItemRenderState existing = itemStates.putIfAbsent(entityId, state);
        return existing != null ? existing : state;
    }

    public ItemRenderLevel computeRenderLevel(ItemEntity itemEntity, Minecraft mc) {
        if (!enabled.get()) return ItemRenderLevel.FULL;
        if (mc.player == null) return ItemRenderLevel.FULL;

        double px = mc.player.getX();
        double py = mc.player.getY() + mc.player.getEyeHeight();
        double pz = mc.player.getZ();

        double ix = itemEntity.getX();
        double iy = itemEntity.getY() + 0.25;
        double iz = itemEntity.getZ();

        double dx = ix - px;
        double dy = iy - py;
        double dz = iz - pz;
        double distSq = dx * dx + dy * dy + dz * dz;
        double dist = Math.sqrt(distSq);

        if (config.isDistanceLod()) {
            double far = config.getFarDistance();
            if (dist > far) {
                return ItemRenderLevel.HIDDEN;
            }
        }

        if (config.isFrustumCulling()) {
            boolean inView = frustumCuller.isInView(ix, iy, iz, 0.5f);
            if (!inView) {
                double near = config.getNearDistance();
                if (dist > near) {
                    return ItemRenderLevel.HIDDEN;
                }
            }
        }

        return ItemRenderLevel.FULL;
    }

    public boolean shouldAnimate(ItemEntity itemEntity, Minecraft mc) {
        if (!enabled.get()) return true;
        if (!config.isOffscreenAnimationPause()) return true;
        if (mc.player == null) return true;

        double ix = itemEntity.getX();
        double iy = itemEntity.getY() + 0.25;
        double iz = itemEntity.getZ();

        boolean inView = frustumCuller.isInViewFast(ix, iy, iz);
        if (!inView) {
            double distSq = itemEntity.distanceToSqr(mc.player);
            if (distSq > (double) config.getNearDistance() * config.getNearDistance()) {
                return false;
            }
        }
        return true;
    }

    public void updateFade(ItemRenderState state, ItemRenderLevel target, float partialTick) {
        if (!config.isSmoothFade()) {
            state.currentLevel = target;
            state.fadeAlpha = 1.0f;
            return;
        }

        state.targetLevel = target;

        if (target == state.currentLevel) {
            if (target == ItemRenderLevel.FULL && state.fadeAlpha < 1.0f) {
                state.fadeAlpha = Math.min(1.0f, state.fadeAlpha + FADE_SPEED_PER_FRAME);
            }
            return;
        }

        if (target == ItemRenderLevel.HIDDEN) {
            state.fadeAlpha = Math.max(0f, state.fadeAlpha - FADE_SPEED_PER_FRAME);
            if (state.fadeAlpha <= 0.01f) {
                state.currentLevel = ItemRenderLevel.HIDDEN;
                state.fadeAlpha = 0.0f;
            }
        } else {
            state.currentLevel = ItemRenderLevel.FULL;
            state.fadeAlpha = Math.min(1.0f, state.fadeAlpha + FADE_SPEED_PER_FRAME);
        }
    }

    public float getFadeAlpha(ItemRenderState state) {
        if (!config.isSmoothFade()) return 1.0f;
        return state.fadeAlpha;
    }

    public boolean shouldSkipRender(ItemRenderState state) {
        if (!config.isSmoothFade()) {
            return state.currentLevel == ItemRenderLevel.HIDDEN;
        }
        return state.currentLevel == ItemRenderLevel.HIDDEN && state.fadeAlpha <= 0.01f;
    }

    private void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        itemStates.entrySet().removeIf(entry -> now - entry.getValue().lastUpdateTime > STATE_TTL_MS);
    }

    private void evictOldestStates() {
        long oldestTime = Long.MAX_VALUE;
        Integer oldestId = null;

        int count = 0;
        for (Map.Entry<Integer, ItemRenderState> entry : itemStates.entrySet()) {
            if (entry.getValue().lastUpdateTime < oldestTime) {
                oldestTime = entry.getValue().lastUpdateTime;
                oldestId = entry.getKey();
            }
            if (++count > 200) break;
        }

        if (oldestId != null) {
            itemStates.remove(oldestId);
        }
    }

    public void reloadConfig() {
        config.load();
        enabled.set(config.isEnabled());
        frustumCuller.updateMargin();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public FrustumCuller getFrustumCuller() {
        return frustumCuller;
    }

    public int getTrackedItemCount() {
        return itemStates.size();
    }

    public void removeState(int entityId) {
        itemStates.remove(entityId);
    }

    public void clearAllStates() {
        itemStates.clear();
    }

    public void shutdown() {
        clearAllStates();
        CoreSplitMod.LOGGER.info("[CoreSplit] ItemRenderOptimizer shutdown");
    }
}
