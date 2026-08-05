package com.coresplit.mixin;

import com.coresplit.CoreSplitMod;
import com.coresplit.overlay.CoreSplitOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class HudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onExtractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            CoreSplitOverlay.render(guiGraphicsExtractor, deltaTracker);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] HUD overlay render failed", e);
        }
        try {
            CoreSplitOverlay.renderHud(guiGraphicsExtractor);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] F6 overlay render failed", e);
        }
    }
}