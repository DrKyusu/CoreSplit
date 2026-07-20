package com.coresplit.command;

import com.coresplit.metrics.PerformanceMonitor;
import com.coresplit.threading.DimensionThreadManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /coresplit 命令 — 运行时查看性能指标和线程状态。
 */
public class CoreSplitCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                 PerformanceMonitor metrics,
                                 DimensionThreadManager manager) {
        dispatcher.register(literal("coresplit")
                .then(literal("stats").executes(ctx -> showStats(ctx, metrics, manager)))
                .then(literal("threads").executes(ctx -> showThreads(ctx, manager)))
                .then(literal("dimensions").executes(ctx -> showDimensions(ctx, metrics, manager)))
                .then(literal("summary").executes(ctx -> { metrics.dumpSummary(); return 1; }))
        );
    }

    private static int showStats(CommandContext<ServerCommandSource> ctx,
                                  PerformanceMonitor metrics,
                                  DimensionThreadManager manager) {
        double tps = metrics.getCurrentTPS();
        double mspt = metrics.getCurrentMSPT();
        Formatting tpsColor = tps >= 19 ? Formatting.GREEN : (tps >= 15 ? Formatting.YELLOW : Formatting.RED);

        ctx.getSource().sendFeedback(() -> Text.literal(
                String.format("""
                        §6════ CoreSplit Stats ════
                        §fTPS: §%s%.1f§f/20.0
                        §fMSPT: §f%.2f ms
                        §fActive Dimensions: §b%d
                        §fTotal Ticks: §f%d
                        §6══════════════════════════""",
                        tpsColor == Formatting.GREEN ? 'a' : (tpsColor == Formatting.YELLOW ? 'e' : 'c'),
                        tps, mspt,
                        manager.getActiveDimensions(),
                        metrics.getTotalTicks()
                )), false);
        return 1;
    }

    private static int showThreads(CommandContext<ServerCommandSource> ctx,
                                    DimensionThreadManager manager) {
        ctx.getSource().sendFeedback(() -> Text.literal(
                String.format("""
                        §6════ Thread Info ════
                        §fDimension threads: §b%d
                        §fEntity pool: §b%s
                        §fChunk pool: §b%s
                        §6══════════════════════""",
                        manager.getActiveDimensions(),
                        manager.getEntityExecutor() != null ? manager.getEntityExecutor().toString() : "N/A",
                        manager.getChunkExecutor() != null ? manager.getChunkExecutor().toString() : "N/A"
                )), false);
        return 1;
    }

    private static int showDimensions(CommandContext<ServerCommandSource> ctx,
                                       PerformanceMonitor metrics,
                                       DimensionThreadManager manager) {
        StringBuilder sb = new StringBuilder("§6════ Dimension Metrics ════\n");

        for (var entry : metrics.getPerDimensionMetrics().entrySet()) {
            PerformanceMonitor.DimensionMetrics m = entry.getValue();
            sb.append(String.format("§f[§b%s§f] avg=§a%.2f§fms max=§e%.2f§fms ticks=§f%d\n",
                    entry.getKey(), m.getAverageMs(), m.getMaxMs(), m.getTickCount()));
        }
        sb.append("§6══════════════════════════");

        String output = sb.toString();
        ctx.getSource().sendFeedback(() -> Text.literal(output), false);
        return 1;
    }
}