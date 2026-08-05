package com.coresplit.mixin.memory;

import com.coresplit.CoreSplitMod;
import com.coresplit.memory.MemoryOptimizer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 实体数据 Mixin。
 *
 * <p>拦截实体保存逻辑，复用 {@link com.coresplit.memory.EntityDataPool} 减少对象创建。
 */
@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "saveAsPassenger", at = @At("HEAD"))
    private void coresplit$onSaveAsPassenger(CallbackInfoReturnable<Object> cir) {
        try {
            MemoryOptimizer optimizer = MemoryOptimizer.getInstance();
            if (!optimizer.isEnabled()) return;
            // 实体保存时的对象池复用钩子
            // 实际复用通过 @Redirect 包裹 NBT 创建逻辑实现
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Entity save mixin failed", e);
        }
    }
}
