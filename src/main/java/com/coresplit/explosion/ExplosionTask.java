package com.coresplit.explosion;

/**
 * 单次爆炸任务，实现 Comparable 供优先级调度。
 *
 * <p>优先级策略：玩家距离越近优先级越高（数值越小越优先），确保玩家附近的爆炸先处理，
 * 提升主观体验。同距离按提交顺序处理。
 */
public class ExplosionTask implements Comparable<ExplosionTask> {

    private static long globalSeq = 0;

    private final double x;
    private final double y;
    private final double z;
    private final float radius;
    private final double distanceToNearestPlayer;
    private final long submitTimeMs;
    private final long seq;

    public ExplosionTask(double x, double y, double z, float radius, double distanceToNearestPlayer) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.distanceToNearestPlayer = Math.max(0, distanceToNearestPlayer);
        this.submitTimeMs = System.currentTimeMillis();
        this.seq = nextSeq();
    }

    private static synchronized long nextSeq() {
        return globalSeq++;
    }

    /**
     * 距离越近优先级越高（排前）。同距离按提交顺序（seq 小的先）。
     */
    @Override
    public int compareTo(ExplosionTask o) {
        int cmp = Double.compare(this.distanceToNearestPlayer, o.distanceToNearestPlayer);
        return cmp != 0 ? cmp : Long.compare(this.seq, o.seq);
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getRadius() { return radius; }
    public double getDistanceToNearestPlayer() { return distanceToNearestPlayer; }
    public long getSubmitTimeMs() { return submitTimeMs; }
    public long getSeq() { return seq; }

    /**
     * 计算两个爆炸是否在空间上重叠（可用于合并处理）。
     */
    public boolean overlaps(ExplosionTask other, double mergeFactor) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        double combinedRadius = (this.radius + other.radius) * mergeFactor;
        return distSq < combinedRadius * combinedRadius;
    }
}
