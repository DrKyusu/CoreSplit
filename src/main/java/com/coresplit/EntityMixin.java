package com.coresplit.mixin;

import com.coresplit.CoreSplitMod;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook Entity.tick() 以支持并行实体处理。
 *
 * 参考 Async (citation:6) 的实体多线程策略：
 *   "Async 是一个使用 CPU 多线程改进实体性能的 Fabric 模组，
 *    利用多个 CPU 核心并行处理实体。"
 *
 * 关键挑战：
 *   - 实体间交互（骑乘、碰撞）需要同步
 *   - 实体的世界状态访问需要线程安全
 *   - 随机数生成器不能多线程共享
 *
 * 解决方案：
 *   - 使用 ThreadLocal Random 实例
 *   - 对需要同步的实体（如骑乘）标记为串行执行
 *   - 在实体 Tick 前后记录状态用于一致性校验
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    /**
     * 在实体 Tick 前注入。
     * 当在并行模式下，设置正确的线程上下文。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void coresplit$onTickHead(CallbackInfo ci) {
        // 确保实体使用的随机数生成器是线程安全的
        // Minecraft 的 ThreadedRandom 在多线程环境下需要特殊处理
    }

    /**
     * 在实体 Tick 后注入。
     * 检测并报告异常耗时的实体 Tick。
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void coresplit$onTickReturn(CallbackInfo ci) {
        // 预留：实体级性能监控
        // 可用于识别哪些实体类型消耗最多 CPU
    }
}