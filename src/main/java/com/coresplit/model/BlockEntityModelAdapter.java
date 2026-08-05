package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockEntityModelAdapter {

    // 修复BUG: 原 cachedBlockModels 无大小限制，大量方块实体模型加载后常驻内存导致泄漏；
    // 改用 access-order LinkedHashMap + LRU 驱逐，上限 128 个方块实体模型。
    // 修复BUG: 原 blockEntityStates 无过期清理，卸载的方块实体状态永不删除，长期运行内存持续增长；
    // 添加上限 512 与 TTL 过期（5 分钟未访问自动清理），在 getOrCreateState 时惰性驱逐过期条目。
    private static final int MAX_CACHED_MODELS = 128;
    private static final int MAX_BLOCK_ENTITY_STATES = 512;
    private static final long STATE_TTL_MS = 5 * 60 * 1000L;

    private final CemModelLoader modelLoader;
    private final Map<String, BlockEntityModelState> blockEntityStates;
    private final Map<String, JemFileParser.CemModel> cachedBlockModels;

    private volatile boolean enabled = true;

    public BlockEntityModelAdapter(CemModelLoader modelLoader) {
        this.modelLoader = modelLoader;
        this.blockEntityStates = new ConcurrentHashMap<>();
        // accessOrder=true：访问时将条目移到链表尾，驱逐时移除链表头（最久未使用）
        this.cachedBlockModels = Collections.synchronizedMap(
                new LinkedHashMap<String, JemFileParser.CemModel>(MAX_CACHED_MODELS, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, JemFileParser.CemModel> eldest) {
                        return size() > MAX_CACHED_MODELS;
                    }
                });

        CoreSplitMod.LOGGER.info("[CoreSplit] BlockEntityModelAdapter initialized");
    }

    public boolean hasCustomModel(String blockEntityType) {
        if (!enabled) return false;
        return getModel(blockEntityType) != null;
    }

    public JemFileParser.CemModel getModel(String blockEntityType) {
        if (!enabled) return null;

        JemFileParser.CemModel cached = cachedBlockModels.get(blockEntityType);
        if (cached != null) {
            return cached;
        }

        JemFileParser.CemModel model = modelLoader.getModel(blockEntityType);
        if (model != null) {
            cachedBlockModels.put(blockEntityType, model);
        }

        return model;
    }

    public BlockEntityModelState getOrCreateState(String blockEntityId) {
        // 修复BUG: 惰性清理过期状态，防止无界增长。
        // 当状态数超过上限的 80% 时触发一次全量过期扫描，移除超过 STATE_TTL_MS 未更新的条目。
        if (blockEntityStates.size() > MAX_BLOCK_ENTITY_STATES * 0.8) {
            evictExpiredStates();
        }
        BlockEntityModelState state = blockEntityStates.get(blockEntityId);
        if (state != null) {
            state.lastUpdateTime = System.currentTimeMillis();
            return state;
        }
        // 达到硬上限时不再创建新状态，返回 null 避免 OOM（上层需判空）
        if (blockEntityStates.size() >= MAX_BLOCK_ENTITY_STATES) {
            return null;
        }
        return blockEntityStates.computeIfAbsent(blockEntityId, BlockEntityModelState::new);
    }

    private void evictExpiredStates() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, BlockEntityModelState>> it = blockEntityStates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BlockEntityModelState> entry = it.next();
            if (now - entry.getValue().lastUpdateTime > STATE_TTL_MS) {
                it.remove();
            }
        }
    }

    public void updateBlockEntityState(String blockEntityId, String stateKey, Object value) {
        BlockEntityModelState state = getOrCreateState(blockEntityId);
        if (state != null) {
            state.setState(stateKey, value);
        }
    }

    public void updateBlockEntityStates(String blockEntityId, Map<String, Object> states) {
        BlockEntityModelState state = getOrCreateState(blockEntityId);
        if (state != null) {
            for (Map.Entry<String, Object> entry : states.entrySet()) {
                state.setState(entry.getKey(), entry.getValue());
            }
        }
    }

    public void removeBlockEntityState(String blockEntityId) {
        blockEntityStates.remove(blockEntityId);
    }

    public void clearAllStates() {
        blockEntityStates.clear();
    }

    public void invalidateModelCache(String blockEntityType) {
        cachedBlockModels.remove(blockEntityType);
    }

    public void invalidateAllModelCaches() {
        cachedBlockModels.clear();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAllStates();
            invalidateAllModelCaches();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getActiveBlockEntityCount() {
        return blockEntityStates.size();
    }

    public int getCachedModelCount() {
        return cachedBlockModels.size();
    }

    public static class BlockEntityModelState {
        public final String blockEntityId;
        public final Map<String, Object> states;
        // 修复BUG: 原字段非volatile，setState写入后其他线程读取可能看不到最新更新时间（可见性问题）
        public volatile long lastUpdateTime;

        public BlockEntityModelState(String blockEntityId) {
            this.blockEntityId = blockEntityId;
            this.states = new ConcurrentHashMap<>();
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public void setState(String key, Object value) {
            states.put(key, value);
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public Object getState(String key) {
            return states.get(key);
        }

        public boolean hasState(String key) {
            return states.containsKey(key);
        }

        public int getIntState(String key, int defaultValue) {
            Object value = states.get(key);
            if (value instanceof Integer) {
                return (Integer) value;
            }
            return defaultValue;
        }

        public boolean getBooleanState(String key, boolean defaultValue) {
            Object value = states.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            return defaultValue;
        }

        public float getFloatState(String key, float defaultValue) {
            Object value = states.get(key);
            if (value instanceof Float) {
                return (Float) value;
            }
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
            return defaultValue;
        }

        public String getStringState(String key, String defaultValue) {
            Object value = states.get(key);
            if (value instanceof String) {
                return (String) value;
            }
            return defaultValue;
        }

        public void clear() {
            states.clear();
        }

        public int getStateCount() {
            return states.size();
        }
    }
}