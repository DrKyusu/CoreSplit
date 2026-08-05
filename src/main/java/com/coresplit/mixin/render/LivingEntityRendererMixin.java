package com.coresplit.mixin.render;

import com.coresplit.CoreSplitMod;
import com.coresplit.renderlimiter.EntityRenderLimiter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void coresplit$onLivingEntitySubmit(LivingEntityRenderState state, PoseStack poseStack,
                                                 SubmitNodeCollector submitNodeCollector,
                                                 CameraRenderState camera,
                                                 CallbackInfo ci) {
        try {
            EntityRenderLimiter limiter = EntityRenderLimiter.getInstance();
            if (!limiter.isEnabled()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            if (state.isInvisible) return;

            if (!limiter.shouldRenderLivingEntity(state.x, state.y + state.eyeHeight * 0.5, state.z)) {
                ci.cancel();
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] LivingEntity submit mixin failed", e);
        }
    }
}
