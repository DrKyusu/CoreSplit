package com.coresplit.mixin.explosion;

import com.coresplit.CoreSplitMod;
import com.coresplit.particle.ParticleFilter;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 粒子引擎 Mixin（客户端）。
 *
 * <p>对 {@link ParticleEngine#createParticle} 进行拦截，根据 {@link ParticleFilter}
 * 的策略过滤粒子：
 * <ul>
 *   <li>视野内（目视可见）的粒子：始终加载</li>
 *   <li>视野外的粒子：距离超过"粒子渲染距离"时屏蔽</li>
 * </ul>
 *
 * <p>注意：仅客户端生效，服务端不加载此类（mixins.coresplit.json 的 client 数组）。
 */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void coresplit$filterParticleCreation(
            ParticleOptions options, double x, double y, double z,
            double xa, double ya, double za,
            CallbackInfoReturnable<net.minecraft.client.particle.Particle> cir) {
        try {
            ParticleFilter filter = ParticleFilter.getInstance();
            if (!filter.isEnabled()) return;

            if (!filter.shouldCreateParticle(x, y, z)) {
                cir.setReturnValue(null);
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] ParticleEngine createParticle mixin failed", e);
        }
    }
}
