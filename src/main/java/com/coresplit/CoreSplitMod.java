package com.coresplit;

import com.coresplit.compat.CompatibilityDetector;
import com.coresplit.config.CoreSplitConfig;
import com.coresplit.monitoring.ServerPerformanceMonitor;
import com.coresplit.network.NetworkHandler;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoreSplitMod implements ModInitializer, DedicatedServerModInitializer {

    public static final String MOD_ID = "coresplit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile CoreSplitConfig config;
    private static volatile ServerPerformanceMonitor perfMonitor;
    private static volatile CompatibilityDetector compatibilityDetector;

    @Override
    public void onInitialize() {
        LOGGER.info("[CoreSplit] v2.0 initializing...");
        config = CoreSplitConfig.load();

        if (config.isCompatibilityMode()) {
            compatibilityDetector = new CompatibilityDetector();
            compatibilityDetector.detect();
        }

        perfMonitor = new ServerPerformanceMonitor(config);

        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                LOGGER.info("[CoreSplit] Server starting."));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            perfMonitor.dumpSummary();
            LOGGER.info("[CoreSplit] Server stopping.");
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> perfMonitor.onTickStart());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            perfMonitor.onTickEnd();
            if (config.isNetworkMetricsEnabled()) {
                NetworkHandler.updateSnapshot(perfMonitor);
            }
        });

        LOGGER.info("[CoreSplit] Bootstrap complete.");
    }

    @Override
    public void onInitializeServer() {
    }

    public static CoreSplitConfig getConfig() { return config; }
    public static ServerPerformanceMonitor getMetrics() { return perfMonitor; }
    public static CompatibilityDetector getCompatibilityDetector() { return compatibilityDetector; }
}