package com.coresplit.overlay;

import com.coresplit.config.CoreSplitYaclConfig;
import net.minecraft.client.Minecraft;

public class CoreSplitOverlay {

    private static final Minecraft CLIENT = Minecraft.getInstance();

    /**
     * Overlay render entry point.
     *
     * MC 26.2 removed GuiGraphics/DrawContext. The correct rendering context
     * class needs to be identified from the decompiled sources.
     * Run: gradlew.bat genSources, then search for the HUD render method signature
     * in net.minecraft.client.gui.Hud to find the parameter type.
     *
     * In the meantime, this method is called from CoreSplitClientMod
     * but performs no drawing.
     */
    public static void render() {
        if (!CoreSplitYaclConfig.isShowOverlay()) return;

        // TODO: Implement rendering once MC 26.2's rendering context class is identified.
        // Likely candidates to search in genSources:
        //   - net.minecraft.client.gui.Hud (check render() method parameters)
        //   - net.minecraft.client.gui.GuiGraphicsExtractor (may be the new context)
        //
        // Example of what the final render method signature might look like:
        //   public static void render(<SomeContextClass> ctx, float tickDelta)
        //
        // Then use ctx.drawString(font, text, x, y, color, shadow) or equivalent.
    }
}