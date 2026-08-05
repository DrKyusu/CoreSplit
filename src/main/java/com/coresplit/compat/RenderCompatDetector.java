package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class RenderCompatDetector {

    public enum CompatMode {
        VANILLA,
        SODIUM_ONLY,
        IRIS_ONLY,
        SODIUM_IRIS
    }

    private static final String SODIUM_MOD_ID = "sodium";
    private static final String IRIS_MOD_ID = "iris";

    private final AtomicBoolean detected = new AtomicBoolean(false);
    private volatile boolean sodiumPresent = false;
    private volatile boolean irisPresent = false;
    private volatile String sodiumVersion = "?";
    private volatile String irisVersion = "?";
    private volatile CompatMode compatMode = CompatMode.VANILLA;

    private static volatile RenderCompatDetector instance;

    public static RenderCompatDetector getInstance() {
        RenderCompatDetector result = instance;
        if (result == null) {
            synchronized (RenderCompatDetector.class) {
                result = instance;
                if (result == null) {
                    result = new RenderCompatDetector();
                    instance = result;
                }
            }
        }
        return result;
    }

    private RenderCompatDetector() {
    }

    public void detect() {
        if (detected.getAndSet(true)) return;

        FabricLoader loader = FabricLoader.getInstance();

        if (loader.isModLoaded(SODIUM_MOD_ID)) {
            sodiumPresent = true;
            Optional<ModContainer> mod = loader.getModContainer(SODIUM_MOD_ID);
            sodiumVersion = mod.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
            CoreSplitMod.LOGGER.info("[CoreSplit] Sodium detected: v{}", sodiumVersion);
        }

        if (loader.isModLoaded(IRIS_MOD_ID)) {
            irisPresent = true;
            Optional<ModContainer> mod = loader.getModContainer(IRIS_MOD_ID);
            irisVersion = mod.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
            CoreSplitMod.LOGGER.info("[CoreSplit] Iris detected: v{}", irisVersion);
        }

        if (sodiumPresent && irisPresent) {
            compatMode = CompatMode.SODIUM_IRIS;
        } else if (sodiumPresent) {
            compatMode = CompatMode.SODIUM_ONLY;
        } else if (irisPresent) {
            compatMode = CompatMode.IRIS_ONLY;
        } else {
            compatMode = CompatMode.VANILLA;
        }

        CoreSplitMod.LOGGER.info("[CoreSplit] Render compatibility mode: {}", compatMode);
    }

    public boolean isSodiumPresent() {
        if (!detected.get()) detect();
        return sodiumPresent;
    }

    public boolean isIrisPresent() {
        if (!detected.get()) detect();
        return irisPresent;
    }

    public CompatMode getCompatMode() {
        if (!detected.get()) detect();
        return compatMode;
    }

    public String getSodiumVersion() { return sodiumVersion; }
    public String getIrisVersion() { return irisVersion; }

    public boolean isDetectionComplete() { return detected.get(); }

    public String getCompatibilitySummary() {
        if (!detected.get()) return "Not detected";
        StringBuilder sb = new StringBuilder();
        if (sodiumPresent) {
            sb.append("Sodium v").append(sodiumVersion);
        }
        if (irisPresent) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append("Iris v").append(irisVersion);
        }
        if (sb.isEmpty()) {
            sb.append("Vanilla");
        }
        sb.append(" (mode: ").append(compatMode).append(")");
        return sb.toString();
    }
}
