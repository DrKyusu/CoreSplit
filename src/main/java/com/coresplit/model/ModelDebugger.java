package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelDebugger {

    // 修复BUG: 原 entityModelInfoCache 无大小限制，压力测试下数千实体持续写入会导致内存泄漏；
    // 改用 access-order LinkedHashMap + removeEldestEntry 实现 LRU 驱逐，上限 256 条。
    // synchronizedMap 保证 capture（渲染/业务线程写）与 get（查询读）的线程安全；
    // 调试信息非高频访问，synchronized 锁开销可接受。
    private static final int MAX_CACHE_SIZE = 256;

    private final Map<String, EntityModelInfo> entityModelInfoCache;

    private volatile boolean enabled = true;
    private volatile boolean verboseMode = false;

    public ModelDebugger() {
        // accessOrder=true：get/put 都会将条目移到链表尾（最近使用），驱逐时移除链表头（最久未用）
        this.entityModelInfoCache = Collections.synchronizedMap(
                new LinkedHashMap<String, EntityModelInfo>(MAX_CACHE_SIZE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, EntityModelInfo> eldest) {
                        return size() > MAX_CACHE_SIZE;
                    }
                });

        CoreSplitMod.LOGGER.info("[CoreSplit] ModelDebugger initialized");
    }

    public void captureEntityModelInfo(String entityId, JemFileParser.CemModel model, String entityClassName) {
        if (!enabled) return;

        EntityModelInfo info = new EntityModelInfo();
        info.entityId = entityId;
        info.entityClassName = entityClassName;
        info.timestamp = System.currentTimeMillis();

        if (model != null) {
            info.modelName = model.name;
            info.texturePath = model.texturePath;
            info.boneCount = model.bones.size();
            info.cubeCount = model.rootCubes.size();
            info.animationCount = model.animations.size();
            info.mirrored = model.mirrored;
            info.shade = model.shade;
            info.alpha = model.alpha;

            for (JemFileParser.Bone bone : model.bones) {
                BoneDebugInfo boneInfo = new BoneDebugInfo();
                boneInfo.name = bone.name;
                boneInfo.parent = bone.parent;
                boneInfo.cubeCount = bone.cubes.size();
                boneInfo.childCount = bone.children.size();
                boneInfo.pivot = copyVec3(bone.pivot);
                boneInfo.rotation = copyVec3(bone.rotation);
                boneInfo.translation = copyVec3(bone.translation);
                boneInfo.scale = copyVec3(bone.scale);
                boneInfo.influence = bone.influence;
                info.bones.add(boneInfo);
            }

            for (JemFileParser.Animation anim : model.animations) {
                AnimationDebugInfo animInfo = new AnimationDebugInfo();
                animInfo.name = anim.name;
                animInfo.targetBone = anim.targetBone;
                animInfo.type = anim.type.name();
                animInfo.duration = anim.duration;
                animInfo.frameCount = anim.frames.size();
                animInfo.loop = anim.loop;
                animInfo.interpolate = anim.interpolate;
                animInfo.condition = anim.condition;
                info.animations.add(animInfo);
            }
        }

        entityModelInfoCache.put(entityId, info);

        if (verboseMode) {
            logEntityModelInfo(info);
        }
    }

    public void logEntityModelInfo(String entityId) {
        EntityModelInfo info = entityModelInfoCache.get(entityId);
        if (info != null) {
            logEntityModelInfo(info);
        }
    }

    // 修复BUG: Bone 的数组字段虽初始化为非 null，但解析器 parseFloatArray 可能覆盖为 null，
    // 日志中直接 bone.pivot[0] 访问会 NPE。提供 null 安全的格式化助手。
    private static String formatVec3(float[] v) {
        if (v == null) return "null";
        int len = Math.min(v.length, 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(", ");
            sb.append(v[i]);
        }
        return sb.toString();
    }

    // 安全复制数组，防止外部修改破坏调试快照
    private static float[] copyVec3(float[] v) {
        return v != null ? v.clone() : null;
    }

    private void logEntityModelInfo(EntityModelInfo info) {
        CoreSplitMod.LOGGER.info("[CoreSplit] ===== Entity Model Debug Info ===== ");
        CoreSplitMod.LOGGER.info("[CoreSplit] Entity ID: {}", info.entityId);
        CoreSplitMod.LOGGER.info("[CoreSplit] Entity Class: {}", info.entityClassName);
        CoreSplitMod.LOGGER.info("[CoreSplit] Timestamp: {}", info.timestamp);

        if (!info.modelName.isEmpty()) {
            CoreSplitMod.LOGGER.info("[CoreSplit] Model Name: {}", info.modelName);
            CoreSplitMod.LOGGER.info("[CoreSplit] Texture Path: {}", info.texturePath);
            CoreSplitMod.LOGGER.info("[CoreSplit] Bone Count: {}", info.boneCount);
            CoreSplitMod.LOGGER.info("[CoreSplit] Root Cube Count: {}", info.cubeCount);
            CoreSplitMod.LOGGER.info("[CoreSplit] Animation Count: {}", info.animationCount);
            CoreSplitMod.LOGGER.info("[CoreSplit] Mirrored: {}", info.mirrored);
            CoreSplitMod.LOGGER.info("[CoreSplit] Shade: {}", info.shade);
            CoreSplitMod.LOGGER.info("[CoreSplit] Alpha: {}", info.alpha);

            if (!info.bones.isEmpty()) {
                CoreSplitMod.LOGGER.info("[CoreSplit] --- Bones --- ");
                for (BoneDebugInfo bone : info.bones) {
                    CoreSplitMod.LOGGER.info("[CoreSplit]   Bone: {} (parent: {}, cubes: {}, children: {})",
                            bone.name, bone.parent, bone.cubeCount, bone.childCount);
                    CoreSplitMod.LOGGER.info("[CoreSplit]     Pivot: [{}]", formatVec3(bone.pivot));
                    CoreSplitMod.LOGGER.info("[CoreSplit]     Rotation: [{}]", formatVec3(bone.rotation));
                    CoreSplitMod.LOGGER.info("[CoreSplit]     Translation: [{}]", formatVec3(bone.translation));
                    CoreSplitMod.LOGGER.info("[CoreSplit]     Scale: [{}]", formatVec3(bone.scale));
                    CoreSplitMod.LOGGER.info("[CoreSplit]     Influence: {}", bone.influence);
                }
            }

            if (!info.animations.isEmpty()) {
                CoreSplitMod.LOGGER.info("[CoreSplit] --- Animations --- ");
                for (AnimationDebugInfo anim : info.animations) {
                    CoreSplitMod.LOGGER.info("[CoreSplit]   Animation: {} (target: {}, type: {})",
                            anim.name, anim.targetBone, anim.type);
                    CoreSplitMod.LOGGER.info("[CoreSplit]     Duration: {}ms, Frames: {}", anim.duration, anim.frameCount);
                    CoreSplitMod.LOGGER.info("[CoreSplit]     Loop: {}, Interpolate: {}", anim.loop, anim.interpolate);
                    if (!anim.condition.isEmpty()) {
                        CoreSplitMod.LOGGER.info("[CoreSplit]     Condition: {}", anim.condition);
                    }
                }
            }
        }

        CoreSplitMod.LOGGER.info("[CoreSplit] ===== End Debug Info ===== ");
    }

    public EntityModelInfo getEntityModelInfo(String entityId) {
        return entityModelInfoCache.get(entityId);
    }

    public void clearEntityModelInfo(String entityId) {
        entityModelInfoCache.remove(entityId);
    }

    public void clearAllEntityModelInfo() {
        entityModelInfoCache.clear();
    }

    public void logAllEntityModelInfo() {
        CoreSplitMod.LOGGER.info("[CoreSplit] ===== All Entity Model Debug Info ===== ");
        CoreSplitMod.LOGGER.info("[CoreSplit] Total entities tracked: {}", entityModelInfoCache.size());

        for (EntityModelInfo info : entityModelInfoCache.values()) {
            logEntityModelInfo(info);
        }

        CoreSplitMod.LOGGER.info("[CoreSplit] ===== End All Debug Info ===== ");
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setVerboseMode(boolean verbose) {
        this.verboseMode = verbose;
    }

    public boolean isVerboseMode() {
        return verboseMode;
    }

    public int getTrackedEntityCount() {
        return entityModelInfoCache.size();
    }

    public static class EntityModelInfo {
        public String entityId;
        public String entityClassName;
        public long timestamp;

        public String modelName = "";
        public String texturePath = "";
        public int boneCount = 0;
        public int cubeCount = 0;
        public int animationCount = 0;
        public boolean mirrored = false;
        public boolean shade = true;
        public float alpha = 1.0f;

        public List<BoneDebugInfo> bones = new ArrayList<>();
        public List<AnimationDebugInfo> animations = new ArrayList<>();
    }

    public static class BoneDebugInfo {
        public String name;
        public String parent;
        public int cubeCount;
        public int childCount;
        public float[] pivot;
        public float[] rotation;
        public float[] translation;
        public float[] scale;
        public float influence;
    }

    public static class AnimationDebugInfo {
        public String name;
        public String targetBone;
        public String type;
        public int duration;
        public int frameCount;
        public boolean loop;
        public boolean interpolate;
        public String condition;
    }
}