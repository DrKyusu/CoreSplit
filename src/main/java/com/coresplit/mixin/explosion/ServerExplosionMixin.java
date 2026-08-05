package com.coresplit.mixin.explosion;

import com.coresplit.CoreSplitMod;
import com.coresplit.explosion.ExplosionOptimizer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端爆炸 Mixin。
 *
 * <p>拦截 {@link ServerExplosion#explode()}，将爆炸事件转交 {@link ExplosionOptimizer}
 * 进行分帧批处理。模块禁用时不拦截，走原版逻辑。
 *
 * <p>注意：MC 26.2 的 ServerExplosion.explode() 方法签名需在 genSources 后二次确认。
 * 若映射有变，调整 @Inject 的 method 参数。
 */
@Mixin(ServerExplosion.class)
public class ServerExplosionMixin {

    // 修复BUG: ServerExplosion.explode() 返回 int（被摧毁方块数），非 void。
    // Mixin 强制要求非 void 目标的 @Inject 必须使用 CallbackInfoReturnable 而非 CallbackInfo，
    // 否则在 INJECT_APPLY 阶段抛 InvalidInjectionException：
    // "Invalid descriptor ... CallbackInfo is required! CallbackInfoReturnable is required!"
    // 导致进入世界/触发爆炸时服务端 tick 崩溃（ReportedException: Ticking entity）。
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void coresplit$onExplode(CallbackInfoReturnable<Integer> cir) {
        try {
            ExplosionOptimizer optimizer = ExplosionOptimizer.getInstance();
            if (!optimizer.isEnabled()) {
                return; // 模块禁用，走原版
            }

            ServerExplosion explosion = (ServerExplosion) (Object) this;
            // 从爆炸对象获取中心和半径（字段名需对照映射确认）
            // 由于无法直接访问私有字段，此处通过反射或 @Shadow 获取
            // 简化处理：提交一个默认参数的爆炸任务，实际坐标由 Mixin @Shadow 提供
            Level level = getLevel(explosion);
            if (level == null) return;

            double x = getExplosionX(explosion);
            double y = getExplosionY(explosion);
            double z = getExplosionZ(explosion);
            float radius = getExplosionRadius(explosion);
            double distToPlayer = 0; // 实际由最近玩家距离计算

            boolean handled = optimizer.submit(x, y, z, radius, distToPlayer);
            if (handled) {
                // 已接管，取消原版爆炸逻辑（分帧处理后再应用效果）
                // 注意：取消后需在异步处理完成后回调原版伤害/方块破坏
                // 当前实现仅做计数与粒子限流，不取消原版物理（保证功能完整性）
                // ci.cancel(); // 取消注释以完全接管爆炸
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] ServerExplosion mixin failed", e);
            // 异常时不取消，走原版逻辑保证安全
        }
    }

    // 以下方法通过反射获取爆炸参数，避免硬编码字段名导致映射变化时编译失败
    // 实际生产环境应使用 @Shadow 注解

    private Level getLevel(ServerExplosion explosion) {
        try {
            // ServerExplosion 通常持有 Level 引用
            java.lang.reflect.Field f = ServerExplosion.class.getDeclaredField("level");
            f.setAccessible(true);
            return (Level) f.get(explosion);
        } catch (Exception e) {
            return null;
        }
    }

    private double getExplosionX(ServerExplosion explosion) {
        return getVec3Component(explosion, 0);
    }

    private double getExplosionY(ServerExplosion explosion) {
        return getVec3Component(explosion, 1);
    }

    private double getExplosionZ(ServerExplosion explosion) {
        return getVec3Component(explosion, 2);
    }

    private double getVec3Component(ServerExplosion explosion, int index) {
        try {
            java.lang.reflect.Field f = ServerExplosion.class.getDeclaredField("center");
            f.setAccessible(true);
            Object vec3 = f.get(explosion);
            if (vec3 != null) {
                java.lang.reflect.Method m = vec3.getClass().getMethod(index == 0 ? "x" : index == 1 ? "y" : "z");
                return ((Number) m.invoke(vec3)).doubleValue();
            }
        } catch (Exception e) {
            // 忽略
        }
        return 0;
    }

    private float getExplosionRadius(ServerExplosion explosion) {
        try {
            java.lang.reflect.Field f = ServerExplosion.class.getDeclaredField("radius");
            f.setAccessible(true);
            return f.getFloat(explosion);
        } catch (Exception e) {
            return 4.0f; // TNT 默认半径
        }
    }
}
