package com.coresplit.network;

import com.coresplit.CoreSplitMod;
import com.coresplit.metrics.PerformanceMonitor;
import com.coresplit.threading.DimensionThreadManager;
import com.coresplit.threading.ExplosionWorkerPool;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * 服务端→客户端网络同步。
 *
 * 每秒（20 Tick）向所有在线玩家发送一次模组运行状态。
 * 客户端接收后缓存在 ClientMetricsCache 中，供 F3 面板读取。
 *
 * 数据包格式（紧凑二进制）：
 *   ┌─────────────────┬──────────┐
 *   │ 字段             │ 类型     │
 *   ├─────────────────┼──────────┤
 *   │ tps             │ float    │
 *   │ mspt            │ float    │
 *   │ activeDims      │ int      │
 *   │ entityCount     │ int      │
 *   │ loadedChunks    │ int      │
 *   │ explosionTotal  │ long     │
 *   │ explosionAvgMs  │ float    │
 *   │ explosionThreads│ int      │
 *   │ dimCount        │ int      │
 *   │ dimName[i]      │ String   │
 *   │ dimAvgMs[i]     │ float    │
 *   └─────────────────┴──────────┘
 */
public class NetworkHandler {

    public static final Identifier METRICS_CHANNEL =
            new Identifier(CoreSplitMod.MOD_ID, "metrics_sync");

    private static int tickCounter = 0;

    /**
     * 每 Tick 调用一次。每 20 Tick（1 秒）发送一次数据包。
     * 在 DimensionThreadManager.tickAllDimensions() 结尾调用。
     */
    public static void onServerTick(MinecraftServer server,
                                     PerformanceMonitor metrics,
                                     DimensionThreadManager manager) {
        tickCounter++;
        if (tickCounter < 20) return;
        tickCounter = 0;

        if (server.getPlayerManager() == null) return;

        PacketByteBuf buf = buildMetricsPacket(metrics, manager);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                ServerPlayNetworking.send(player, METRICS_CHANNEL, buf);
            } catch (Exception e) {
                // 玩家可能不支持此通道（未安装客户端模组），静默忽略
            }
        }
    }

    /**
     * 序列化指标到数据包。
     */
    private static PacketByteBuf buildMetricsPacket(PerformanceMonitor metrics,
                                                     DimensionThreadManager manager) {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeFloat((float) metrics.getCurrentTPS());
        buf.writeFloat((float) metrics.getCurrentMSPT());
        buf.writeInt(manager.getActiveDimensions());
        buf.writeInt(countTotalEntities(manager));
        buf.writeInt(countLoadedChunks(manager));

        // 爆炸统计
        ExplosionWorkerPool explosionPool = ExplosionWorkerPool.getInstance();
        if (explosionPool != null) {
            buf.writeLong(explosionPool.getTotalExplosions());
            buf.writeFloat((float) explosionPool.getAvgParallelMs());
            buf.writeInt(explosionPool.getThreadCount());
        } else {
            buf.writeLong(0);
            buf.writeFloat(0);
            buf.writeInt(0);
        }

        // 每维度耗时
        Map<String, PerformanceMonitor.DimensionMetrics> dimMetrics = metrics.getPerDimensionMetrics();
        buf.writeInt(dimMetrics.size());
        for (Map.Entry<String, PerformanceMonitor.DimensionMetrics> entry : dimMetrics.entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeFloat((float) entry.getValue().getAverageMs());
        }

        return buf;
    }

    private static int countTotalEntities(DimensionThreadManager manager) {
        return manager.getWorkers().keySet().stream()
                .mapToInt(world -> world.getEntities().size())
                .sum();
    }

    private static int countLoadedChunks(DimensionThreadManager manager) {
        return manager.getWorkers().keySet().stream()
                .mapToInt(world -> world.getChunkManager().getLoadedChunkCount())
                .sum();
    }
}