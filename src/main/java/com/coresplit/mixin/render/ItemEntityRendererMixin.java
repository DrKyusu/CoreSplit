package com.coresplit.mixin.render;

import com.coresplit.CoreSplitMod;
import com.coresplit.itemrender.ItemRenderConfig;
import com.coresplit.itemrender.ItemRenderOptimizer;
import com.coresplit.renderlimiter.EntityRenderLimiter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void coresplit$onItemSubmit(ItemEntityRenderState state, PoseStack poseStack,
                                         SubmitNodeCollector submitNodeCollector,
                                         CameraRenderState camera,
                                         CallbackInfo ci) {
        try {
            ItemRenderOptimizer optimizer = ItemRenderOptimizer.getInstance();
            EntityRenderLimiter limiter = EntityRenderLimiter.getInstance();

            if (!optimizer.isEnabled() && !limiter.isEnabled()) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            double px = mc.player.getX();
            double py = mc.player.getY() + mc.player.getEyeHeight();
            double pz = mc.player.getZ();
            double dx = state.x - px;
            double dy = state.y + 0.25 - py;
            double dz = state.z - pz;
            double distSq = dx * dx + dy * dy + dz * dz;
            double dist = Math.sqrt(distSq);

            if (optimizer.isEnabled()) {
                ItemRenderConfig config = ItemRenderConfig.getInstance();

                if (config.isDistanceLod()) {
                    if (dist > config.getFarDistance()) {
                        ci.cancel();
                        return;
                    }
                }

                if (config.isFrustumCulling()) {
                    if (dist > config.getNearDistance()) {
                        boolean inView = optimizer.getFrustumCuller()
                                .isInView(state.x, state.y + 0.25, state.z, 0.5f);
                        if (!inView) {
                            ci.cancel();
                            return;
                        }
                    }
                }
            }

            if (limiter.isEnabled()) {
                if (!limiter.shouldRenderItem(state.x, state.y + 0.25, state.z)) {
                    ci.cancel();
                }
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] ItemEntity submit mixin failed", e);
        }
    }
}
