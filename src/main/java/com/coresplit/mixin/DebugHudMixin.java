package com.coresplit.mixin;

import com.coresplit.network.ClientMetricsCache;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * F3 调试屏幕 Mixin — 在左侧信息面板追加 CoreSplit 运行状态。
 *
 * 目标方法：DebugHud.getLeftText() → List<String>
 *
 * 追加内容示例：
 *   [CoreSplit] Engine v1.1.0
 *     TPS: 19.8  MSPT: 48.2ms
 *     Dimensions: 3 active  Entities: 12,450
 *     Chunks: 8,234 loaded
 *     Explosions: 1,203 processed  (4 threads, 3.2ms avg)
 *     [overworld] 15.3ms  [the_nether] 8.1ms  [the_end] 2.4ms
 *
 * 注意：
 *   - 仅客户端加载此 Mixin（在 coresplit.mixins.json 的 "client" 段注册）
 *   - 数据来源为 ClientMetricsCache（由服务端每秒推送一次）
 *   - 单人游戏（集成服务器）和多人游戏均支持
 *   - 若服务端未安装 CoreSplit，显示 "Waiting for server..."
 */
@Mixin(DebugHud.class)
@Environment(EnvType.CLIENT)
public class DebugHudMixin {

    /**
     * 在 F3 左侧面板末尾追加 CoreSplit 信息。
     * 通过修改返回的 List<String> 实现，不影响原有内容。
     */
    @Inject(method = "getLeftText", at = @At("RETURN"))
    private void coresplit$appendMetrics(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();

        ClientMetricsCache.MetricsSnapshot snap = ClientMetricsCache.getLatest();

        // 分隔线
        lines.add("");

        if (!snap.hasData()) {
            // 未收到服务端数据
            lines.add("\u00a76[CoreSplit] \u00a77Waiting for server data...");
            lines.add("  \u00a77Install CoreSplit on server for full metrics.");
            return;
        }

        // ─── 标题行 ───
        lines.add("\u00a76[CoreSplit] \u00a7rv1.1.0");

        // ─── TPS / MSPT ───
        String tpsColor;
        if (snap.tps() >= 19.0f) {
            tpsColor = "\u00a7a"; // 绿色
        } else if (snap.tps() >= 15.0f) {
            tpsColor = "\u00a7e"; // 黄色
        } else {
            tpsColor = "\u00a7c"; // 红色
        }

        String msptColor;
        if (snap.mspt() <= 40.0f) {
            msptColor = "\u00a7a";
        } else if (snap.mspt() <= 50.0f) {
            msptColor = "\u00a7e";
        } else {
            msptColor = "\u00a7c";
        }

        lines.add(String.format("  \u00a77TPS: %s%.1f \u00a77MSPT: %s%.1fms",
                tpsColor, Math.min(snap.tps(), 20.0f),
                msptColor, snap.mspt()));

        // ─── 维度 / 实体 / 区块 ───
        lines.add(String.format("  \u00a77Dims: \u00a7b%d \u00a77active  Entities: \u00a7b%,d \u00a77Chunks: \u00a7b%,d",
                snap.activeDimensions(), snap.entityCount(), snap.loadedChunks()));

        // ─── 爆炸统计 ───
        if (snap.explosionTotal() > 0) {
            lines.add(String.format("  \u00a77Explosions: \u00a7f%,d \u00a77processed (%d threads, %.1fms avg)",
                    snap.explosionTotal(), snap.explosionThreads(), snap.explosionAvgMs()));
        }

        // ─── 每维度耗时 ───
        if (!snap.dimensionTimings().isEmpty()) {
            StringBuilder dimLine = new StringBuilder("  \u00a77");
            for (Map.Entry<String, Float> entry : snap.dimensionTimings().entrySet()) {
                String name = simplify(entry.getKey());
                float ms = entry.getValue();
                String color = ms <= 20 ? "\u00a7f" : (ms <= 40 ? "\u00a7e" : "\u00a7c");
                dimLine.append(String.format("[%s] %s%.1fms \u00a77", name, color, ms));
            }
            lines.add(dimLine.toString().trim());
        }
    }

    /**
     * 简化维度名称，如 "minecraft:overworld" → "overworld"
     */
    private static String simplify(String fullId) {
        int colon = fullId.indexOf(':');
        return colon >= 0 ? fullId.substring(colon + 1) : fullId;
    }
}