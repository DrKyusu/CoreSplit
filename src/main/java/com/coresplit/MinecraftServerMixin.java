package com.coresplit.mixin;

import com.coresplit.CoreSplitMod;
import com.coresplit.threading.DimensionThreadManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Hook MinecraftServer 的世界 Tick 调度。
 *
 * 目标方法：MinecraftServer.tickWorlds(BooleanSupplier)
 *
 * 策略：
 *   在原版遍历各维度并逐个调用 ServerWorld.tick() 之前拦截，
 *   由 DimensionThreadManager 将各维度分发到独立线程并行执行。
 *   若维度线程未启用，正常执行原版逻辑（ci 不 cancel）。
 *
 * 注意：此 Mixin 仅在维度线程模式启用时替换行为，
 *       保证与原版及其他模组的最大兼容性。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    /**
     * 在 tickWorlds 开头注入。
     * 如果 CoreSplit 维度线程模式已启用，则接管整个维度调度并取消原版。
     */
    @Inject(
            method = "tickWorlds",
            at = @At("HEAD"),
            cancellable = true
    )
    private void coresplit$onTickWorlds(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        DimensionThreadManager manager = CoreSplitMod.getManager();
        if (manager != null && manager.isEnabled()) {
            manager.tickAllDimensions(shouldKeepTicking);
            ci.cancel(); // 跳过原版的维度遍历
        }
    }
}