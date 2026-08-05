package com.coresplit.ai;

/**
 * AI 更新距离桶枚举。
 *
 * <p>根据实体到最近玩家的距离将实体分入不同桶，每个桶有不同的 AI 更新倍率：
 * <ul>
 *   <li>{@link #NEAR} - 距离 ≤ nearRadius（默认16），每 tick 都更新（倍率 1.0）</li>
 *   <li>{@link #MID} - 距离 ≤ farRadius/2，每 2 tick 更新一次（倍率 0.5）</li>
 *   <li>{@link #FAR} - 距离 ≤ farRadius，每 4 tick 更新一次（倍率 0.25）</li>
 *   <li>{@link #OFFSCREEN} - 距离 > farRadius，AI 暂停（倍率 0.0）</li>
 * </ul>
 */
public enum AiBucket {
    NEAR(1.0f, 1),
    MID(0.5f, 2),
    FAR(0.25f, 4),
    OFFSCREEN(0.0f, Integer.MAX_VALUE);

    public final float updateMultiplier;
    public final int tickInterval;

    AiBucket(float updateMultiplier, int tickInterval) {
        this.updateMultiplier = updateMultiplier;
        this.tickInterval = tickInterval;
    }

    /**
     * 根据距离平方值和桶边界判定所属桶。
     *
     * @param distanceSq 距离平方（避免 sqrt）
     * @param nearRadiusSq 近距离桶边界平方
     * @param midRadiusSq 中距离桶边界平方
     * @param farRadiusSq 远距离桶边界平方
     * @return 对应的桶
     */
    public static AiBucket fromDistanceSq(double distanceSq, double nearRadiusSq,
                                           double midRadiusSq, double farRadiusSq) {
        if (distanceSq <= nearRadiusSq) return NEAR;
        if (distanceSq <= midRadiusSq) return MID;
        if (distanceSq <= farRadiusSq) return FAR;
        return OFFSCREEN;
    }
}
