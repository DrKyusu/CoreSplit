package com.coresplit.ai;

import java.util.Objects;

/**
 * 路径缓存键。
 *
 * <p>由起点 chunk 坐标、终点 chunk 坐标和实体类型标识组成。
 * 使用 chunk 级别而非精确坐标，提高缓存命中率（同 chunk 内的路径可复用）。
 */
public final class PathKey {

    private final int startChunkX;
    private final int startChunkZ;
    private final int endChunkX;
    private final int endChunkZ;
    private final String entityType;
    private final int hash;

    public PathKey(int startChunkX, int startChunkZ, int endChunkX, int endChunkZ, String entityType) {
        this.startChunkX = startChunkX;
        this.startChunkZ = startChunkZ;
        this.endChunkX = endChunkX;
        this.endChunkZ = endChunkZ;
        this.entityType = entityType != null ? entityType : "";
        this.hash = computeHash();
    }

    private int computeHash() {
        return Objects.hash(startChunkX, startChunkZ, endChunkX, endChunkZ, entityType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathKey other)) return false;
        return startChunkX == other.startChunkX
                && startChunkZ == other.startChunkZ
                && endChunkX == other.endChunkX
                && endChunkZ == other.endChunkZ
                && entityType.equals(other.entityType);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    public int getStartChunkX() { return startChunkX; }
    public int getStartChunkZ() { return startChunkZ; }
    public int getEndChunkX() { return endChunkX; }
    public int getEndChunkZ() { return endChunkZ; }
    public String getEntityType() { return entityType; }

    /**
     * 判断路径是否经过指定 chunk（用于方块变化时失效缓存）。
     */
    public boolean passesThrough(int chunkX, int chunkZ) {
        // 简化判定：起点、终点或连线 bbox 包含该 chunk
        int minX = Math.min(startChunkX, endChunkX);
        int maxX = Math.max(startChunkX, endChunkX);
        int minZ = Math.min(startChunkZ, endChunkZ);
        int maxZ = Math.max(startChunkZ, endChunkZ);
        return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
    }

    @Override
    public String toString() {
        return "PathKey{" + entityType + ":(" + startChunkX + "," + startChunkZ
                + ")->(" + endChunkX + "," + endChunkZ + ")}";
    }
}
