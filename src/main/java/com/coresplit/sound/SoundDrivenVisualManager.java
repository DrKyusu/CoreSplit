package com.coresplit.sound;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SoundDrivenVisualManager {

    private final Map<String, SoundVisualState> entityVisualStates = new ConcurrentHashMap<>();
    private final Map<String, SoundPropertiesParser.PlayingSoundState> playingSoundConfigs = new ConcurrentHashMap<>();
    // 修复BUG: 原值为 ArrayList 非线程安全，addVisualEffect 并发调用与 update 迭代移除会抛 ConcurrentModificationException；
    // 改为 ConcurrentLinkedQueue 保证线程安全且迭代弱一致
    private final Map<String, Queue<VisualEffect>> pendingEffects = new ConcurrentHashMap<>();
    
    private boolean enabled = true;
    private boolean probabilityEnabled = true;

    public void loadPlayingSoundConfigs(String entityId, SoundPropertiesParser.SoundProperties properties) {
        if (!enabled) return;
        
        for (Map.Entry<String, SoundPropertiesParser.PlayingSoundState> entry : properties.playingSounds.entrySet()) {
            playingSoundConfigs.put(entityId + ":" + entry.getKey(), entry.getValue());
        }
        
        CoreSplitMod.LOGGER.debug("[CoreSplit] Loaded {} playing sound configs for entity: {}", 
                properties.playingSounds.size(), entityId);
    }

    public void onSoundStarted(String entityId, String soundId) {
        if (!enabled) return;

        SoundPropertiesParser.PlayingSoundState config = lookupSoundConfig(entityId, soundId);
        if (config != null) {
            applyVisualChanges(entityId, config);
        }
    }

    public void onSoundStopped(String entityId, String soundId) {
        if (!enabled) return;

        SoundPropertiesParser.PlayingSoundState config = lookupSoundConfig(entityId, soundId);
        if (config != null) {
            revertVisualChanges(entityId, config);
        }
    }

    // PERF: 抽取 onSoundStarted/onSoundStopped 中完全重复的 4 步配置查找逻辑
    private SoundPropertiesParser.PlayingSoundState lookupSoundConfig(String entityId, String soundId) {
        // 1. 精确匹配 entityId:soundId
        SoundPropertiesParser.PlayingSoundState config = playingSoundConfigs.get(entityId + ":" + soundId);
        if (config != null) return config;

        // 2. 通配实体 entityId:*
        config = playingSoundConfigs.get(entityId + ":*");
        if (config != null) return config;

        // 3. 通配 soundId（后缀匹配 :soundId）
        for (String key : playingSoundConfigs.keySet()) {
            if (key.endsWith(":" + soundId)) {
                return playingSoundConfigs.get(key);
            }
        }

        // 4. 全通配 :*
        for (String key : playingSoundConfigs.keySet()) {
            if (key.endsWith(":*")) {
                return playingSoundConfigs.get(key);
            }
        }

        return null;
    }

    private void applyVisualChanges(String entityId, SoundPropertiesParser.PlayingSoundState config) {
        if (!probabilityEnabled || config.shouldApply()) {
            SoundVisualState state = entityVisualStates.computeIfAbsent(entityId, SoundVisualState::new);
            
            if (config.hasTextureChange()) {
                state.previousTexture = state.currentTexture;
                state.currentTexture = config.textureChange;
                triggerTextureChange(entityId, config.textureChange);
            }
            
            if (config.hasModelChange()) {
                state.previousModel = state.currentModel;
                state.currentModel = config.modelChange;
                triggerModelChange(entityId, config.modelChange);
            }
            
            if (config.transparency >= 0) {
                state.previousTransparency = state.currentTransparency;
                state.currentTransparency = config.transparency;
                triggerTransparencyChange(entityId, config.transparency);
            }
            
            if (config.scale != null && (config.scale[0] != 1 || config.scale[1] != 1 || config.scale[2] != 1)) {
                System.arraycopy(state.currentScale, 0, state.previousScale, 0, 3);
                System.arraycopy(config.scale, 0, state.currentScale, 0, 3);
                triggerScaleChange(entityId, config.scale);
            }
            
            if (config.color != 0) {
                state.previousColor = state.currentColor;
                state.currentColor = config.color;
                triggerColorChange(entityId, config.color);
            }
            
            state.activeSounds.add(config.id);
            
            CoreSplitMod.LOGGER.debug("[CoreSplit] Applied visual changes for sound {} on entity {}: texture={}, model={}, transparency={}, scale=({},{},{}), color={}",
                    config.id, entityId, config.textureChange, config.modelChange, config.transparency, 
                    config.scale != null ? config.scale[0] : 1, 
                    config.scale != null ? config.scale[1] : 1, 
                    config.scale != null ? config.scale[2] : 1, 
                    Integer.toHexString(config.color));
        }
    }

    private void revertVisualChanges(String entityId, SoundPropertiesParser.PlayingSoundState config) {
        SoundVisualState state = entityVisualStates.get(entityId);
        if (state == null) return;
        
        state.activeSounds.remove(config.id);
        
        if (config.textureChange != null && !config.textureChange.isEmpty()) {
            if (!state.previousTexture.isEmpty()) {
                triggerTextureChange(entityId, state.previousTexture);
                state.currentTexture = state.previousTexture;
            } else {
                state.currentTexture = "";
            }
            state.previousTexture = "";
        }
        
        if (config.modelChange != null && !config.modelChange.isEmpty()) {
            if (!state.previousModel.isEmpty()) {
                triggerModelChange(entityId, state.previousModel);
                state.currentModel = state.previousModel;
            } else {
                state.currentModel = "";
            }
            state.previousModel = "";
        }
        
        if (config.transparency >= 0) {
            if (state.previousTransparency >= 0) {
                triggerTransparencyChange(entityId, state.previousTransparency);
                state.currentTransparency = state.previousTransparency;
            } else {
                state.currentTransparency = -1;
            }
            state.previousTransparency = -1;
        }
        
        if (config.scale != null) {
            if (state.previousScale[0] != 1 || state.previousScale[1] != 1 || state.previousScale[2] != 1) {
                triggerScaleChange(entityId, state.previousScale);
                System.arraycopy(state.previousScale, 0, state.currentScale, 0, 3);
            } else {
                // PERF: 用 Arrays.fill 原地重置替代 new float[]{1,1,1}，避免每次声音停止时分配小数组增加 GC 压力
                Arrays.fill(state.currentScale, 1f);
            }
            // PERF: 同上，原地重置 previousScale 而非重新分配
            Arrays.fill(state.previousScale, 1f);
        }
        
        if (config.color != 0) {
            if (state.previousColor != 0) {
                triggerColorChange(entityId, state.previousColor);
                state.currentColor = state.previousColor;
            } else {
                state.currentColor = 0xFFFFFFFF;
            }
            state.previousColor = 0;
        }
        
        CoreSplitMod.LOGGER.debug("[CoreSplit] Reverted visual changes for sound {} on entity {}", config.id, entityId);
    }

    public void update(float deltaTime) {
        if (!enabled) return;
        
        List<String> processedEntities = new ArrayList<>();
        
        for (Map.Entry<String, Queue<VisualEffect>> entry : pendingEffects.entrySet()) {
            Queue<VisualEffect> effects = entry.getValue();
            Iterator<VisualEffect> iterator = effects.iterator();
            
            while (iterator.hasNext()) {
                VisualEffect effect = iterator.next();
                effect.update(deltaTime);
                
                if (effect.isComplete()) {
                    iterator.remove();
                }
            }
            
            if (effects.isEmpty()) {
                processedEntities.add(entry.getKey());
            }
        }
        
        for (String entityId : processedEntities) {
            pendingEffects.remove(entityId);
        }
    }

    public void addVisualEffect(String entityId, VisualEffect effect) {
        pendingEffects.computeIfAbsent(entityId, k -> new ConcurrentLinkedQueue<>()).add(effect);
    }

    public List<VisualEffect> getPendingEffects(String entityId) {
        // 修复BUG: 原代码返回内部集合引用，外部修改会破坏内部状态且迭代时并发修改抛 CME；
        // 返回快照副本保证封装性与线程安全
        Queue<VisualEffect> effects = pendingEffects.get(entityId);
        if (effects == null || effects.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(effects);
    }

    public void clearEntityState(String entityId) {
        entityVisualStates.remove(entityId);
        pendingEffects.remove(entityId);
    }

    public void clearAllStates() {
        entityVisualStates.clear();
        pendingEffects.clear();
    }

    public void reloadConfigs() {
        playingSoundConfigs.clear();
        clearAllStates();
        CoreSplitMod.LOGGER.info("[CoreSplit] Sound-driven visual configs reloaded");
    }

    public SoundVisualState getEntityVisualState(String entityId) {
        return entityVisualStates.computeIfAbsent(entityId, SoundVisualState::new);
    }

    public int getEntityStateCount() {
        return entityVisualStates.size();
    }

    public int getPendingEffectCount() {
        int count = 0;
        for (Queue<VisualEffect> effects : pendingEffects.values()) {
            count += effects.size();
        }
        return count;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAllStates();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setProbabilityEnabled(boolean enabled) {
        this.probabilityEnabled = enabled;
    }

    public boolean isProbabilityEnabled() {
        return probabilityEnabled;
    }

    public void triggerTextureChange(String entityId, String texturePath) {
        CoreSplitMod.LOGGER.debug("[CoreSplit] Texture change triggered for entity {}: {}", entityId, texturePath);
    }

    public void triggerModelChange(String entityId, String modelPath) {
        CoreSplitMod.LOGGER.debug("[CoreSplit] Model change triggered for entity {}: {}", entityId, modelPath);
    }

    public void triggerTransparencyChange(String entityId, int transparency) {
        CoreSplitMod.LOGGER.debug("[CoreSplit] Transparency change triggered for entity {}: {}", entityId, transparency);
    }

    public void triggerScaleChange(String entityId, float[] scale) {
        CoreSplitMod.LOGGER.debug("[CoreSplit] Scale change triggered for entity {}: ({}, {}, {})", 
                entityId, scale[0], scale[1], scale[2]);
    }

    public void triggerColorChange(String entityId, int color) {
        CoreSplitMod.LOGGER.debug("[CoreSplit] Color change triggered for entity {}: {}", entityId, Integer.toHexString(color));
    }

    public static class SoundVisualState {
        public String entityId;
        public String currentTexture = "";
        public String previousTexture = "";
        public String currentModel = "";
        public String previousModel = "";
        public int currentTransparency = -1;
        public int previousTransparency = -1;
        public float[] currentScale = new float[]{1, 1, 1};
        public float[] previousScale = new float[]{1, 1, 1};
        public int currentColor = 0;
        public int previousColor = 0;
        // 修复BUG: activeSounds 在 onSoundStarted/onSoundStopped 跨线程并发 add/remove，HashSet 非线程安全会丢数据或抛异常；改用并发键集
        public Set<String> activeSounds = ConcurrentHashMap.newKeySet();
        
        public SoundVisualState(String entityId) {
            this.entityId = entityId;
        }
        
        public boolean hasActiveChanges() {
            return !currentTexture.isEmpty() || 
                   !currentModel.isEmpty() || 
                   currentTransparency >= 0 || 
                   currentColor != 0 ||
                   (currentScale[0] != 1 || currentScale[1] != 1 || currentScale[2] != 1);
        }
        
        public void reset() {
            currentTexture = "";
            previousTexture = "";
            currentModel = "";
            previousModel = "";
            currentTransparency = -1;
            previousTransparency = -1;
            // PERF: 原地重置 scale 数组，避免 reset 时的小数组分配
            Arrays.fill(currentScale, 1f);
            Arrays.fill(previousScale, 1f);
            currentColor = 0;
            previousColor = 0;
            activeSounds.clear();
        }
    }

    public static class VisualEffect {
        public String entityId;
        public String effectType;
        public float progress = 0;
        public float duration = 1.0f;
        public float[] startValue;
        public float[] endValue;
        public float[] currentValue;
        public boolean completed = false;
        
        public VisualEffect(String entityId, String effectType, float duration, float[] startValue, float[] endValue) {
            this.entityId = entityId;
            this.effectType = effectType;
            // 修复BUG: duration<=0 会导致 update() 除零产生 NaN，归一化为最小正值
            this.duration = duration > 0 ? duration : 1.0f;
            // 修复BUG: 空值保护 + 防御性拷贝，避免外部修改数组破坏内部插值，且 startValue 为 null 时 NPE
            this.startValue = startValue != null ? startValue.clone() : new float[0];
            this.endValue = endValue != null ? endValue.clone() : new float[0];
            this.currentValue = Arrays.copyOf(this.startValue, this.startValue.length);
        }
        
        public void update(float deltaTime) {
            if (completed) return;
            // 修复BUG: duration 字段可被外部置为 0，除零产生 NaN 使 progress>=1.0f 恒为 false，效果永不完成
            float safeDuration = duration > 0 ? duration : 1.0f;
            progress += deltaTime / safeDuration;
            progress = Math.min(1.0f, progress);
            
            for (int i = 0; i < currentValue.length; i++) {
                currentValue[i] = startValue[i] + (endValue[i] - startValue[i]) * progress;
            }
            
            if (progress >= 1.0f) {
                completed = true;
            }
        }
        
        public boolean isComplete() {
            return completed;
        }
        
        public float[] getCurrentValue() {
            return currentValue;
        }
        
        public float getProgress() {
            return progress;
        }
    }
}