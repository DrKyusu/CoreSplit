package com.coresplit.mixin.ai;

import com.coresplit.CoreSplitMod;
import com.coresplit.ai.AiOptimizer;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 生物 AI 节流 Mixin。
 *
 * <p>拦截 {@link Mob#serverAiStep()}（仅 AI 调度，不含物理），在 HEAD 处调用
 * {@link AiOptimizer#shouldTickAi} 判断是否应执行 AI 逻辑。返回 false 时短路本次 AI 步进。
 *
 * <p>PERF: 使用 AiOptimizer 缓存的玩家位置，每 tick 仅刷新一次，避免每实体
 * 调用 getNearestPlayer() 造成的 O(mobs×players) 全玩家列表扫描。
 *
 * <p>修复BUG: 原实现钩入 {@code Mob.tick()} 并 ci.cancel()，而 tick() 是完整的实体 tick
 * （含物理位移、火焰/溺水/传送门、插值等），cancel 会跳过全部物理，导致实体状态损坏、
 * 位置 NaN、碰撞检测异常，严重时引发服务端 tick 崩溃。{@code serverAiStep()} 仅负责
 * goalSelector/brain/控制器的 AI 调度，cancel 它只跳过 AI，物理仍由 tick()→aiStep() 正常执行。
 *
 * <p>注：{@code serverAiStep()} 为 protected final，Mixin @Inject 可注入 final 方法（仅插入调用，
 * 非覆写）。该方法仅服务端调用，客户端注入但不触发，安全。
 */
@Mixin(Mob.class)
public class MobMixin {

    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void coresplit$onMobServerAiStep(CallbackInfo ci) {
        try {
            AiOptimizer optimizer = AiOptimizer.getInstance();
            if (!optimizer.isEnabled()) {
                return; // 模块禁用，走原版
            }

            Mob mob = (Mob) (Object) this;
            double entityX = mob.getX();
            double entityY = mob.getY();
            double entityZ = mob.getZ();

            // PERF: 使用每 tick 刷新一次的缓存玩家位置，避免每实体 getNearestPlayer()
            if (!optimizer.hasNearestPlayer()) {
                return; // 无玩家附近，不节流，保证生物基本 AI 行为
            }

            boolean shouldTick = optimizer.shouldTickAi(entityX, entityY, entityZ);
            if (!shouldTick) {
                ci.cancel(); // 短路本次 AI 步进（物理不受影响）
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Mob AI throttle mixin failed", e);
            // 异常时不取消，走原版逻辑保证安全
        }
    }
}
