package com.coresplit.ai;

import com.coresplit.chunk.EntityFilter;
import com.coresplit.chunk.EntityFilter.EntityInfo;

import java.util.List;

/**
 * AI 空间索引，包装复用 {@link EntityFilter} 的空间分区能力。
 *
 * <p>不修改 EntityFilter，保持其单一职责。本类仅提供面向 AI 优化的查询接口：
 * 获取指定距离桶内的实体列表，供 {@link EntityAiThrottle} 和 {@link SharedPathRegistry} 使用。
 */
public class AiSpatialIndex {

    private final EntityFilter entityFilter;

    public AiSpatialIndex(EntityFilter entityFilter) {
        this.entityFilter = entityFilter;
    }

    /**
     * 获取以指定坐标为中心、指定范围内的实体列表。
     *
     * @param centerX 中心 X
     * @param centerZ 中心 Z
     * @param range 查询范围（方块单位）
     * @return 范围内的实体信息列表
     */
    public List<EntityInfo> getEntitiesInRange(double centerX, double centerZ, double range) {
        if (entityFilter == null || !entityFilter.isEnabled()) {
            return List.of();
        }
        return entityFilter.getEntitiesInRange(centerX, centerZ, range);
    }

    /**
     * 获取指定桶范围内的实体数量。
     */
    public int countEntitiesInBucket(double centerX, double centerZ, AiBucket bucket,
                                      int nearRadius, int farRadius) {
        double range;
        switch (bucket) {
            case NEAR -> range = nearRadius;
            case MID -> range = farRadius / 2.0;
            case FAR -> range = farRadius;
            case OFFSCREEN -> range = farRadius * 2.0;
            default -> range = farRadius;
        }
        return getEntitiesInRange(centerX, centerZ, range).size();
    }

    public EntityFilter getEntityFilter() {
        return entityFilter;
    }

    public int getTotalEntityCount() {
        return entityFilter != null ? entityFilter.getTotalEntityCount() : 0;
    }

    public int getPartitionCount() {
        return entityFilter != null ? entityFilter.getPartitionCount() : 0;
    }
}
