package com.coresplit.mixin.ai;

import com.coresplit.CoreSplitMod;
import com.coresplit.ai.AiOptimizer;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 目标选择器节流 Mixin。
 *
 * <p>拦截 {@link GoalSelector#tick()}，对目标评估进行桶化节流。
 * 与 {@link MobMixin} 配合，MobMixin 控制 tick 整体，本类控制目标选择器的评估频率。
 */
@Mixin(GoalSelector.class)
public class GoalSelectorMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void coresplit$onGoalSelectorTick(CallbackInfo ci) {
        try {
            AiOptimizer optimizer = AiOptimizer.getInstance();
            if (!optimizer.isEnabled()) {
                return;
            }
            // 目标选择器节流逻辑由 MobMixin 的 tick 短路覆盖，
            // 此处仅作为补充钩子，当前不额外短路
            // 未来可在此实现更细粒度的目标评估节流
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] GoalSelector mixin failed", e);
        }
    }
}
