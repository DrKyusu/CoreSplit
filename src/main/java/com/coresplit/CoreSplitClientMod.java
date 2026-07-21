package com.coresplit;

import com.coresplit.compat.MiniHudInfoLine;
import com.coresplit.config.CoreSplitYaclConfig;
import com.coresplit.overlay.OverlayKeyBinding;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class CoreSplitClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CoreSplitMod.LOGGER.info("[CoreSplit] Client v2.0 initializing...");

        CoreSplitYaclConfig.load();
        OverlayKeyBinding.register();
        MiniHudInfoLine.tryRegister();

        // TODO: Register overlay render callback once MC 26.2 HUD render hook is found.
        // Fabric HudRenderCallback does not exist in Fabric API 0.152.1+26.2.
        // Options:
        //   1. Use a Mixin on net.minecraft.client.gui.Hud.render()
        //   2. Find the renamed Fabric event in the API JAR
        //
        // To search: jar tf fabric-api-*.jar | findstr /i "HudRender\|AfterHud\|InGameOverlay"

        CoreSplitMod.LOGGER.info("[CoreSplit] Client ready.");
    }
}