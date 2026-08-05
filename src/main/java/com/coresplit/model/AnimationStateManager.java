package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class AnimationStateManager {

    // 修复BUG: 原 entityVariables 无上限，大规模实体场景下内存持续增长；
    // 添加上限 1024，超限后拒绝创建新状态。
    // 修复BUG: 原警告日志无限流，压力测试下大量资源包重载监听器异常会刷屏日志；
    // 采用令牌桶限流（每 10 秒最多 5 条警告），避免日志风暴。
    private static final int MAX_ENTITY_STATES = 1024;
    private static final long WARN_RATE_LIMIT_MS = 10_000L;
    private static final int WARN_MAX_PER_WINDOW = 5;

    private final Map<String, EntityAnimationVariables> entityVariables;
    private final Set<ResourcePackReloadListener> reloadListeners;

    private final AtomicLong lastWarnTime = new AtomicLong(0);
    private final AtomicLong warnCount = new AtomicLong(0);

    private volatile boolean enabled = true;

    public AnimationStateManager() {
        this.entityVariables = new ConcurrentHashMap<>();
        this.reloadListeners = new CopyOnWriteArraySet<>();

        CoreSplitMod.LOGGER.info("[CoreSplit] AnimationStateManager initialized");
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

    public void onResourcePackReload() {
        if (!enabled) return;

        CoreSplitMod.LOGGER.info("[CoreSplit] Resource pack reload detected, resetting animation states");

        resetAllEntityVariables();

        for (ResourcePackReloadListener listener : reloadListeners) {
            try {
                listener.onResourcePackReload();
            } catch (Exception e) {
                if (allowWarn()) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Error in resource pack reload listener", e);
                }
            }
        }
    }

    public void resetAllEntityVariables() {
        for (EntityAnimationVariables vars : entityVariables.values()) {
            vars.reset();
        }
    }

    public void resetEntityVariables(String entityId) {
        EntityAnimationVariables vars = entityVariables.get(entityId);
        if (vars != null) {
            vars.reset();
        }
    }

    public void setVariable(String entityId, String variableName, float value) {
        EntityAnimationVariables vars = getOrCreateVariables(entityId);
        if (vars != null) {
            vars.var.put(variableName, value);
        }
    }

    public void setBooleanVariable(String entityId, String variableName, boolean value) {
        EntityAnimationVariables vars = getOrCreateVariables(entityId);
        if (vars != null) {
            vars.varb.put(variableName, value);
        }
    }

    public float getVariable(String entityId, String variableName) {
        EntityAnimationVariables vars = entityVariables.get(entityId);
        return vars != null ? vars.var.getOrDefault(variableName, 0.0f) : 0.0f;
    }

    public boolean getBooleanVariable(String entityId, String variableName) {
        EntityAnimationVariables vars = entityVariables.get(entityId);
        return vars != null && vars.varb.getOrDefault(variableName, false);
    }

    public void incrementVariable(String entityId, String variableName, float delta) {
        EntityAnimationVariables vars = getOrCreateVariables(entityId);
        if (vars != null) {
            vars.var.merge(variableName, delta, Float::sum);
        }
    }

    public void toggleVariable(String entityId, String variableName) {
        EntityAnimationVariables vars = getOrCreateVariables(entityId);
        if (vars != null) {
            vars.varb.put(variableName, !vars.varb.getOrDefault(variableName, false));
        }
    }

    public void saveState(String entityId) {
        EntityAnimationVariables vars = entityVariables.get(entityId);
        if (vars != null) {
            vars.saveSnapshot();
        }
    }

    public void restoreState(String entityId) {
        EntityAnimationVariables vars = entityVariables.get(entityId);
        if (vars != null) {
            vars.restoreSnapshot();
        }
    }

    public void registerReloadListener(ResourcePackReloadListener listener) {
        reloadListeners.add(listener);
    }

    public void unregisterReloadListener(ResourcePackReloadListener listener) {
        reloadListeners.remove(listener);
    }

    public void clearEntityState(String entityId) {
        entityVariables.remove(entityId);
    }

    public void clearAllStates() {
        entityVariables.clear();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAllStates();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getActiveEntityCount() {
        return entityVariables.size();
    }

    public int getTotalVariableCount() {
        int total = 0;
        for (EntityAnimationVariables vars : entityVariables.values()) {
            total += vars.var.size() + vars.varb.size();
        }
        return total;
    }

    private EntityAnimationVariables getOrCreateVariables(String entityId) {
        EntityAnimationVariables vars = entityVariables.get(entityId);
        if (vars != null) {
            return vars;
        }
        // 达到上限时拒绝创建新状态，避免内存无界增长
        if (entityVariables.size() >= MAX_ENTITY_STATES) {
            return null;
        }
        return entityVariables.computeIfAbsent(entityId, EntityAnimationVariables::new);
    }

    public interface ResourcePackReloadListener {
        void onResourcePackReload();
    }

    public static class EntityAnimationVariables {
        public final String entityId;
        public final Map<String, Float> var;
        public final Map<String, Boolean> varb;

        // 修复BUG: 原字段非volatile，saveSnapshot写入后其他线程调用restoreSnapshot可能看不到最新快照（可见性问题）
        private volatile Map<String, Float> savedVar;
        private volatile Map<String, Boolean> savedVarb;

        public EntityAnimationVariables(String entityId) {
            this.entityId = entityId;
            this.var = new ConcurrentHashMap<>();
            this.varb = new ConcurrentHashMap<>();
        }

        public void reset() {
            var.clear();
            varb.clear();
        }

        public void saveSnapshot() {
            savedVar = new ConcurrentHashMap<>(var);
            savedVarb = new ConcurrentHashMap<>(varb);
        }

        public void restoreSnapshot() {
            if (savedVar != null) {
                var.clear();
                var.putAll(savedVar);
            }
            if (savedVarb != null) {
                varb.clear();
                varb.putAll(savedVarb);
            }
        }

        public int getVariableCount() {
            return var.size() + varb.size();
        }
    }
}