package com.coresplit.sound;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SoundVariantManager {

    private static final int MAX_CACHE_SIZE = 500;
    
    private final Map<String, SoundPropertiesParser.SoundProperties> propertiesCache = new ConcurrentHashMap<>();
    private final Map<String, String> entityTextureSoundMap = new ConcurrentHashMap<>();
    private final Map<String, String> entityModelSoundMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> textureVariantSoundIds = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> modelVariantSoundIds = new ConcurrentHashMap<>();
    private final Map<String, SoundVariantState> entitySoundStates = new ConcurrentHashMap<>();
    private final Queue<String> cacheEvictionQueue = new ConcurrentLinkedQueue<>();
    
    private boolean enabled = true;
    private float volumeMultiplier = 1.0f;
    private float pitchMultiplier = 1.0f;

    public void loadEntityProperties(String entityId, SoundPropertiesParser.SoundProperties properties) {
        if (!enabled) return;
        
        propertiesCache.put(entityId, properties);
        
        if (!cacheEvictionQueue.contains(entityId)) {
            cacheEvictionQueue.offer(entityId);
        }
        
        if (cacheEvictionQueue.size() > MAX_CACHE_SIZE) {
            String oldest = cacheEvictionQueue.poll();
            if (oldest != null) {
                propertiesCache.remove(oldest);
            }
        }
        
        buildVariantMappings(entityId, properties);
        
        CoreSplitMod.LOGGER.debug("[CoreSplit] Loaded sound properties for entity: {} (sounds: {}, variants: {}, playingSounds: {})",
                entityId, properties.sounds.size(), properties.variants.size(), properties.playingSounds.size());
    }

    private void buildVariantMappings(String entityId, SoundPropertiesParser.SoundProperties properties) {
        textureVariantSoundIds.remove(entityId);
        modelVariantSoundIds.remove(entityId);
        
        Set<String> textureSounds = new HashSet<>();
        Set<String> modelSounds = new HashSet<>();
        
        for (SoundPropertiesParser.SoundDefinition sound : properties.sounds.values()) {
            if (!sound.textureVariant.isEmpty()) {
                textureSounds.add(sound.id);
                entityTextureSoundMap.put(entityId + ":" + sound.textureVariant, sound.id);
            }
            if (!sound.modelVariant.isEmpty()) {
                modelSounds.add(sound.id);
                entityModelSoundMap.put(entityId + ":" + sound.modelVariant, sound.id);
            }
        }
        
        for (SoundPropertiesParser.SoundVariant variant : properties.variants.values()) {
            if (!variant.textureVariant.isEmpty()) {
                for (String soundId : variant.soundIds) {
                    textureSounds.add(soundId);
                    entityTextureSoundMap.put(entityId + ":" + variant.textureVariant, soundId);
                }
            }
        }
        
        textureVariantSoundIds.put(entityId, textureSounds);
        modelVariantSoundIds.put(entityId, modelSounds);
    }

    public String getSoundForTexture(String entityId, String textureVariant) {
        // 修复BUG: 原代码未判textureVariant为null，拼接key和equalsIgnoreCase时会NPE
        if (!enabled || entityId == null || textureVariant == null) return "";

        String key = entityId + ":" + textureVariant;
        String soundId = entityTextureSoundMap.get(key);

        if (soundId != null) {
            return soundId;
        }

        SoundPropertiesParser.SoundProperties properties = propertiesCache.get(entityId);

        if (properties == null) {
            for (String cacheKey : propertiesCache.keySet()) {
                // 修复BUG: propertiesCache.get(cacheKey)可能返回null，需判空
                SoundPropertiesParser.SoundProperties cached = propertiesCache.get(cacheKey);
                if (cached == null) continue;

                if (entityId.startsWith(cacheKey) || cacheKey.startsWith(entityId)) {
                    properties = cached;
                    break;
                }
                String entityName = entityId.replaceAll("_\\d+$", "");
                String cacheEntityName = cacheKey.replaceAll("_\\d+$", "");
                if (entityName.equals(cacheEntityName)) {
                    properties = cached;
                    break;
                }
                if (cacheKey.contains(":") && entityId.contains("_")) {
                    String[] cacheParts = cacheKey.split(":");
                    String[] entityParts = entityId.split("_");
                    if (cacheParts.length > 1 && entityParts.length > 0) {
                        String cacheEntity = cacheParts[1];
                        String testEntity = entityParts[0];
                        if (cacheEntity.equalsIgnoreCase(testEntity)) {
                            properties = cached;
                            break;
                        }
                    }
                }
            }
        }

        if (properties != null) {
            for (SoundPropertiesParser.SoundVariant variant : properties.variants.values()) {
                if (textureVariant.equalsIgnoreCase(variant.textureVariant)) {
                    String randomSound = variant.getRandomSound();
                    if (!randomSound.isEmpty()) {
                        entityTextureSoundMap.put(key, randomSound);
                        return randomSound;
                    }
                }
            }

            for (SoundPropertiesParser.SoundDefinition sound : properties.sounds.values()) {
                if (textureVariant.equalsIgnoreCase(sound.textureVariant)) {
                    entityTextureSoundMap.put(key, sound.id);
                    return sound.id;
                }
            }
        }

        return getDefaultSound(entityId);
    }

    public String getSoundForModel(String entityId, String modelVariant) {
        // 修复BUG: 原代码未判 modelVariant/entityId 为 null，modelVariant.equalsIgnoreCase() 会抛 NPE；
        // 与 getSoundForTexture 保持一致的空值保护
        if (!enabled || entityId == null || modelVariant == null) return "";

        String key = entityId + ":" + modelVariant;
        String soundId = entityModelSoundMap.get(key);
        
        if (soundId != null) {
            return soundId;
        }
        
        SoundPropertiesParser.SoundProperties properties = propertiesCache.get(entityId);
        if (properties != null) {
            for (SoundPropertiesParser.SoundDefinition sound : properties.sounds.values()) {
                if (modelVariant.equalsIgnoreCase(sound.modelVariant)) {
                    entityModelSoundMap.put(key, sound.id);
                    return sound.id;
                }
            }
        }
        
        return getDefaultSound(entityId);
    }

    private String getDefaultSound(String entityId) {
        SoundPropertiesParser.SoundProperties properties = propertiesCache.get(entityId);
        if (properties != null && !properties.sounds.isEmpty()) {
            return properties.sounds.values().iterator().next().id;
        }
        return "";
    }

    public SoundPropertiesParser.SoundDefinition getSoundDefinition(String entityId, String soundId) {
        SoundPropertiesParser.SoundProperties properties = propertiesCache.get(entityId);
        if (properties != null) {
            return properties.sounds.get(soundId);
        }
        return null;
    }

    public void updateEntityTexture(String entityId, String newTextureVariant) {
        if (!enabled) return;
        
        String oldSoundId = getCurrentSoundId(entityId);
        String newSoundId = getSoundForTexture(entityId, newTextureVariant);
        
        if (!oldSoundId.equals(newSoundId)) {
            updateEntitySoundState(entityId, newSoundId);
            CoreSplitMod.LOGGER.debug("[CoreSplit] Entity {} texture changed to {}, sound updated to {}", 
                    entityId, newTextureVariant, newSoundId);
        }
    }

    public void updateEntityModel(String entityId, String newModelVariant) {
        if (!enabled) return;
        
        String oldSoundId = getCurrentSoundId(entityId);
        String newSoundId = getSoundForModel(entityId, newModelVariant);
        
        if (!oldSoundId.equals(newSoundId)) {
            updateEntitySoundState(entityId, newSoundId);
            CoreSplitMod.LOGGER.debug("[CoreSplit] Entity {} model changed to {}, sound updated to {}", 
                    entityId, newModelVariant, newSoundId);
        }
    }

    private void updateEntitySoundState(String entityId, String soundId) {
        SoundVariantState state = entitySoundStates.computeIfAbsent(entityId, SoundVariantState::new);
        state.currentSoundId = soundId;
        state.lastUpdateTime = System.currentTimeMillis();
    }

    private String getCurrentSoundId(String entityId) {
        SoundVariantState state = entitySoundStates.get(entityId);
        return state != null ? state.currentSoundId : "";
    }

    public SoundVariantState getEntitySoundState(String entityId) {
        return entitySoundStates.computeIfAbsent(entityId, SoundVariantState::new);
    }

    public boolean shouldPlaySound(String entityId, String soundId, String condition) {
        SoundPropertiesParser.SoundDefinition sound = getSoundDefinition(entityId, soundId);
        
        // 修复BUG: 原代码propertiesCache.get(key)可能返回null，调用.sounds会NPE
        if (sound == null) {
            for (String key : propertiesCache.keySet()) {
                SoundPropertiesParser.SoundProperties props = propertiesCache.get(key);
                if (props != null && props.sounds != null) {
                    sound = props.sounds.get(soundId);
                    if (sound != null) break;
                }
            }
        }
        
        if (sound == null) return true;
        
        if (sound.chance <= 0) {
            return false;
        }
        if (sound.chance < 1.0f && Math.random() >= sound.chance) {
            return false;
        }
        
        if (sound.hasCondition() && !sound.matchesCondition(condition)) {
            return false;
        }
        
        return true;
    }

    public void clearEntityState(String entityId) {
        entitySoundStates.remove(entityId);
    }

    public void clearAllStates() {
        entitySoundStates.clear();
        entityTextureSoundMap.clear();
        entityModelSoundMap.clear();
    }

    public void reloadProperties() {
        Set<String> entities = new HashSet<>(propertiesCache.keySet());
        propertiesCache.clear();
        entityTextureSoundMap.clear();
        entityModelSoundMap.clear();
        textureVariantSoundIds.clear();
        modelVariantSoundIds.clear();
        cacheEvictionQueue.clear();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] Sound variant properties reloaded, {} entities will be reloaded", entities.size());
    }

    public int getCachedPropertiesCount() {
        return propertiesCache.size();
    }

    public int getEntityStateCount() {
        return entitySoundStates.size();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setVolumeMultiplier(float multiplier) {
        this.volumeMultiplier = Math.max(0, Math.min(2, multiplier));
    }

    public float getVolumeMultiplier() {
        return volumeMultiplier;
    }

    public void setPitchMultiplier(float multiplier) {
        this.pitchMultiplier = Math.max(0.5f, Math.min(2.0f, multiplier));
    }

    public float getPitchMultiplier() {
        return pitchMultiplier;
    }

    public static class SoundVariantState {
        public String entityId;
        public String currentSoundId = "";
        public String currentTextureVariant = "";
        public String currentModelVariant = "";
        public long lastUpdateTime = 0;
        public int playCount = 0;
        
        public SoundVariantState(String entityId) {
            this.entityId = entityId;
        }
        
        public void incrementPlayCount() {
            playCount++;
        }
        
        public void resetPlayCount() {
            playCount = 0;
        }
    }
}