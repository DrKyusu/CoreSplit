package com.coresplit.chunk;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityFilter {

    private final ConcurrentHashMap<Long, EntityPartition> partitions;
    private final ConcurrentHashMap<Integer, EntityInfo> entities;
    
    private final int partitionSize;
    private final int maxEntitiesPerPartition;
    
    private final AtomicInteger totalEntityCount = new AtomicInteger(0);
    private final AtomicInteger filteredEntityCount = new AtomicInteger(0);
    
    private volatile boolean enabled = true;
    private volatile boolean noTickMode = false;

    public EntityFilter(int partitionSize, int maxEntitiesPerPartition) {
        this.partitionSize = Math.max(8, Math.min(64, partitionSize));
        this.maxEntitiesPerPartition = Math.max(10, Math.min(500, maxEntitiesPerPartition));
        
        this.partitions = new ConcurrentHashMap<>();
        this.entities = new ConcurrentHashMap<>();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] EntityFilter initialized - partitionSize:{}, maxPerPartition:{}", 
                this.partitionSize, this.maxEntitiesPerPartition);
    }

    public void addEntity(int entityId, double x, double y, double z, boolean isNoTickRelevant) {
        if (!enabled) return;

        long partitionKey = getPartitionKey(x, z);
        EntityPartition partition = partitions.computeIfAbsent(partitionKey,
                k -> new EntityPartition(k, partitionSize));

        EntityInfo info = new EntityInfo(entityId, x, y, z, isNoTickRelevant);
        // 修复BUG: 若 entityId 已存在，原代码直接 put 覆盖但不从旧分区移除，导致旧分区残留引用
        // 且 totalEntityCount 重复递增，先移除旧实体再添加新实体
        EntityInfo oldInfo = entities.put(entityId, info);
        if (oldInfo != null) {
            long oldPartitionKey = getPartitionKey(oldInfo.x, oldInfo.z);
            EntityPartition oldPartition = partitions.get(oldPartitionKey);
            if (oldPartition != null) {
                oldPartition.removeEntity(entityId);
            }
        } else {
            totalEntityCount.incrementAndGet();
        }
        partition.addEntity(info);

        // 修复BUG: maxEntitiesPerPartition 已定义但从未检查，超限时应记录警告以便排查
        if (partition.getEntityCount() > maxEntitiesPerPartition) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Entity partition {} exceeds max entities {}",
                    partitionKey, maxEntitiesPerPartition);
        }
    }

    public void updateEntity(int entityId, double x, double y, double z) {
        if (!enabled) return;

        EntityInfo info = entities.get(entityId);
        if (info == null) return;

        // 修复BUG: 位置更新与分区迁移非原子，并发更新同一实体时可能残留于多个分区或丢失分区引用，
        // 对 info 加锁保证读旧位置、写新位置、迁移分区三步原子执行
        synchronized (info) {
            long oldPartitionKey = getPartitionKey(info.x, info.z);
            long newPartitionKey = getPartitionKey(x, z);

            info.updatePosition(x, y, z);

            if (oldPartitionKey != newPartitionKey) {
                EntityPartition oldPartition = partitions.get(oldPartitionKey);
                if (oldPartition != null) {
                    oldPartition.removeEntity(entityId);
                }

                EntityPartition newPartition = partitions.computeIfAbsent(newPartitionKey,
                        k -> new EntityPartition(k, partitionSize));
                newPartition.addEntity(info);
            }
        }
    }

    public void removeEntity(int entityId) {
        EntityInfo info = entities.remove(entityId);
        if (info == null) return;

        // 修复BUG: 与 updateEntity 的分区迁移存在竞态，可能移除时实体正被迁移到新分区，
        // 导致残留引用，对 info 加锁与 updateEntity 互斥
        synchronized (info) {
            long partitionKey = getPartitionKey(info.x, info.z);
            EntityPartition partition = partitions.get(partitionKey);
            if (partition != null) {
                partition.removeEntity(entityId);
            }
        }

        totalEntityCount.decrementAndGet();
    }

    public List<EntityInfo> getEntitiesInRange(double centerX, double centerZ, double range) {
        List<EntityInfo> result = new ArrayList<>();
        
        int minPartitionX = (int) Math.floor((centerX - range) / partitionSize);
        int maxPartitionX = (int) Math.floor((centerX + range) / partitionSize);
        int minPartitionZ = (int) Math.floor((centerZ - range) / partitionSize);
        int maxPartitionZ = (int) Math.floor((centerZ + range) / partitionSize);
        
        for (int px = minPartitionX; px <= maxPartitionX; px++) {
            for (int pz = minPartitionZ; pz <= maxPartitionZ; pz++) {
                long partitionKey = getPartitionKey(px * partitionSize, pz * partitionSize);
                EntityPartition partition = partitions.get(partitionKey);
                
                if (partition != null) {
                    result.addAll(partition.getEntitiesInRange(centerX, centerZ, range));
                }
            }
        }
        
        return result;
    }

    public List<EntityInfo> getFilteredEntitiesForNoTick(int chunkX, int chunkZ) {
        List<EntityInfo> result = new ArrayList<>();
        
        if (!noTickMode || !enabled) {
            return result;
        }
        
        double centerX = (chunkX + 0.5) * 16;
        double centerZ = (chunkZ + 0.5) * 16;
        double range = 16;
        
        List<EntityInfo> entitiesInRange = getEntitiesInRange(centerX, centerZ, range);
        
        for (EntityInfo entity : entitiesInRange) {
            if (entity.isNoTickRelevant) {
                result.add(entity);
            }
        }
        
        return result;
    }

    public boolean shouldProcessEntity(int entityId, int chunkX, int chunkZ) {
        if (!enabled) return true;
        
        EntityInfo info = entities.get(entityId);
        if (info == null) return true;
        
        if (!noTickMode) return true;
        
        return !isEntityInNoTickChunk(info.x, info.z, chunkX, chunkZ);
    }

    private boolean isEntityInNoTickChunk(double x, double z, int chunkX, int chunkZ) {
        int entityChunkX = (int) Math.floor(x / 16);
        int entityChunkZ = (int) Math.floor(z / 16);
        
        return entityChunkX == chunkX && entityChunkZ == chunkZ;
    }

    private long getPartitionKey(double x, double z) {
        int px = (int) Math.floor(x / partitionSize);
        int pz = (int) Math.floor(z / partitionSize);
        return ((long) px << 32) | (pz & 0xFFFFFFFFL);
    }

    public void clearAll() {
        partitions.clear();
        entities.clear();
        totalEntityCount.set(0);
        filteredEntityCount.set(0);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAll();
        }
    }

    public void setNoTickMode(boolean noTickMode) {
        this.noTickMode = noTickMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isNoTickMode() {
        return noTickMode;
    }

    public int getTotalEntityCount() {
        return totalEntityCount.get();
    }

    public int getFilteredEntityCount() {
        return filteredEntityCount.get();
    }

    public int getPartitionCount() {
        return partitions.size();
    }

    public void incrementFilteredCount() {
        filteredEntityCount.incrementAndGet();
    }

    public static class EntityInfo {
        public final int entityId;
        public volatile double x, y, z;
        public final boolean isNoTickRelevant;
        public volatile long lastUpdateTime;

        public EntityInfo(int entityId, double x, double y, double z, boolean isNoTickRelevant) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.isNoTickRelevant = isNoTickRelevant;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public void updatePosition(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public double distanceTo(double cx, double cz) {
            double dx = x - cx;
            double dz = z - cz;
            return Math.sqrt(dx * dx + dz * dz);
        }

        // PERF: 平方距离，用于范围比较时避免 Math.sqrt 开销
        public double distanceSquaredTo(double cx, double cz) {
            double dx = x - cx;
            double dz = z - cz;
            return dx * dx + dz * dz;
        }
    }

    private static class EntityPartition {
        final long key;
        final int partitionSize;
        // PERF: CopyOnWriteArrayList 每次 add/remove 复制整个数组，entity 位置每 tick 更新导致写放大；
        // 改用 ConcurrentHashMap<Integer, EntityInfo> 支持高频写操作
        final ConcurrentHashMap<Integer, EntityInfo> entities;

        EntityPartition(long key, int partitionSize) {
            this.key = key;
            this.partitionSize = partitionSize;
            this.entities = new ConcurrentHashMap<>();
        }

        void addEntity(EntityInfo info) {
            entities.put(info.entityId, info);
        }

        void removeEntity(int entityId) {
            entities.remove(entityId);
        }

        List<EntityInfo> getEntitiesInRange(double centerX, double centerZ, double range) {
            List<EntityInfo> result = new ArrayList<>();
            // PERF: 用平方距离比较替代 Math.sqrt，减少每实体一次开方运算
            double rangeSq = range * range;

            for (EntityInfo entity : entities.values()) {
                if (entity.distanceSquaredTo(centerX, centerZ) <= rangeSq) {
                    result.add(entity);
                }
            }

            return result;
        }

        int getEntityCount() {
            return entities.size();
        }
    }
}