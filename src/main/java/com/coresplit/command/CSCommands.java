package com.coresplit.command;

import com.coresplit.CoreSplitClientMod;
import com.coresplit.CoreSplitMod;
import com.coresplit.config.CoreSplitYaclConfig;
import com.coresplit.governor.PerformanceGovernor;
import com.coresplit.monitoring.PerformanceMonitor;
import com.coresplit.renderlimiter.EntityRenderLimiter;
import com.coresplit.scheduler.HardwareDetector;
import com.coresplit.scheduler.TaskScheduler;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Locale;

/**
 * CoreSplit command system — registers all client-side commands.
 *
 * <p>All commands are registered as client commands via Fabric API's
 * {@link ClientCommandRegistrationCallback}. This ensures they work in both
 * single-player and multiplayer (the server does not need CoreSplit installed).
 *
 * <h3>Command list</h3>
 * <ul>
 *   <li>{@code /cshelp} — list all commands with descriptions and usage</li>
 *   <li>{@code /csset} (alias {@code /csSet}) — open the YACL settings screen</li>
 *   <li>{@code /csfps} — display current FPS, average, min, max, and frame time</li>
 *   <li>{@code /csstats} — comprehensive performance statistics</li>
 *   <li>{@code /csreload} — reload configuration from disk and apply to all modules</li>
 *   <li>{@code /cstoggle <module>} — toggle a module on or off</li>
 *   <li>{@code /csinfo} — mod version, hardware info, and module status</li>
 * </ul>
 *
 * <h3>Performance</h3>
 * <p>All command handlers complete in O(1) time (cached lookups only),
 * satisfying the ≤300 ms response time requirement.
 *
 * <h3>Error handling</h3>
 * <p>Every handler is wrapped in try-catch. Invalid arguments produce
 * translatable error messages with suggestions for correction.
 */
public final class CSCommands {

    /** Accent colour for command headers (gold). */
    private static final int COLOR_HEADER = 0xFFAA00;
    /** Accent colour for command names (aqua). */
    private static final int COLOR_CMD = 0x55FFFF;
    /** Accent colour for values (green). */
    private static final int COLOR_VALUE = 0x55FF55;
    /** Accent colour for warnings (red). */
    private static final int COLOR_WARN = 0xFF5555;
    /** Accent colour for descriptions (gray). */
    private static final int COLOR_DESC = 0xAAAAAA;

    private CSCommands() {}

    /**
     * Register all CoreSplit client commands.
     * Called from {@link CoreSplitClientMod#onInitializeClient()}.
     */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 1. /cshelp — info query
            dispatcher.register(ClientCommands.literal("cshelp")
                    .executes(CSCommands::runHelp));

            // 2. /csset (alias /csSet) — open settings UI
            dispatcher.register(ClientCommands.literal("csset")
                    .executes(CSCommands::runOpenSettings));
            dispatcher.register(ClientCommands.literal("csSet")
                    .executes(CSCommands::runOpenSettings));

            // 3. /csfps — FPS query
            dispatcher.register(ClientCommands.literal("csfps")
                    .executes(CSCommands::runFps));

            // 4. /csstats — comprehensive stats
            dispatcher.register(ClientCommands.literal("csstats")
                    .executes(CSCommands::runStats));

            // 5. /csreload — reload config
            dispatcher.register(ClientCommands.literal("csreload")
                    .executes(CSCommands::runReload));

            // 6. /cstoggle <module> — toggle module
            dispatcher.register(ClientCommands.literal("cstoggle")
                    .then(ClientCommands.argument("module",
                            StringArgumentType.string())
                            .executes(CSCommands::runToggle)));

            // Also provide /cstoggle with no args to list toggleable modules
            dispatcher.register(ClientCommands.literal("cstoggle")
                    .executes(CSCommands::runToggleList));

