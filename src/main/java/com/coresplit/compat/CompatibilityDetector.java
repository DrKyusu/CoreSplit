package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class CompatibilityDetector {

    private static final Map<String, String> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("c2me", "chunk parallel loading");
        KNOWN.put("async", "entity parallel tick");
        KNOWN.put("c3h6n6o6", "entity parallel tick");
        KNOWN.put("lithium", "entity AI parallel");
    }

    private final Map<String, String> detected = new LinkedHashMap<>();
    private boolean done = false;

    public void detect() {
        FabricLoader loader = FabricLoader.getInstance();
        for (Map.Entry<String, String> e : KNOWN.entrySet()) {
            if (loader.isModLoaded(e.getKey())) {
                detected.put(e.getKey(), e.getValue());
                Optional<ModContainer> mod = loader.getModContainer(e.getKey());
                String ver = mod.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
                CoreSplitMod.LOGGER.warn("[CoreSplit] Conflict: {} v{}", e.getKey(), ver);
            }
        }
        done = true;
        CoreSplitMod.LOGGER.info("[CoreSplit] Compat: {}", detected.isEmpty() ? "clear" : detected);
    }

    public Map<String, String> getDetectedConflicts() {
        return Collections.unmodifiableMap(detected);
    }

    public boolean isDetectionComplete() { return done; }
}