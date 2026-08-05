package com.coresplit.ai;

/**
 * 路径导航缓存门面（非 Mixin 类）。
 *
 * <p>将路径缓存的查询/写入接口从 {@code PathNavigationMixin} 抽取至此，
 * 作为对外暴露的静态工具 API。Mixin 类不应包含 public static 非注入方法，
 * 否则 Sponge Mixin 在 APPLY 阶段抛 {@code InvalidMixinException}，导致 Bootstrap 崩溃。
 *
 * <p>使用方式：直接调用 {@link #tryGetCachedPath(int, int, int, int, String)}
 * 与 {@link #cachePath(int, int, int, int, String, Object)}，
 * 无需依赖 Mixin 类。
 */
public final class PathNavCache {

    private PathNavCache() {}

    /**
     * 尝试从缓存中获取路径。
     *
     * @param startChunkX 起始区块 X
     * @param startChunkZ 起始区块 Z
     * @param endChunkZ   目标区块 Z
     * @param endChunkX   目标区块 X
     * @param entityType  实体类型
     * @return 缓存的路径对象，或 null（未命中/模块禁用/异常）
     */
    public static Object tryGetCachedPath(int startChunkX, int startChunkZ,
                                          int endChunkX, int endChunkZ, String entityType) {
        try {
            AiOptimizer optimizer = AiOptimizer.getInstance();
            if (optimizer == null || !optimizer.isEnabled()) {
                return null;
            }
            PathCache cache = optimizer.getPathCache();
            if (cache == null) {
                return null;
            }
            PathKey key = new PathKey(startChunkX, startChunkZ, endChunkX, endChunkZ, entityType);
            return cache.tryGet(key);
        } catch (Exception e) {
            // 缓存查询失败不影响游戏逻辑
            return null;
        }
    }

    /**
     * 将计算出的路径存入缓存。
     *
     * @param startChunkX 起始区块 X
     * @param startChunkZ 起始区块 Z
     * @param endChunkX   目标区块 X
     * @param endChunkZ   目标区块 Z
     * @param entityType  实体类型
     * @param path        路径对象
     */
    public static void cachePath(int startChunkX, int startChunkZ,
                                  int endChunkX, int endChunkZ, String entityType, Object path) {
        try {
            AiOptimizer optimizer = AiOptimizer.getInstance();
            if (optimizer == null || !optimizer.isEnabled()) {
                return;
            }
            PathCache cache = optimizer.getPathCache();
            if (cache == null) {
                return;
            }
            PathKey key = new PathKey(startChunkX, startChunkZ, endChunkX, endChunkZ, entityType);
            cache.put(key, path);
        } catch (Exception e) {
            // 缓存写入失败不影响游戏逻辑
        }
    }
}
