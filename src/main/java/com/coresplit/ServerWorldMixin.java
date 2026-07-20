package com.coresplit.mixin;

import com.coresplit.CoreSplitMod;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Hook ServerWorld 的 Tick 方法。
 *
 * 目标方法：ServerWorld.tick(BooleanSupplier)
 *
 * 用途：
 *   - 在维度 Tick 开始/结束时记录性能指标
 *   - 检测并报告异常耗时的维度
 *   - 为未来的内部 Tick 拆分提供注入点
 *
 * 注意：当维度线程模式启用时，此方法在 DimensionWorker 线程上调用（而非主线程）。
 *       不在此处做线程调度，仅做监控与辅助。
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void coresplit$onTickHead(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        // 标记当前线程正在 Tick 此维度
        // 可用于调试时追踪线程-维度映射关系
        ServerWorld self = (ServerWorld) (Object) this;
        Thread.currentThread().setName(
                "CoreSplit-Dim[" + self.getRegistryKey().getValue() + "]"
        );
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void coresplit$onTickReturn(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        // 恢复线程名
        Thread.currentThread().setName(
                Thread.currentThread().getName().replaceAll("CoreSplit-Dim\\[.*?\\]", "CoreSplit-Worker")
        );
    }
}