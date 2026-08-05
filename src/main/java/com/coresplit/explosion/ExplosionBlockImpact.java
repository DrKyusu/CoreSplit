package com.coresplit.explosion;

/**
 * 方块碰撞影响裁剪器。
 *
 * <p>优化策略：爆炸半径内并非所有方块都需要完整碰撞检测。
 * 远离爆炸中心的方块（超过 {@code farBlockDistance}）且硬度较高的方块，
 * 其破坏效果对玩家体验影响极小，可跳过详细检测以减少计算量。
 */
public class ExplosionBlockImpact {

    private volatile boolean skipFarBlockImpact;
    private volatile int farBlockDistance;

    public ExplosionBlockImpact(boolean skipFarBlockImpact, int farBlockDistance) {
        this.skipFarBlockImpact = skipFarBlockImpact;
        this.farBlockDistance = Math.max(1, farBlockDistance);
    }

    /**
     * 判断指定位置的方块是否需要完整的碰撞检测。
     *
     * @param blockX 方块 X 坐标
     * @param blockY 方块 Y 坐标
     * @param blockZ 方块 Z 坐标
     * @param explosionX 爆炸中心 X
     * @param explosionY 爆炸中心 Y
     * @param explosionZ 爆炸中心 Z
     * @return true 表示需要检测，false 表示可跳过
     */
    public boolean shouldProcessBlock(double blockX, double blockY, double blockZ,
                                       double explosionX, double explosionY, double explosionZ) {
        if (!skipFarBlockImpact) {
            return true;
        }
        // 平方距离比较，避免 Math.sqrt 开销
        double dx = blockX - explosionX;
        double dy = blockY - explosionY;
        double dz = blockZ - explosionZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        double threshold = farBlockDistance;
        return distSq <= threshold * threshold;
    }

    /**
     * 估算爆炸影响范围内的方块检测数量（用于监控）。
     *
     * @param radius 爆炸半径
     * @return 需要检测的方块估算数
     */
    public int estimateBlockChecks(float radius) {
        if (!skipFarBlockImpact) {
            // 完整检测：球形体积内的方块数
            int r = (int) Math.ceil(radius);
            return (int) (4.0 / 3.0 * Math.PI * r * r * r);
        }
        // 裁剪后：仅检测 farBlockDistance 范围内的方块
        int r = Math.min((int) Math.ceil(radius), farBlockDistance);
        return (int) (4.0 / 3.0 * Math.PI * r * r * r);
    }

    public boolean isSkipFarBlockImpact() { return skipFarBlockImpact; }
    public int getFarBlockDistance() { return farBlockDistance; }

    public void setSkipFarBlockImpact(boolean v) { this.skipFarBlockImpact = v; }
    public void setFarBlockDistance(int v) { this.farBlockDistance = Math.max(1, v); }
}
