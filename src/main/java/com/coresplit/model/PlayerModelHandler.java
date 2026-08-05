package com.coresplit.model;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Collections;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerModelHandler {

    private static final int MAX_BONE_TRANSFORM_STACK = 64;
    private static final int VECTOR_SIZE = 3;
    private static final float DEFAULT_SCALE = 1.0f;
    // 已知与玩家模型系统冲突的模组（同样替换/覆盖玩家模型渲染）
    private static final Set<String> KNOWN_PLAYER_MODEL_CONFLICTS = Set.of(
            "fresh_animations", "firstperson", "playeranimator",
            "moreplayermodels", "customplayermodels", "animated_player"
    );

    // 修复BUG: 原 playerModelStates 无上限，多人服务器大量玩家时内存持续增长；
    // 添加上限 256 个玩家状态，超限后拒绝创建新状态。
    // 修复BUG: 原警告日志无限流，压力测试下大量 bone transform 超限会刷屏日志；
    // 采用令牌桶限流（每 10 秒最多 5 条警告），避免日志风暴。
    private static final int MAX_PLAYER_STATES = 256;
    private static final long WARN_RATE_LIMIT_MS = 10_000L;
    private static final int WARN_MAX_PER_WINDOW = 5;

    private final Map<String, PlayerModelState> playerModelStates;
    private final Set<String> conflictingMods;
    private final Map<String, ModelPriority> modelPriorities;

    private final AtomicLong lastWarnTime = new AtomicLong(0);
    private final AtomicLong warnCount = new AtomicLong(0);

    private volatile boolean enabled = true;
    private volatile boolean conflictDetectionEnabled = true;
    private volatile ModelPriority defaultPriority = ModelPriority.CUSTOM;

    public PlayerModelHandler() {
        this.playerModelStates = new ConcurrentHashMap<>();
        this.conflictingMods = new CopyOnWriteArraySet<>();
        this.modelPriorities = new ConcurrentHashMap<>();

        CoreSplitMod.LOGGER.info("[CoreSplit] PlayerModelHandler initialized");
    }

    // 警告限流
    private boolean allowWarn() {
        long now = System.currentTimeMillis();
        long last = lastWarnTime.get();
        if (now - last > WARN_RATE_LIMIT_MS) {
            lastWarnTime.set(now);
            warnCount.set(1);
            return true;
        }
        return warnCount.incrementAndGet() <= WARN_MAX_PER_WINDOW;
    }

    public void applyCustomModel(String playerName, JemFileParser.CemModel model) {
        if (!enabled) return;

        PlayerModelState state = getOrCreateState(playerName);
        if (state == null) return;
        state.currentModel = model;

        resetBoneTransforms(playerName);

        CoreSplitMod.LOGGER.debug("[CoreSplit] Applied custom model to player: {}", playerName);
    }

    public void resetBoneTransforms(String playerName) {
        PlayerModelState state = getOrCreateState(playerName);
        if (state == null) return;
        state.boneTransforms.clear();
        state.boneResetFlags.clear();
    }

    public void resetBoneTransform(String playerName, String boneName) {
        PlayerModelState state = playerModelStates.get(playerName);
        if (state != null) {
            state.boneTransforms.remove(boneName);
            state.boneResetFlags.put(boneName, true);
        }
    }

    public void setBoneTransform(String playerName, String boneName, BoneTransform transform) {
        PlayerModelState state = getOrCreateState(playerName);
        if (state == null) return;
        // 修复BUG: 原代码未限制boneTransforms大小，MAX_BONE_TRANSFORM_STACK常量定义后从未使用（死代码），可能导致无界内存增长
        if (!state.boneTransforms.containsKey(boneName) && state.boneTransforms.size() >= MAX_BONE_TRANSFORM_STACK) {
            if (allowWarn()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Bone transform stack limit reached for player: {}", playerName);
            }
            return;
        }
        state.boneTransforms.put(boneName, transform);
        state.boneResetFlags.remove(boneName);
    }

    public BoneTransform getBoneTransform(String playerName, String boneName) {
        PlayerModelState state = playerModelStates.get(playerName);
        return state != null ? state.boneTransforms.get(boneName) : null;
    }

    public boolean isBoneReset(String playerName, String boneName) {
        PlayerModelState state = playerModelStates.get(playerName);
        return state != null && state.boneResetFlags.getOrDefault(boneName, false);
    }

    public void detectConflicts() {
        if (!conflictDetectionEnabled) return;

        conflictingMods.clear();

        detectModConflicts();
    }

    private void detectModConflicts() {
        // 修复BUG: 原方法体为空，detectConflicts() 实际不检测任何冲突，hasConflicts() 恒为 false；
        // 改为通过 FabricLoader 检测已加载的玩家模型冲突模组
        FabricLoader loader = FabricLoader.getInstance();
        for (String modId : KNOWN_PLAYER_MODEL_CONFLICTS) {
            if (loader.isModLoaded(modId)) {
                conflictingMods.add(modId);
                if (allowWarn()) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] 检测到玩家模型冲突模组: {}", modId);
                }
            }
        }
    }

    public boolean hasConflicts() {
        return !conflictingMods.isEmpty();
    }

    public Set<String> getConflictingMods() {
        // 修复BUG: 原代码直接返回内部可变集合，调用方可任意修改破坏封装性；改为返回不可修改视图
        return Collections.unmodifiableSet(conflictingMods);
    }

    public void setModelPriority(String modId, ModelPriority priority) {
        modelPriorities.put(modId, priority);
    }

    public ModelPriority getModelPriority(String modId) {
        return modelPriorities.getOrDefault(modId, defaultPriority);
    }

    public void setDefaultPriority(ModelPriority priority) {
        this.defaultPriority = priority;
    }

    public boolean shouldApplyCustomModel(String playerName) {
        if (!enabled) return false;

        PlayerModelState state = playerModelStates.get(playerName);
        return state != null && state.currentModel != null;
    }

    public JemFileParser.CemModel getCurrentModel(String playerName) {
        PlayerModelState state = playerModelStates.get(playerName);
        return state != null ? state.currentModel : null;
    }

    public void clearPlayerModel(String playerName) {
        playerModelStates.remove(playerName);
    }

    public void clearAllPlayerModels() {
        playerModelStates.clear();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAllPlayerModels();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setConflictDetectionEnabled(boolean enabled) {
        this.conflictDetectionEnabled = enabled;
    }

    public boolean isConflictDetectionEnabled() {
        return conflictDetectionEnabled;
    }

    public int getActivePlayerCount() {
        return playerModelStates.size();
    }

    private PlayerModelState getOrCreateState(String playerName) {
        PlayerModelState state = playerModelStates.get(playerName);
        if (state != null) {
            return state;
        }
        // 达到上限时拒绝创建新状态，避免内存无界增长
        if (playerModelStates.size() >= MAX_PLAYER_STATES) {
            return null;
        }
        return playerModelStates.computeIfAbsent(playerName, PlayerModelState::new);
    }

    public enum ModelPriority {
        VANILLA,
        CUSTOM,
        MOD,
        HIGHEST
    }

    public static class PlayerModelState {
        public final String playerName;
        // 修复BUG: 原字段非volatile，applyCustomModel写入后其他线程读取shouldApplyCustomModel/getCurrentModel可能看不到最新值（可见性问题）
        public volatile JemFileParser.CemModel currentModel;
        public final Map<String, BoneTransform> boneTransforms;
        public final Map<String, Boolean> boneResetFlags;

        public PlayerModelState(String playerName) {
            this.playerName = playerName;
            this.boneTransforms = new ConcurrentHashMap<>();
            this.boneResetFlags = new ConcurrentHashMap<>();
        }
    }

    public static class BoneTransform {
        public float[] rotation = new float[VECTOR_SIZE];
        public float[] translation = new float[VECTOR_SIZE];
        public float[] scale = new float[]{DEFAULT_SCALE, DEFAULT_SCALE, DEFAULT_SCALE};

        public BoneTransform() {}

        public BoneTransform(float[] rotation, float[] translation, float[] scale) {
            // 修复BUG: 原代码未判空，传入null数组时arraycopy会抛NullPointerException
            copyVec3Safe(this.rotation, rotation);
            copyVec3Safe(this.translation, translation);
            copyVec3Safe(this.scale, scale);
            // 确保scale默认值正确，传入的null或短数组位置用DEFAULT_SCALE填充
            if (scale == null || scale.length < VECTOR_SIZE) {
                for (int i = scale != null ? scale.length : 0; i < VECTOR_SIZE; i++) {
                    this.scale[i] = DEFAULT_SCALE;
                }
            }
        }

        public void reset() {
            Arrays.fill(rotation, 0);
            Arrays.fill(translation, 0);
            Arrays.fill(scale, DEFAULT_SCALE);
        }

        // 安全复制向量数据，处理null和长度不足的情况
        private static void copyVec3Safe(float[] dest, float[] src) {
            if (src == null || dest == null) return;
            int length = Math.min(src.length, Math.min(dest.length, VECTOR_SIZE));
            System.arraycopy(src, 0, dest, 0, length);
        }
    }
}