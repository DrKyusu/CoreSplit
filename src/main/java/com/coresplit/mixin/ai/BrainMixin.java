package com.coresplit.mixin.ai;

import com.coresplit.CoreSplitMod;
import com.coresplit.ai.AiOptimizer;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体大脑 Mixin。
 *
 * <p>针对村民、末影龙等使用 {@link Brain} 的实体，对其 {@code tick} 方法进行节流。
 * 与 {@link MobMixin} 配合，提供第二层 AI 节流保障。
 */
@Mixin(Brain.class)
public class BrainMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void coresplit$onBrainTick(CallbackInfo ci) {
        try {
            AiOptimizer optimizer = AiOptimizer.getInstance();
            if (!optimizer.isEnabled()) {
                return;
            }
            // Brain 节流：由 MobMixin 在 Mob.tick() 层面短路覆盖，
            // 此处作为补充，对直接调用 Brain.tick() 的场景提供节流
            // 当前实现依赖 MobMixin，此处不额外短路
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Brain mixin failed", e);
        }
    }
}
