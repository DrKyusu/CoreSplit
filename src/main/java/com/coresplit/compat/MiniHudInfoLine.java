package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

public class MiniHudInfoLine {

    public static void tryRegister() {
        if (FabricLoader.getInstance().isModLoaded("minihud")) {
            CoreSplitMod.LOGGER.info("[CoreSplit] MiniHUD detected.");
        } else {
            CoreSplitMod.LOGGER.info("[CoreSplit] MiniHUD not found.");
        }
    }

    public static List<String> getInfoLines() {
        return new ArrayList<>();
    }
}