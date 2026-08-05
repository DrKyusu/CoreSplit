package com.coresplit.sound;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class SoundAnimationIntegrator {

    private static final int MAX_SIMULTANEOUS_SOUNDS = 256;
    private static final float DEFAULT_FADE_DURATION = 0.5f;
    
    private final Map<String, ActiveSound> activeSounds = new ConcurrentHashMap<>();
    // 修复BUG: 原值为 ArrayList 非线程安全，addEvent 并发调用与 processEventQueue 迭代移除会抛 ConcurrentModificationException；
    // 改为 ConcurrentLinkedQueue 保证线程安全且迭代弱一致
    private final Map<String, Queue<SoundEvent>> soundEventQueue = new ConcurrentHashMap<>();
    private final Map<String, Float> entityDistances = new ConcurrentHashMap<>();
    
    private boolean enabled = true;
    private boolean fadeEnabled = true;
    private boolean spatialAudioEnabled = true;
    private float fadeDuration = DEFAULT_FADE_DURATION;
    
    // 修复BUG: 原为普通 int，generateUniqueSoundId 的 ++soundCounter 非原子，并发会产生重复 ID 导致 activeSounds 键覆盖；
    // 改为 AtomicInteger 保证唯一性（上一轮修复因并行编辑冲突未生效，本次补正）
    private final AtomicInteger soundCounter = new AtomicInteger(0);

    public void processAnimationCommand(String entityId, String command, String[] args) {
        if (!enabled) return;
        
        if (!command.equalsIgnoreCase("playsound")) {
            return;
        }
        
        if (args.length < 1) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] playsound command requires at least 1 argument: {}", command);
            return;
        }
        
        String soundId = args[0];
        String condition = args.length > 1 ? args[1] : "";
        float x = args.length > 2 ? parseFloat(args[2], 0) : 0;
        float y = args.length > 3 ? parseFloat(args[3], 0) : 0;
        float z = args.length > 4 ? parseFloat(args[4], 0) : 0;
        float attenuation = args.length > 5 ? parseFloat(args[5], 16) : 16;
        
        playSound(entityId, soundId, condition, x, y, z, attenuation);
    }

    public void playSound(String entityId, String soundId) {
        playSound(entityId, soundId, "", 0, 0, 0, 16);
    }

    public void playSound(String entityId, String soundId, String condition) {
        playSound(entityId, soundId, condition, 0, 0, 0, 16);
    }

    public void playSound(String entityId, String soundId, String condition, 
                          float x, float y, float z, float attenuation) {
        if (!enabled) return;
        
        if (activeSounds.size() >= MAX_SIMULTANEOUS_SOUNDS) {
            evictOldestSound();
        }
        
        String uniqueSoundId = generateUniqueSoundId(entityId, soundId);
        
        ActiveSound activeSound = new ActiveSound();
        activeSound.entityId = entityId;
        activeSound.soundId = soundId;
        activeSound.condition = condition;
        activeSound.x = x;
        activeSound.y = y;
        activeSound.z = z;
        activeSound.attenuation = attenuation;
        activeSound.startTime = System.currentTimeMillis();
        activeSound.state = SoundState.FADING_IN;
        activeSound.volume = 0;
        activeSound.targetVolume = 1.0f;
        activeSound.pitch = 1.0f;
        
        activeSounds.put(uniqueSoundId, activeSound);
        
        triggerEvent(entityId, soundId + ".start");
        
        CoreSplitMod.LOGGER.debug("[CoreSplit] playsound triggered for entity {}: sound={}, condition={}, position=({},{},{}), attenuation={}",
                entityId, soundId, condition, x, y, z, attenuation);
    }

    public void updateSounds(float deltaTime) {
        if (!enabled) return;
        
        List<String> finishedSounds = new ArrayList<>();
        
        for (Map.Entry<String, ActiveSound> entry : activeSounds.entrySet()) {
            ActiveSound sound = entry.getValue();
            updateSoundState(sound, deltaTime);
            
            if (sound.state == SoundState.FINISHED) {
                finishedSounds.add(entry.getKey());
            }
        }
        
        for (String soundId : finishedSounds) {
            ActiveSound finished = activeSounds.remove(soundId);
            if (finished != null) {
                triggerEvent(finished.entityId, finished.soundId + ".end");
            }
        }
        
        processEventQueue();
    }

    private void updateSoundState(ActiveSound sound, float deltaTime) {
        switch (sound.state) {
            case FADING_IN:
                if (fadeEnabled) {
                    sound.volume += deltaTime / fadeDuration;
                    if (sound.volume >= sound.targetVolume) {
                        sound.volume = sound.targetVolume;
                        sound.state = SoundState.PLAYING;
                    }
                } else {
                    sound.volume = sound.targetVolume;
                    sound.state = SoundState.PLAYING;
                }
                break;
                
            case PLAYING:
                // 修复BUG: 原代码if/else两个分支逻辑完全相同，合并为单一逻辑
                {
                    long elapsed = System.currentTimeMillis() - sound.startTime;
                    if (sound.duration > 0 && elapsed > sound.duration * 1000) {
                        sound.state = SoundState.FADING_OUT;
                    }
                }
                break;
                
            case FADING_OUT:
                if (fadeEnabled) {
                    sound.volume -= deltaTime / fadeDuration;
                    if (sound.volume <= sound.targetVolume || sound.volume <= 0) {
                        sound.volume = Math.max(0, sound.targetVolume);
                        sound.state = sound.volume <= 0 ? SoundState.FINISHED : SoundState.PLAYING;
                    }
                } else {
                    sound.volume = sound.targetVolume;
                    sound.state = sound.volume <= 0 ? SoundState.FINISHED : SoundState.PLAYING;
                }
                break;
        }
    }

    private void evictOldestSound() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<String, ActiveSound> entry : activeSounds.entrySet()) {
            if (entry.getValue().startTime < oldestTime) {
                oldestTime = entry.getValue().startTime;
                oldestKey = entry.getKey();
            }
        }
        
        if (oldestKey != null) {
            ActiveSound evicted = activeSounds.remove(oldestKey);
            if (evicted != null && evicted.state != SoundState.FINISHED) {
                triggerEvent(evicted.entityId, evicted.soundId + ".evicted");
            }
        }
    }

    public void stopSound(String entityId, String soundId) {
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, ActiveSound> entry : activeSounds.entrySet()) {
            ActiveSound sound = entry.getValue();
            if (sound != null && sound.entityId != null && sound.soundId != null) {
                if (sound.entityId.equals(entityId) && sound.soundId.equals(soundId)) {
                    toRemove.add(entry.getKey());
                }
            }
        }
        
        for (String key : toRemove) {
            activeSounds.remove(key);
        }
    }

    public void stopAllSounds(String entityId) {
        // 修复BUG: 原代码未对entry.getValue()和entityId做null检查
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, ActiveSound> entry : activeSounds.entrySet()) {
            ActiveSound sound = entry.getValue();
            if (sound != null && sound.entityId != null && sound.entityId.equals(entityId)) {
                toRemove.add(entry.getKey());
            }
        }
        
        for (String key : toRemove) {
            activeSounds.remove(key);
        }
    }

    public void stopAllSounds() {
        activeSounds.clear();
    }

    public void addEvent(String entityId, SoundEvent event) {
        soundEventQueue.computeIfAbsent(entityId, k -> new ConcurrentLinkedQueue<>()).add(event);
    }

    private void processEventQueue() {
        List<SoundEvent> eventsToExecute = new ArrayList<>();
        
        for (Map.Entry<String, Queue<SoundEvent>> entry : soundEventQueue.entrySet()) {
            Queue<SoundEvent> events = entry.getValue();
            Iterator<SoundEvent> iterator = events.iterator();
            
            while (iterator.hasNext()) {
                SoundEvent event = iterator.next();
                if (System.currentTimeMillis() >= event.triggerTime) {
                    eventsToExecute.add(event);
                    iterator.remove();
                }
            }
        }
        
        for (SoundEvent event : eventsToExecute) {
            executeEvent(event);
        }
    }

    private void executeEvent(SoundEvent event) {
        switch (event.type) {
            case PLAY:
                playSound(event.entityId, event.soundId, event.condition, 
                        event.x, event.y, event.z, event.attenuation);
                break;
            case STOP:
                stopSound(event.entityId, event.soundId);
                break;
            case FADE:
                fadeSound(event.entityId, event.soundId, event.targetVolume, event.duration);
                break;
        }
    }

    public void fadeSound(String entityId, String soundId, float targetVolume, float duration) {
        // 修复BUG: 原代码声明了toRemove列表但从未添加元素，是死代码，已移除
        for (Map.Entry<String, ActiveSound> entry : activeSounds.entrySet()) {
            ActiveSound sound = entry.getValue();
            if (sound != null && sound.entityId != null && sound.soundId != null) {
                if (sound.entityId.equals(entityId) && sound.soundId.equals(soundId)) {
                    sound.targetVolume = Math.max(0, Math.min(2, targetVolume));
                    if (targetVolume <= 0) {
                        sound.state = SoundState.FADING_OUT;
                    } else {
                        sound.state = targetVolume > sound.volume ? SoundState.FADING_IN : SoundState.FADING_OUT;
                    }
                }
            }
        }
    }

    public void overlaySound(String entityId, String baseSoundId, String overlaySoundId) {
        // 修复BUG: 原代码未对entry.getValue()做null检查，可能NPE
        String baseKey = null;
        ActiveSound baseSound = null;
        for (Map.Entry<String, ActiveSound> entry : activeSounds.entrySet()) {
            ActiveSound sound = entry.getValue();
            if (sound != null && sound.entityId != null && sound.soundId != null &&
                sound.entityId.equals(entityId) && sound.soundId.equals(baseSoundId)) {
                baseKey = entry.getKey();
                baseSound = sound;
                break;
            }
        }
        
        if (baseKey != null && baseSound != null) {
            ActiveSound overlay = new ActiveSound();
            overlay.entityId = entityId;
            overlay.soundId = overlaySoundId;
            overlay.x = baseSound.x;
            overlay.y = baseSound.y;
            overlay.z = baseSound.z;
            overlay.attenuation = baseSound.attenuation;
            overlay.startTime = System.currentTimeMillis();
            overlay.state = SoundState.PLAYING;
            overlay.volume = 0.5f;
            overlay.targetVolume = 0.5f;
            overlay.pitch = baseSound.pitch;
            
            activeSounds.put(generateUniqueSoundId(entityId, overlaySoundId), overlay);
        }
    }

    private void triggerEvent(String entityId, String eventName) {
        CoreSplitMod.LOGGER.debug("[CoreSplit] Sound event triggered: {} for entity {}", eventName, entityId);
    }

    private String generateUniqueSoundId(String entityId, String soundId) {
        return entityId + ":" + soundId + ":" + soundCounter.incrementAndGet();
    }

    private float parseFloat(String value, float defaultValue) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int getActiveSoundCount() {
        return activeSounds.size();
    }

    public int getEventQueueSize(String entityId) {
        Queue<SoundEvent> events = soundEventQueue.get(entityId);
        return events != null ? events.size() : 0;
    }

    public boolean isSoundPlaying(String entityId, String soundId) {
        // 修复BUG: 原代码未对sound.entityId/soundId做null检查
        for (ActiveSound sound : activeSounds.values()) {
            if (sound != null && sound.entityId != null && sound.soundId != null &&
                sound.entityId.equals(entityId) && sound.soundId.equals(soundId)) {
                return sound.state != SoundState.FINISHED;
            }
        }
        return false;
    }

    /**
     * 检查指定实体是否有任何活跃声音在播放
     * 新增方法：用于实体级别的活跃声音检查，替代全局getActiveSoundCount()的误用
     */
    public boolean isEntityPlayingSound(String entityId) {
        for (ActiveSound sound : activeSounds.values()) {
            if (sound != null && sound.entityId != null && sound.entityId.equals(entityId)) {
                if (sound.state != SoundState.FINISHED) {
                    return true;
                }
            }
        }
        return false;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            stopAllSounds();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setFadeEnabled(boolean fadeEnabled) {
        this.fadeEnabled = fadeEnabled;
    }

    public boolean isFadeEnabled() {
        return fadeEnabled;
    }

    public void setFadeDuration(float duration) {
        this.fadeDuration = Math.max(0.1f, Math.min(5.0f, duration));
    }

    public float getFadeDuration() {
        return fadeDuration;
    }

    public void setSpatialAudioEnabled(boolean enabled) {
        this.spatialAudioEnabled = enabled;
    }

    public boolean isSpatialAudioEnabled() {
        return spatialAudioEnabled;
    }

    public void updateEntityDistance(String entityId, float distance) {
        entityDistances.put(entityId, distance);
    }

    public float getEntityDistance(String entityId) {
        Float distance = entityDistances.get(entityId);
        return distance != null ? distance : 0;
    }

    public static class ActiveSound {
        public String entityId;
        public String soundId;
        public String condition;
        public float x, y, z;
        public float attenuation;
        public long startTime;
        public SoundState state;
        public float volume;
        public float targetVolume;
        public float pitch;
        public boolean loop;
        public float duration;
        public String category;
    }

    public static class SoundEvent {
        public String entityId;
        public String soundId;
        public String condition;
        public String eventName;
        public SoundEventType type;
        public float x, y, z;
        public float attenuation;
        public float targetVolume;
        public float duration;
        public long triggerTime;
        
        public SoundEvent() {
            this.type = SoundEventType.PLAY;
        }
    }

    public enum SoundState {
        FADING_IN,
        PLAYING,
        FADING_OUT,
        FINISHED
    }

    public enum SoundEventType {
        PLAY,
        STOP,
        FADE
    }
}