            // 7. /csinfo — mod info
            dispatcher.register(ClientCommands.literal("csinfo")
                    .executes(CSCommands::runInfo));
        });

        CoreSplitMod.LOGGER.info("[CoreSplit] Client commands registered: cshelp, csset, csfps, csstats, csreload, cstoggle, csinfo");
    }

    // =========================================================================
    // /cshelp — Display all commands
    // =========================================================================
    private static int runHelp(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            src.sendFeedback(header("coresplit.command.help.title"));
            src.sendFeedback(Component.literal(""));

            sendCommandEntry(src, "/cshelp",
                    "coresplit.command.help.desc_cshelp",
                    "/cshelp",
                    "coresplit.command.help.note_cshelp");
            sendCommandEntry(src, "/csset  (/csSet)",
                    "coresplit.command.help.desc_csset",
                    "/csset  or  /csSet",
                    "coresplit.command.help.note_csset");
            sendCommandEntry(src, "/csfps",
                    "coresplit.command.help.desc_csfps",
                    "/csfps",
                    "coresplit.command.help.note_csfps");
            sendCommandEntry(src, "/csstats",
                    "coresplit.command.help.desc_csstats",
                    "/csstats",
                    "coresplit.command.help.note_csstats");
            sendCommandEntry(src, "/csreload",
                    "coresplit.command.help.desc_csreload",
                    "/csreload",
                    "coresplit.command.help.note_csreload");
            sendCommandEntry(src, "/cstoggle <module>",
                    "coresplit.command.help.desc_cstoggle",
                    "/cstoggle governor  |  /cstoggle scheduler  |  /cstoggle ai",
                    "coresplit.command.help.note_cstoggle");
            sendCommandEntry(src, "/csinfo",
                    "coresplit.command.help.desc_csinfo",
                    "/csinfo",
                    "coresplit.command.help.note_csinfo");

            src.sendFeedback(Component.literal(""));
            src.sendFeedback(Component.translatable("coresplit.command.help.footer")
                    .setStyle(Style.EMPTY.withColor(COLOR_DESC)));
        } catch (Exception e) {
            sendError(src, "coresplit.command.error.execution", e.getMessage());
        }
        return 1;
    }

    private static void sendCommandEntry(FabricClientCommandSource src, String name,
                                           String descKey, String usage, String noteKey) {
        src.sendFeedback(Component.literal(name)
                .setStyle(Style.EMPTY.withColor(COLOR_CMD))
                .append(Component.literal(" — ")
                        .setStyle(Style.EMPTY.withColor(COLOR_DESC)))
                .append(Component.translatable(descKey)
                        .setStyle(Style.EMPTY.withColor(COLOR_DESC))));
        src.sendFeedback(Component.literal("    ")
                .append(Component.translatable("coresplit.command.help.usage_label")
                        .setStyle(Style.EMPTY.withColor(COLOR_HEADER)))
                .append(Component.literal(usage)
                        .setStyle(Style.EMPTY.withColor(COLOR_VALUE))));
        src.sendFeedback(Component.literal("    ")
                .append(Component.translatable("coresplit.command.help.note_label")
                        .setStyle(Style.EMPTY.withColor(COLOR_HEADER)))
                .append(Component.translatable(noteKey)
                        .setStyle(Style.EMPTY.withColor(COLOR_DESC))));
        src.sendFeedback(Component.literal(""));
    }

    // =========================================================================
    // /csset — Open settings screen
    // =========================================================================
    private static int runOpenSettings(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                sendError(src, "coresplit.command.error.client_only");
                return 0;
            }
            // Client commands run on the main client thread, so we can open the screen
            // directly. MC 26.2 renamed setScreen -> setScreenAndShow. No public getter
            // exists for the current screen, so we pass null as the parent (the YACL
            // screen will return to the previous screen on close via its own logic).
            mc.setScreenAndShow(CoreSplitYaclConfig.makeScreen(null));
            src.sendFeedback(Component.translatable("coresplit.command.csset.opening")
                    .setStyle(Style.EMPTY.withColor(COLOR_VALUE)));
        } catch (Exception e) {
            sendError(src, "coresplit.command.error.execution", e.getMessage());
        }
        return 1;
    }

    // =========================================================================
    // /csfps — FPS query
    // =========================================================================
    private static int runFps(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                sendError(src, "coresplit.command.error.client_only");
                return 0;
            }

            int currentFps = mc.getFps();
            PerformanceMonitor monitor = CoreSplitMod.getClientPerformanceMonitor();

            // Build a multi-line FPS report
            src.sendFeedback(header("coresplit.command.csfps.title"));
            src.sendFeedback(statLine("coresplit.command.csfps.current", String.valueOf(currentFps)));

            if (monitor != null) {
                PerformanceMonitor.Metric fpsMetric = monitor.getMetric(PerformanceMonitor.MetricType.FPS);
                if (fpsMetric != null) {
                    src.sendFeedback(statLine("coresplit.command.csfps.average",
                            String.format(Locale.ROOT, "%.1f", fpsMetric.avgValue)));
                    src.sendFeedback(statLine("coresplit.command.csfps.min",
                            String.format(Locale.ROOT, "%.1f", fpsMetric.minValue)));
                    src.sendFeedback(statLine("coresplit.command.csfps.max",
                            String.format(Locale.ROOT, "%.1f", fpsMetric.maxValue)));
                }

                PerformanceMonitor.Metric msptMetric = monitor.getMetric(PerformanceMonitor.MetricType.MSPT);
                if (msptMetric != null) {
                    src.sendFeedback(statLine("coresplit.command.csfps.frametime",
                            String.format(Locale.ROOT, "%.2f ms", msptMetric.value)));
                }
            }

            // Show target FPS from governor
            int targetFps = CoreSplitYaclConfig.getTargetFps();
            String targetStr = targetFps <= 0 ? "Unlimited" : String.valueOf(targetFps);
            src.sendFeedback(statLine("coresplit.command.csfps.target", targetStr));

            // Quality level from governor
            PerformanceGovernor governor = CoreSplitClientMod.getGovernor();
            if (governor != null && governor.isInitialized()) {
                src.sendFeedback(statLine("coresplit.command.csfps.quality_level",
                        String.valueOf(governor.getQualityLevel())));
            }
        } catch (Exception e) {
            sendError(src, "coresplit.command.error.execution", e.getMessage());
        }
        return 1;
    }

    // =========================================================================
    // /csstats — Comprehensive performance statistics
    // =========================================================================
    private static int runStats(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                sendError(src, "coresplit.command.error.client_only");
                return 0;
            }

            src.sendFeedback(header("coresplit.command.csstats.title"));

            // --- Performance ---
            src.sendFeedback(subHeader("coresplit.command.csstats.section_performance"));
            int fps = mc.getFps();
            src.sendFeedback(statLine("coresplit.command.csstats.fps", String.valueOf(fps)));

            PerformanceMonitor monitor = CoreSplitMod.getClientPerformanceMonitor();
            if (monitor != null) {
                PerformanceMonitor.Metric msptMetric = monitor.getMetric(PerformanceMonitor.MetricType.MSPT);
                if (msptMetric != null) {
                    src.sendFeedback(statLine("coresplit.command.csstats.mspt",
                            String.format(Locale.ROOT, "%.2f ms", msptMetric.value)));
                }
                src.sendFeedback(statLine("coresplit.command.csstats.frame_count",
                        String.valueOf(monitor.getFrameCount())));
            }

            // --- Memory ---
            src.sendFeedback(subHeader("coresplit.command.csstats.section_memory"));
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            src.sendFeedback(statLine("coresplit.command.csstats.heap_used",
                    usedMb + " MB"));
            src.sendFeedback(statLine("coresplit.command.csstats.heap_max",
                    maxMb + " MB"));
            src.sendFeedback(statLine("coresplit.command.csstats.heap_usage",
                    String.format(Locale.ROOT, "%.1f%%", (usedMb * 100.0 / maxMb))));

            MemoryOptimizerInfo memInfo = getMemoryOptimizerInfo();
            if (memInfo != null) {
                src.sendFeedback(statLine("coresplit.command.csstats.memory_saved",
                        memInfo.savedMb + " MB"));
            }

            // --- Scheduler ---
            src.sendFeedback(subHeader("coresplit.command.csstats.section_scheduler"));
            TaskScheduler scheduler = CoreSplitClientMod.getScheduler();
            if (scheduler != null) {
                src.sendFeedback(statLine("coresplit.command.csstats.scheduler_enabled",
                        String.valueOf(scheduler.isEnabled())));
                src.sendFeedback(statLine("coresplit.command.csstats.scheduler_threads",
                        String.valueOf(scheduler.getConfiguredThreads())));
                src.sendFeedback(statLine("coresplit.command.csstats.scheduler_cores",
                        String.valueOf(scheduler.getConfiguredCores())));
            } else {
                src.sendFeedback(Component.translatable("coresplit.command.csstats.scheduler_unavailable")
                        .setStyle(Style.EMPTY.withColor(COLOR_DESC)));
            }

            // --- Hardware ---
            src.sendFeedback(subHeader("coresplit.command.csstats.section_hardware"));
            src.sendFeedback(statLine("coresplit.command.csstats.logical_cores",
                    String.valueOf(HardwareDetector.getLogicalCores())));
            src.sendFeedback(statLine("coresplit.command.csstats.physical_cores",
                    String.valueOf(HardwareDetector.getPhysicalCoresEstimate())));
            if (HardwareDetector.isHybridSuspected()) {
                src.sendFeedback(statLine("coresplit.command.csstats.architecture",
                        "Hybrid (P/E-core)"));
            }

            // --- Entity Render ---
            src.sendFeedback(subHeader("coresplit.command.csstats.section_render"));
            EntityRenderLimiter limiter = EntityRenderLimiter.getInstance();
            src.sendFeedback(statLine("coresplit.command.csstats.entity_limiter_enabled",
                    String.valueOf(limiter.isEnabled())));
            src.sendFeedback(statLine("coresplit.command.csstats.max_entities",
                    String.valueOf(limiter.getMaxLivingEntities())));
            src.sendFeedback(statLine("coresplit.command.csstats.max_items",
                    String.valueOf(limiter.getMaxDroppedItems())));
        } catch (Exception e) {
            sendError(src, "coresplit.command.error.execution", e.getMessage());
        }
        return 1;
    }

    // =========================================================================
    // /csreload — Reload configuration
    // =========================================================================
    private static int runReload(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            long startNs = System.nanoTime();
            CoreSplitYaclConfig.load();
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            // Apply reloaded config to all modules
            applyConfigToModules();

            com.coresplit.logging.CSLogger.info("Command",
                    "Config reloaded in {} ms",
                    "配置已在 {} 毫秒内重新加载", elapsedMs);

            src.sendFeedback(Component.translatable("coresplit.command.csreload.success",
                            String.valueOf(elapsedMs))
                    .setStyle(Style.EMPTY.withColor(COLOR_VALUE)));
        } catch (Exception e) {
            sendError(src, "coresplit.command.csreload.failed", e.getMessage());
            com.coresplit.logging.CSLogger.error("Command",
                    "Config reload failed: {}",
                    "重新加载配置失败：{}", e.getMessage());
        }
        return 1;
    }

    private static void applyConfigToModules() {
        try {
            // Governor
            PerformanceGovernor governor = CoreSplitClientMod.getGovernor();
            if (governor != null) {
                governor.setEnabled(CoreSplitYaclConfig.isPerformanceGovernorEnabled());
                governor.refreshDisplaySettings();
            }

            // Scheduler
            TaskScheduler scheduler = CoreSplitClientMod.getScheduler();
            if (scheduler != null) {
                scheduler.applyConfigurationWithMultiplier(
                        CoreSplitYaclConfig.getSchedulerCores(),
                        CoreSplitYaclConfig.getThreadMultiplier());
                scheduler.setEnabled(CoreSplitYaclConfig.isTaskSchedulerEnabled());
            }

            // Entity render limiter
            EntityRenderLimiter limiter = EntityRenderLimiter.getInstance();
            limiter.setEnabled(CoreSplitYaclConfig.isEntityRenderLimiterEnabled());
            limiter.setMaxDroppedItems(CoreSplitYaclConfig.getMaxDroppedItemsRendered());
            limiter.setMaxLivingEntities(CoreSplitYaclConfig.getMaxLivingEntitiesRendered());
            limiter.setItemRenderDistance(CoreSplitYaclConfig.getItemRenderDistance());
            limiter.setEntityRenderDistance(CoreSplitYaclConfig.getEntityRenderDistance());

            // Server-side modules — toggle directly on optimizer instances.
            // These read their initial state from CoreSplitConfig at startup; runtime
            // toggling is done via setEnabled() on the optimizer singleton.
            com.coresplit.explosion.ExplosionOptimizer explosion = CoreSplitMod.getExplosionOptimizer();
            if (explosion != null) {
                com.coresplit.config.CoreSplitConfig cfg = CoreSplitMod.getConfig();
                if (cfg != null) explosion.setEnabled(cfg.isExplosionEnabled());
            }
            com.coresplit.ai.AiOptimizer ai = CoreSplitMod.getAiOptimizer();
            if (ai != null) {
                com.coresplit.config.CoreSplitConfig cfg = CoreSplitMod.getConfig();
                if (cfg != null) ai.setEnabled(cfg.isAiOptimizationEnabled());
            }
            com.coresplit.memory.MemoryOptimizer memory = CoreSplitMod.getMemoryOptimizer();
            if (memory != null) {
                memory.setEnabled(CoreSplitYaclConfig.isMemoryOptimizationEnabled());
            }

            // Logger
            com.coresplit.logging.CSLogger logger = com.coresplit.logging.CSLogger.getInstance();
            logger.setEnabled(CoreSplitYaclConfig.isCsLoggerEnabled());
            logger.setMinimumLevel(CoreSplitYaclConfig.getCsLogLevel());
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to apply reloaded config to modules", e);
        }
    }

    // =========================================================================
    // /cstoggle <module> — Toggle module on/off
    // =========================================================================
    private static int runToggleList(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            src.sendFeedback(header("coresplit.command.cstoggle.title"));
            src.sendFeedback(Component.translatable("coresplit.command.cstoggle.available")
                    .setStyle(Style.EMPTY.withColor(COLOR_DESC)));
            src.sendFeedback(Component.literal(""));
            for (String module : ToggleableModule.NAMES) {
                ToggleableModule tm = ToggleableModule.fromName(module);
                if (tm != null) {
                    boolean on = tm.isEnabled();
                    String status = on ? "§a[ON]" : "§c[OFF]";
                    src.sendFeedback(Component.literal("  " + status + " §b" + module)
                            .append(Component.literal(" — ")
                                    .setStyle(Style.EMPTY.withColor(COLOR_DESC)))
                            .append(Component.translatable(tm.getDescriptionKey())
                                    .setStyle(Style.EMPTY.withColor(COLOR_DESC))));
                }
            }
            src.sendFeedback(Component.literal(""));
            src.sendFeedback(Component.translatable("coresplit.command.cstoggle.usage_hint")
                    .setStyle(Style.EMPTY.withColor(COLOR_DESC)));
        } catch (Exception e) {
            sendError(src, "coresplit.command.error.execution", e.getMessage());
        }
        return 1;
    }

    private static int runToggle(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            String moduleName = StringArgumentType.getString(ctx, "module");
            ToggleableModule module = ToggleableModule.fromName(moduleName);
            if (module == null) {
                sendError(src, "coresplit.command.cstoggle.unknown_module", moduleName);
                // List available modules
                StringBuilder sb = new StringBuilder();
                for (String name : ToggleableModule.NAMES) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(name);
                }
                src.sendFeedback(Component.literal("  " + sb)
                        .setStyle(Style.EMPTY.withColor(COLOR_DESC)));
                return 0;
            }

            boolean newState = !module.isEnabled();
            module.setEnabled(newState);

            com.coresplit.logging.CSLogger.info("Command",
                    "Module '{}' toggled to {}",
                    "模块 '{}' 已切换为 {}",
                    module.getDisplayName(), newState ? "ON" : "OFF");

            src.sendFeedback(Component.translatable("coresplit.command.cstoggle.toggled",
                            module.getDisplayName(), newState ? "ON" : "OFF")
                    .setStyle(Style.EMPTY.withColor(newState ? COLOR_VALUE : COLOR_WARN)));
        } catch (Exception e) {
            sendError(src, "coresplit.command.error.execution", e.getMessage());
            com.coresplit.logging.CSLogger.error("Command",
                    "Toggle command failed: {}",
                    "切换指令失败：{}", e.getMessage());
        }
        return 1;
    }

    // =========================================================================
    // /csinfo — Mod information
    // =========================================================================
    private static int runInfo(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        try {
            src.sendFeedback(header("coresplit.command.csinfo.title"));

            src.sendFeedback(statLine("coresplit.command.csinfo.version", "2.5.0"));
            src.sendFeedback(statLine("coresplit.command.csinfo.mod_id", CoreSplitMod.MOD_ID));

            // Hardware
            src.sendFeedback(subHeader("coresplit.command.csinfo.section_hardware"));
            src.sendFeedback(statLine("coresplit.command.csinfo.logical_cores",
                    String.valueOf(HardwareDetector.getLogicalCores())));
            src.sendFeedback(statLine("coresplit.command.csinfo.physical_cores",
                    String.valueOf(HardwareDetector.getPhysicalCoresEstimate())));
            src.sendFeedback(statLine("coresplit.command.csinfo.recommended_threads",
                    String.valueOf(HardwareDetector.recommendCpuBoundThreads())));
            if (HardwareDetector.isHybridSuspected()) {
                src.sendFeedback(statLine("coresplit.command.csinfo.hybrid_arch",
                        "Yes (P/E-core detected)"));
            }

            // Module status
            src.sendFeedback(subHeader("coresplit.command.csinfo.section_modules"));
            printModuleStatus(src, "Governor",
                    CoreSplitYaclConfig.isPerformanceGovernorEnabled());
            printModuleStatus(src, "Scheduler",
                    CoreSplitYaclConfig.isTaskSchedulerEnabled());
            printModuleStatus(src, "Entity Render Limiter",
                    CoreSplitYaclConfig.isEntityRenderLimiterEnabled());
            printModuleStatus(src, "Particle Filter",
                    CoreSplitYaclConfig.isParticleFilterEnabled());
            // Explosion/AI are server-side config (CoreSplitConfig)
            com.coresplit.config.CoreSplitConfig serverCfg = CoreSplitMod.getConfig();
            printModuleStatus(src, "Explosion Optimizer",
                    serverCfg != null && serverCfg.isExplosionEnabled());
            printModuleStatus(src, "AI Optimizer",
                    serverCfg != null && serverCfg.isAiOptimizationEnabled());
            printModuleStatus(src, "Memory Optimizer",
                    CoreSplitYaclConfig.isMemoryOptimizationEnabled());
            printModuleStatus(src, "Shader Optimization",
                    CoreSplitYaclConfig.isShaderPerformanceOptimizationEnabled());
            printModuleStatus(src, "Deep Compatibility",
                    CoreSplitYaclConfig.isDeepCompatEnabled());

            // Scheduler
            src.sendFeedback(subHeader("coresplit.command.csinfo.section_scheduler"));
            TaskScheduler scheduler = CoreSplitClientMod.getScheduler();
            if (scheduler != null) {
                src.sendFeedback(statLine("coresplit.command.csinfo.scheduler_cores",
                        String.valueOf(scheduler.getConfiguredCores())));
                src.sendFeedback(statLine("coresplit.command.csinfo.scheduler_threads",
                        String.valueOf(scheduler.getConfiguredThreads())));
                src.sendFeedback(statLine("coresplit.command.csinfo.thread_multiplier",
                        String.format(Locale.ROOT, "%.1f", CoreSplitYaclConfig.getThreadMultiplier())));
            }
        } catch (Exception e) {
            sendError(src, "coresplit.command.error.execution", e.getMessage());
        }
        return 1;
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private static MutableComponent header(String key) {
        return Component.literal("§6═══════════════════════════════════════")
                .append(Component.literal("\n"))
                .append(Component.literal("         ")
                        .append(Component.translatable(key)
                                .setStyle(Style.EMPTY.withColor(COLOR_HEADER))))
                .append(Component.literal("\n"))
                .append(Component.literal("§6═══════════════════════════════════════"));
    }

    private static MutableComponent subHeader(String key) {
        return Component.literal("")
                .append(Component.literal("\n"))
                .append(Component.translatable(key)
                        .setStyle(Style.EMPTY.withColor(COLOR_HEADER)));
    }

    private static MutableComponent statLine(String labelKey, String value) {
        return Component.literal("  ")
                .append(Component.translatable(labelKey)
                        .setStyle(Style.EMPTY.withColor(COLOR_DESC)))
                .append(Component.literal(": ")
                        .setStyle(Style.EMPTY.withColor(COLOR_DESC)))
                .append(Component.literal(value)
                        .setStyle(Style.EMPTY.withColor(COLOR_VALUE)));
    }

    private static void printModuleStatus(FabricClientCommandSource src, String name, boolean enabled) {
        String status = enabled ? "§a[ON] " : "§c[OFF] ";
        src.sendFeedback(Component.literal("  " + status + "§b" + name));
    }

    private static void sendError(FabricClientCommandSource src, String key, Object... args) {
        Component msg = Component.translatable(key, args)
                .setStyle(Style.EMPTY.withColor(COLOR_WARN));
        src.sendFeedback(msg);
    }

    private static MemoryOptimizerInfo getMemoryOptimizerInfo() {
        try {
            com.coresplit.memory.MemoryOptimizer mem = CoreSplitMod.getMemoryOptimizer();
            if (mem != null) {
                return new MemoryOptimizerInfo((int) mem.getMemorySavedMb());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private record MemoryOptimizerInfo(int savedMb) {}

    // =========================================================================
    // Toggleable module definitions
    // =========================================================================

    /**
     * Enum of modules that can be toggled via the {@code /cstoggle} command.
     * Each entry maps a user-friendly name to the module's enable/disable logic.
     */
    private enum ToggleableModule {
        GOVERNOR("governor", "coresplit.command.cstoggle.desc_governor") {
            @Override boolean isEnabled() { return CoreSplitYaclConfig.isPerformanceGovernorEnabled(); }
            @Override void setEnabled(boolean on) {
                CoreSplitYaclConfig.setPerformanceGovernorEnabled(on);
                PerformanceGovernor g = CoreSplitClientMod.getGovernor();
                if (g != null) g.setEnabled(on);
            }
        },
        SCHEDULER("scheduler", "coresplit.command.cstoggle.desc_scheduler") {
            @Override boolean isEnabled() { return CoreSplitYaclConfig.isTaskSchedulerEnabled(); }
            @Override void setEnabled(boolean on) {
                CoreSplitYaclConfig.setTaskSchedulerEnabled(on);
                TaskScheduler s = CoreSplitClientMod.getScheduler();
                if (s != null) s.setEnabled(on);
            }
        },
        RENDER_LIMITER("renderlimiter", "coresplit.command.cstoggle.desc_renderlimiter") {
            @Override boolean isEnabled() { return EntityRenderLimiter.getInstance().isEnabled(); }
            @Override void setEnabled(boolean on) {
                CoreSplitYaclConfig.setEntityRenderLimiterEnabled(on);
                EntityRenderLimiter.getInstance().setEnabled(on);
            }
        },
        PARTICLE_FILTER("particle", "coresplit.command.cstoggle.desc_particle") {
            @Override boolean isEnabled() { return CoreSplitYaclConfig.isParticleFilterEnabled(); }
            @Override void setEnabled(boolean on) {
                CoreSplitYaclConfig.setParticleFilterEnabled(on);
                try {
                    com.coresplit.particle.ParticleFilter.getInstance().setEnabled(on);
                } catch (Exception ignored) {}
            }
        },
        EXPLOSION("explosion", "coresplit.command.cstoggle.desc_explosion") {
            @Override boolean isEnabled() {
                com.coresplit.explosion.ExplosionOptimizer e = CoreSplitMod.getExplosionOptimizer();
                return e != null && e.isEnabled();
            }
            @Override void setEnabled(boolean on) {
                com.coresplit.explosion.ExplosionOptimizer e = CoreSplitMod.getExplosionOptimizer();
                if (e != null) e.setEnabled(on);
            }
        },
        AI("ai", "coresplit.command.cstoggle.desc_ai") {
            @Override boolean isEnabled() {
                com.coresplit.ai.AiOptimizer a = CoreSplitMod.getAiOptimizer();
                return a != null && a.isEnabled();
            }
            @Override void setEnabled(boolean on) {
                com.coresplit.ai.AiOptimizer a = CoreSplitMod.getAiOptimizer();
                if (a != null) a.setEnabled(on);
            }
        },
        MEMORY("memory", "coresplit.command.cstoggle.desc_memory") {
            @Override boolean isEnabled() {
                com.coresplit.memory.MemoryOptimizer m = CoreSplitMod.getMemoryOptimizer();
                return m != null && m.isEnabled();
            }
            @Override void setEnabled(boolean on) {
                CoreSplitYaclConfig.setMemoryOptimizationEnabled(on);
                com.coresplit.memory.MemoryOptimizer m = CoreSplitMod.getMemoryOptimizer();
                if (m != null) m.setEnabled(on);
            }
        },
        SHADER("shader", "coresplit.command.cstoggle.desc_shader") {
            @Override boolean isEnabled() { return CoreSplitYaclConfig.isShaderPerformanceOptimizationEnabled(); }
            @Override void setEnabled(boolean on) {
                CoreSplitYaclConfig.setShaderPerformanceOptimizationEnabled(on);
            }
        },
        DEEP_COMPAT("deepcompat", "coresplit.command.cstoggle.desc_deepcompat") {
            @Override boolean isEnabled() { return CoreSplitYaclConfig.isDeepCompatEnabled(); }
            @Override void setEnabled(boolean on) {
                CoreSplitYaclConfig.setDeepCompatEnabled(on);
            }
        };

        private static final String[] NAMES;
        static {
            NAMES = new String[values().length];
            for (int i = 0; i < values().length; i++) {
                NAMES[i] = values()[i].name;
            }
        }

        private final String name;
        private final String descKey;

        ToggleableModule(String name, String descKey) {
            this.name = name;
            this.descKey = descKey;
        }

        abstract boolean isEnabled();
        abstract void setEnabled(boolean on);

        String getDisplayName() { return name; }
        String getDescriptionKey() { return descKey; }

        static ToggleableModule fromName(String input) {
            if (input == null) return null;
            String lower = input.toLowerCase(Locale.ROOT);
            for (ToggleableModule m : values()) {
                if (m.name.equals(lower)) return m;
            }
            return null;
        }
    }
}
