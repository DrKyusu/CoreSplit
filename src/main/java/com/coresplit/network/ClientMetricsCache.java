package com.coresplit.network;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端指标缓存。
 * 由网络线程写入，由渲染线程（F3 面板）读取。
 * 使用 volatile 保证可见性。
 */
public class ClientMetricsCache {

    private static volatile MetricsSnapshot latest = MetricsSnapshot.EMPTY;

    public static void update(MetricsSnapshot snapshot) {
        latest = snapshot;
    }

    public static MetricsSnapshot getLatest() {
        return latest;
    }

    /**
     * 不可变指标快照。
     */
    public record MetricsSnapshot(
            float tps,
            float mspt,
            int activeDimensions,
            int entityCount,
            int loadedChunks,
            long explosionTotal,
            float explosionAvgMs,
            int explosionThreads,
            Map<String, Float> dimensionTimings
    ) {
        public static final MetricsSnapshot EMPTY = new MetricsSnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, Collections.emptyMap()
        );

        /**
         * 判断是否已收到服务端数据。
         */
        public boolean hasData() {
            return this != EMPTY && tps > 0;
        }

        /**
         * 从数据包反序列化。
         */
        public static MetricsSnapshot fromPacket(net.minecraft.network.PacketByteBuf buf) {
            try {
                float tps = buf.readFloat();
                float mspt = buf.readFloat();
                int activeDims = buf.readInt();
                int entities = buf.readInt();
                int chunks = buf.readInt();
                long explosionTotal = buf.readLong();
                float explosionAvgMs = buf.readFloat();
                int explosionThreads = buf.readInt();

                int dimCount = buf.readInt();
                Map<String, Float> dimTimings = new LinkedHashMap<>();
                for (int i = 0; i < dimCount; i++) {
                    dimTimings.put(buf.readString(), buf.readFloat());
                }

                return new MetricsSnapshot(
                        tps, mspt, activeDims, entities, chunks,
                        explosionTotal, explosionAvgMs, explosionThreads,
                        dimTimings
                );
            } catch (Exception e) {
                return EMPTY;
            }
        }
    }
}