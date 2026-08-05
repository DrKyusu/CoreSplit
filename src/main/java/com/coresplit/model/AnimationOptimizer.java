package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class AnimationOptimizer {

    private static final int MAX_BATCH_SIZE = 64;
    private static final float DEFAULT_LOD_DISTANCE = 128.0f;
    private static final int LOD_LEVELS = 4;

    private final Map<String, AnimationBatch> animationBatches;
    private final Map<String, LodInfo> entityLodInfo;

    private volatile boolean batchRenderingEnabled = true;
    private volatile boolean lodEnabled = true;
    private volatile float lodDistance = DEFAULT_LOD_DISTANCE;

    public AnimationOptimizer() {
        this.animationBatches = new ConcurrentHashMap<>();
        this.entityLodInfo = new ConcurrentHashMap<>();

        CoreSplitMod.LOGGER.info("[CoreSplit] AnimationOptimizer initialized");
    }

    public void updateEntityDistance(String entityId, float distance) {
        LodInfo info = entityLodInfo.computeIfAbsent(entityId, LodInfo::new);
        info.distance = distance;
        info.lastUpdateTime = System.currentTimeMillis();
        info.lodLevel = calculateLodLevel(distance);
    }

    private int calculateLodLevel(float distance) {
        if (!lodEnabled) return 0;

        float normalizedDistance = distance / lodDistance;
        if (normalizedDistance < 0.25f) return 0;
        if (normalizedDistance < 0.5f) return 1;
        if (normalizedDistance < 0.75f) return 2;
        return 3;
    }

    public int getEntityLodLevel(String entityId) {
        LodInfo info = entityLodInfo.get(entityId);
        return info != null ? info.lodLevel : 0;
    }

    public boolean shouldRender(String entityId) {
        LodInfo info = entityLodInfo.get(entityId);
        if (info == null) return true;
        return info.lodLevel < LOD_LEVELS;
    }

    public boolean shouldAnimate(String entityId) {
        LodInfo info = entityLodInfo.get(entityId);
        if (info == null) return true;
        return info.lodLevel < 2;
    }

    public void addToBatch(String batchKey, String entityId) {
        if (!batchRenderingEnabled) return;

        AnimationBatch batch = animationBatches.computeIfAbsent(batchKey, AnimationBatch::new);
        batch.addEntity(entityId);

        if (batch.size() >= MAX_BATCH_SIZE) {
            processBatch(batch);
        }
    }

    public void processAllBatches() {
        for (AnimationBatch batch : animationBatches.values()) {
            if (!batch.isEmpty()) {
                processBatch(batch);
            }
        }
    }

    private void processBatch(AnimationBatch batch) {
        batch.process();
        batch.clear();
    }

    public void removeFromBatch(String batchKey, String entityId) {
        AnimationBatch batch = animationBatches.get(batchKey);
        if (batch != null) {
            batch.removeEntity(entityId);
        }
    }

    public void removeEntity(String entityId) {
        entityLodInfo.remove(entityId);
        for (AnimationBatch batch : animationBatches.values()) {
            batch.removeEntity(entityId);
        }
    }

    public void clearAll() {
        animationBatches.clear();
        entityLodInfo.clear();
    }

    public void setBatchRenderingEnabled(boolean enabled) {
        this.batchRenderingEnabled = enabled;
        if (!enabled) {
            animationBatches.clear();
        }
    }

    public void setLodEnabled(boolean enabled) {
        this.lodEnabled = enabled;
        if (!enabled) {
            for (LodInfo info : entityLodInfo.values()) {
                info.lodLevel = 0;
            }
        }
    }

    public void setLodDistance(float distance) {
        this.lodDistance = Math.max(16.0f, Math.min(512.0f, distance));
    }

    public boolean isBatchRenderingEnabled() {
        return batchRenderingEnabled;
    }

    public boolean isLodEnabled() {
        return lodEnabled;
    }

    public float getLodDistance() {
        return lodDistance;
    }

    public int getBatchCount() {
        return animationBatches.size();
    }

    public int getTotalEntitiesInBatches() {
        int total = 0;
        for (AnimationBatch batch : animationBatches.values()) {
            total += batch.size();
        }
        return total;
    }

    public int getActiveEntityCount() {
        return entityLodInfo.size();
    }

    public static class AnimationBatch {
        public final String batchKey;
        public final Set<String> entities;

        public AnimationBatch(String batchKey) {
            this.batchKey = batchKey;
            // 修复BUG: 原代码使用LinkedHashSet，addToBatch/removeEntity多线程并发修改时非线程安全；改用CopyOnWriteArraySet保证线程安全
            this.entities = new CopyOnWriteArraySet<>();
        }

        public void addEntity(String entityId) {
            entities.add(entityId);
        }

        public void removeEntity(String entityId) {
            entities.remove(entityId);
        }

        public int size() {
            return entities.size();
        }

        public boolean isEmpty() {
            return entities.isEmpty();
        }

        public void clear() {
            entities.clear();
        }

        public void process() {
        }

        public Set<String> getEntities() {
            return Collections.unmodifiableSet(entities);
        }
    }

    public static class LodInfo {
        public final String entityId;
        // 修复BUG: 原字段非volatile，updateEntityDistance写入后其他线程读取shouldRender/shouldAnimate可能看不到最新值（可见性问题）
        public volatile float distance;
        public volatile int lodLevel;
        public volatile long lastUpdateTime;

        public LodInfo(String entityId) {
            this.entityId = entityId;
            this.distance = 0;
            this.lodLevel = 0;
            this.lastUpdateTime = System.currentTimeMillis();
        }
    }
}