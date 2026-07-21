package com.coresplit.config;

import com.coresplit.CoreSplitMod;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class CoreSplitConfig {

    private boolean networkMetricsEnabled = true;
    private boolean monitoringEnabled = true;
    private boolean logPerTickMetrics = false;
    private boolean compatibilityMode = false;

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit.toml");

    public static CoreSplitConfig load() {
        CoreSplitConfig cfg = new CoreSplitConfig();

        if (!Files.exists(CONFIG_PATH)) {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                try (InputStream in = CoreSplitConfig.class.getResourceAsStream("/coresplit-default.toml")) {
                    if (in != null) {
                        Files.copy(in, CONFIG_PATH);
                    } else {
                        String content = "[network]\nmetrics_enabled = true\n\n"
                                + "[monitoring]\nenabled = true\nlog_per_tick = false\n\n"
                                + "[compatibility]\nenabled = false\n";
                        Files.writeString(CONFIG_PATH, content);
                    }
                }
            } catch (IOException e) {
                CoreSplitMod.LOGGER.error("[CoreSplit] Config creation failed", e);
            }
        }

        try (FileConfig fc = FileConfig.of(CONFIG_PATH)) {
            fc.load();
            cfg.networkMetricsEnabled = fc.getOrElse("network.metrics_enabled", true);
            cfg.monitoringEnabled     = fc.getOrElse("monitoring.enabled", true);
            cfg.logPerTickMetrics     = fc.getOrElse("monitoring.log_per_tick", false);
            cfg.compatibilityMode     = fc.getOrElse("compatibility.enabled", false);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Config load failed", e);
        }

        return cfg;
    }

    public boolean isNetworkMetricsEnabled() { return networkMetricsEnabled; }
    public boolean isMonitoringEnabled()     { return monitoringEnabled; }
    public boolean isLogPerTickMetrics()     { return logPerTickMetrics; }
    public boolean isCompatibilityMode()     { return compatibilityMode; }
}