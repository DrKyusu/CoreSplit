package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class PhysicsCompatibilityLayer {

    // 修复BUG: 原 physicsOverrides 无上限，大规模实体场景下内存持续增长；
    // 添加上限 512，超限后拒绝创建新状态。
    // 修复BUG: 原警告日志无限流，压力测试下大量物理模组通知异常会刷屏日志；
    // 采用令牌桶限流（每 10 秒最多 5 条警告），避免日志风暴。
    private static final int MAX_OVERRIDES = 512;
    private static final long WARN_RATE_LIMIT_MS = 10_000L;
    private static final int WARN_MAX_PER_WINDOW = 5;

    private final Map<String, PhysicsModelOverride> physicsOverrides;
    private final Set<PhysicsModIntegration> physicsModIntegrations;

    private final AtomicLong lastWarnTime = new AtomicLong(0);
    private final AtomicLong warnCount = new AtomicLong(0);

    private volatile boolean enabled = true;
    private volatile ModelRenderMode defaultRenderMode = ModelRenderMode.CUSTOM;

    public PhysicsCompatibilityLayer() {
        this.physicsOverrides = new ConcurrentHashMap<>();
        this.physicsModIntegrations = new CopyOnWriteArraySet<>();

        CoreSplitMod.LOGGER.info("[CoreSplit] PhysicsCompatibilityLayer initialized");
    }

    // 警告限流
    private boolean allowWarn() {
        long now = System.currentTimeMillis();
        long last = lastWarnTime.get();
        if (now - last > WARN_RATE_LIMIT_MS) {
            lastWarnTime.set(now);
            warnCount.set(1);
            return true;
        }
        return warnCount.incrementAndGet() <= WARN_MAX_PER_WINDOW;
    }

    private PhysicsModelOverride getOrCreateOverride(String entityId) {
        PhysicsModelOverride override = physicsOverrides.get(entityId);
        if (override != null) {
            return override;
        }
        // 达到上限时拒绝创建新状态，避免内存无界增长
        if (physicsOverrides.size() >= MAX_OVERRIDES) {
            return null;
        }
        return physicsOverrides.computeIfAbsent(entityId, PhysicsModelOverride::new);
    }

    public ModelRenderMode getRenderMode(String entityId) {
        PhysicsModelOverride override = physicsOverrides.get(entityId);
        return override != null ? override.renderMode : defaultRenderMode;
    }

    public void setRenderMode(String entityId, ModelRenderMode renderMode) {
        PhysicsModelOverride override = getOrCreateOverride(entityId);
        if (override == null) return;
        override.renderMode = renderMode;

        CoreSplitMod.LOGGER.debug("[CoreSplit] Set render mode for {} to {}", entityId, renderMode);
    }

    public void setDefaultRenderMode(ModelRenderMode mode) {
        this.defaultRenderMode = mode;
    }

    public boolean shouldUseCustomModel(String entityId) {
        return getRenderMode(entityId) == ModelRenderMode.CUSTOM;
    }

    public void syncCollisionVolume(String entityId, float[] collisionBox) {
        PhysicsModelOverride override = getOrCreateOverride(entityId);
        if (override == null) return;
        // 修复BUG: 防御性拷贝，避免外部后续修改数组破坏内部状态
        override.collisionBox = collisionBox != null ? collisionBox.clone() : null;
        override.lastSyncTime = System.currentTimeMillis();
    }

    public float[] getCollisionVolume(String entityId) {
        PhysicsModelOverride override = physicsOverrides.get(entityId);
        // 修复BUG: 返回防御性拷贝，避免外部修改破坏内部状态
        return (override != null && override.collisionBox != null) ? override.collisionBox.clone() : null;
    }

    public void registerPhysicsModIntegration(PhysicsModIntegration integration) {
        physicsModIntegrations.add(integration);
        CoreSplitMod.LOGGER.info("[CoreSplit] Registered physics mod integration: {}", integration.getModId());
    }

    public void unregisterPhysicsModIntegration(PhysicsModIntegration integration) {
        physicsModIntegrations.remove(integration);
    }

    public void notifyPhysicsModsOfModelChange(String entityId) {
        for (PhysicsModIntegration integration : physicsModIntegrations) {
            try {
                integration.onModelChanged(entityId);
            } catch (Exception e) {
                if (allowWarn()) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Error notifying physics mod: {}", integration.getModId(), e);
                }
            }
        }
    }

    public void updatePhysicsState(String entityId, Map<String, Object> state) {
        for (PhysicsModIntegration integration : physicsModIntegrations) {
            try {
                integration.updateEntityState(entityId, state);
            } catch (Exception e) {
                if (allowWarn()) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Error updating physics state for mod: {}", integration.getModId(), e);
                }
            }
        }
    }

    public void clearEntityOverride(String entityId) {
        physicsOverrides.remove(entityId);
    }

    public void clearAllOverrides() {
        physicsOverrides.clear();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAllOverrides();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getActiveOverrideCount() {
        return physicsOverrides.size();
    }

    public int getRegisteredPhysicsModCount() {
        return physicsModIntegrations.size();
    }

    public enum ModelRenderMode {
        VANILLA,
        CUSTOM,
        HYBRID
    }

    public interface PhysicsModIntegration {
        String getModId();
        void onModelChanged(String entityId);
        void updateEntityState(String entityId, Map<String, Object> state);
    }

    public static class PhysicsModelOverride {
        public final String entityId;
        // 修复BUG: 原字段非volatile，setRenderMode/syncCollisionVolume写入后其他线程读取可能看不到最新值（可见性问题）
        public volatile ModelRenderMode renderMode = ModelRenderMode.CUSTOM;
        public volatile float[] collisionBox;
        public volatile long lastSyncTime;

        public PhysicsModelOverride(String entityId) {
            this.entityId = entityId;
            this.lastSyncTime = System.currentTimeMillis();
        }

        public void reset() {
            renderMode = ModelRenderMode.CUSTOM;
            collisionBox = null;
        }
    }
}