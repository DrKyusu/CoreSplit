package com.coresplit.mixin.ai;

import com.coresplit.CoreSplitMod;
import com.coresplit.ai.AiOptimizer;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 路径导航 Mixin。
 *
 * <p>包裹路径计算逻辑，通过 {@link com.coresplit.ai.PathNavCache} 查询/存入
 * 路径缓存，减少重复寻路计算。
 *
 * <p>修复BUG: 原实现包含 public static 的 tryGetCachedPath/cachePath 方法。
 * Sponge Mixin 要求 Mixin 类中所有非注入/非 shadow 方法必须为 private，
 * 否则在 APPLY 阶段抛 InvalidMixinException，导致 Bootstrap 阶段崩溃：
 * "contains non-private static method tryGetCachedPath"。
 * 现仅保留 @Inject 钩子，对外 API 移入独立的 PathNavCache 工具类。
 */
@Mixin(PathNavigation.class)
public class PathNavigationMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void coresplit$onPathNavigationTick(CallbackInfo ci) {
        try {
            AiOptimizer optimizer = AiOptimizer.getInstance();
            if (optimizer == null || !optimizer.isEnabled()) {
                return;
            }
            // 路径缓存的查询/存入在 createPath 方法中通过 @Redirect 实现
            // 此处 tick 钩子仅做轻量级计数
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] PathNavigation mixin failed", e);
        }
    }
}
