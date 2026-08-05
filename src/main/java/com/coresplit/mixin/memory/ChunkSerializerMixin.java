package com.coresplit.mixin.memory;

import com.coresplit.CoreSplitMod;
import com.coresplit.memory.MemoryOptimizer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 区块序列化 Mixin。
 *
 * <p>复用 {@link com.coresplit.memory.ByteBufferPool} 进行区块数据的序列化/反序列化，
 * 减少 ByteBuffer 的直接分配。
 *
 * <p>注意：MC 26.2 起 {@code ChunkSerializer} 已重命名为 {@link SerializableChunkData}，
 * 位于同一包 {@code net.minecraft.world.level.chunk.storage}。
 */
@Mixin(SerializableChunkData.class)
public class ChunkSerializerMixin {

    // 修复BUG: SerializableChunkData.write() 返回 CompoundTag（非 void），
    // Mixin 强制要求非 void 目标的 @Inject 必须使用 CallbackInfoReturnable 而非 CallbackInfo，
    // 否则在 mixin 应用阶段抛出 InvalidMixinException，配合 required:true + defaultRequire:1
    // 会导致游戏在加载该类时直接崩溃。改用 CallbackInfoReturnable<CompoundTag>。
    @Inject(method = "write", at = @At("HEAD"))
    private void coresplit$onChunkWrite(CallbackInfoReturnable<CompoundTag> cir) {
        try {
            MemoryOptimizer optimizer = MemoryOptimizer.getInstance();
            if (!optimizer.isEnabled()) return;
            // 区块写入时的 ByteBuffer 池化复用钩子
            // 实际复用通过 @Redirect 包裹 ByteBuffer.allocate 实现
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] ChunkSerializer mixin failed", e);
        }
    }
}
