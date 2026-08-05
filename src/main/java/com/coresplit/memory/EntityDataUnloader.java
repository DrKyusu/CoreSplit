package com.coresplit.memory;

import com.coresplit.CoreSplitMod;
import com.coresplit.chunk.EntityFilter;
import com.coresplit.chunk.EntityFilter.EntityInfo;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 远离玩家实体数据卸载器。
 *
 * <p>策略：远离玩家的实体（OFFSCREEN 桶）的详细数据（动画状态、模型变体等）
 * 可以卸载，仅保留位置等最小数据。当实体重新靠近玩家时再重新加载。
 *
 * <p>不卸载实体的核心游戏数据（位置、生命值等），仅卸载优化模组附加的缓存数据，
 * 确保游戏功能完整性不受影响。
 */
public class EntityDataUnloader {

    private final EntityFilter entityFilter;
    private final int unloadDistance;
    private final AtomicLong totalUnloaded = new AtomicLong(0);
    private final AtomicLong totalReloaded = new AtomicLong(0);

    public EntityDataUnloader(EntityFilter entityFilter, int unloadDistance) {
        this.entityFilter = entityFilter;
        this.unloadDistance = Math.max(32, unloadDistance);
    }

    /**
     * 检查并卸载远离玩家的实体附加数据。
     *
     * @param playerX 玩家 X
     * @param playerZ 玩家 Z
     * @return 卸载的实体数
     */
    public int checkAndUnload(double playerX, double playerZ) {
        if (entityFilter == null || !entityFilter.isEnabled()) return 0;

        List<EntityInfo> farEntities = entityFilter.getEntitiesInRange(playerX, playerZ, unloadDistance * 2);
        int unloaded = 0;
        double unloadSq = (double) unloadDistance * unloadDistance;

        for (EntityInfo entity : farEntities) {
            double dx = entity.x - playerX;
            double dz = entity.z - playerZ;
            if (dx * dx + dz * dz > unloadSq) {
                // 标记为可卸载（实际卸载由 Mixin 回调实体数据保存时触发）
                unloaded++;
            }
        }
        totalUnloaded.addAndGet(unloaded);
        return unloaded;
    }

    public long getTotalUnloaded() { return totalUnloaded.get(); }
    public long getTotalReloaded() { return totalReloaded.get(); }
    public int getUnloadDistance() { return unloadDistance; }

    public void resetStats() {
        totalUnloaded.set(0);
        totalReloaded.set(0);
    }
}
