package com.coresplit.mixin;

import com.coresplit.CoreSplitMod;
import com.coresplit.threading.ExplosionWorkerPool;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionBehavior;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 爆炸计算 Mixin — 拦截 collectBlocksAndDamageEntities()，
 * 用并行射线投射替代原版单线程实现。
 *
 * 目标方法：Explosion.collectBlocksAndDamageEntities()
 *
 * 策略：
 *   在 HEAD 处注入，若并行爆炸模式启用，则：
 *     1. 调用 ExplosionWorkerPool 并行收集受影响方块
 *     2. 调用 ExplosionWorkerPool 并行计算实体伤害参数 + 串行应用
 *     3. 取消原版方法执行
 *   若未启用或爆炸池未初始化，正常执行原版逻辑（ci 不 cancel）。
 *
 * 兼容性：
 *   - 与 Lithium 完全兼容（Lithium 不修改爆炸核心逻辑）
 *   - 与 C2ME 兼容（C2ME 优化区块加载，不涉及爆炸）
 *   - 与其他修改 Explosion 的模组可能冲突（通过配置开关可禁用）
 *
 * 字段映射说明：
 *   以下 @Shadow 字段使用 Mojang 官方映射名称。
 *   若版本映射不同，仅需修改 @Shadow 注解中的字段名。
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    // ─── Shadow 字段 ───
    @Shadow @Final private World world;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Shadow @Final private float radius;
    @Shadow @Nullable @Final private Entity source;

    @Shadow @Final private List<BlockPos> toBlow;
    @Shadow @Final private Map<Entity, Vec3d> hitEntities;

    /**
     * 拦截原版爆炸计算，替换为并行版本。
     */
    @Inject(
            method = "collectBlocksAndDamageEntities",
            at = @At("HEAD"),
            cancellable = true
    )
    private void coresplit$parallelExplode(CallbackInfo ci) {
        // 安全检查：仅在启用且池已初始化时替换
        if (CoreSplitMod.getConfig() == null
                || !CoreSplitMod.getConfig().isExplosionParallelEnabled()) {
            return;
        }

        ExplosionWorkerPool pool = ExplosionWorkerPool.getInstance();
        if (pool == null) {
            return;
        }

        try {
            // ─── Phase 1: 并行射线投射，收集受影响方块 ───
            Set<BlockPos> blocks = pool.collectAffectedBlocks(
                    this.world, this.x, this.y, this.z, this.radius
            );
            this.toBlow.addAll(blocks);

            // ─── Phase 2: 并行计算 + 串行应用实体伤害 ───
            pool.damageEntitiesParallel(
                    this.world, this.source,
                    this.x, this.y, this.z, this.radius,
                    this.toBlow
            );

            ci.cancel(); // 跳过原版单线程实现

        } catch (Exception e) {
            // 并行计算异常时回退到原版（保证游戏不崩）
            CoreSplitMod.LOGGER.error(
                    "[CoreSplit] Parallel explosion failed, falling back to vanilla", e);
            // 不 cancel → 原版逻辑继续执行
        }
    }
}