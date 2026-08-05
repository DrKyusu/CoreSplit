package com.coresplit.sound;

import com.coresplit.CoreSplitMod;
import com.coresplit.model.AnimationController;
import com.coresplit.model.CemModelLoader;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class EntitySoundSystem {

    private final SoundPropertiesParser soundPropertiesParser = new SoundPropertiesParser();
    private final SoundVariantManager soundVariantManager = new SoundVariantManager();
    private final SoundAnimationIntegrator soundAnimationIntegrator = new SoundAnimationIntegrator();
    private final SoundDrivenVisualManager soundDrivenVisualManager = new SoundDrivenVisualManager();
    
    private final Map<String, EntitySoundState> entityStates = new ConcurrentHashMap<>();
    // 修复BUG: 原代码使用ArrayList在多线程迭代时可能ConcurrentModificationException，改用CopyOnWriteArrayList
    private final List<SoundSystemListener> listeners = new CopyOnWriteArrayList<>();
    // 修复BUG: scheduleDelayedSound 原每次 new Thread() 创建匿名非守护线程，无法管理且阻碍 JVM 退出；
    // 改用共享单线程调度器（守护线程），支持优雅关闭
    private final ScheduledExecutorService soundScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "coresplit-sound-scheduler");
        t.setDaemon(true);
        return t;
    });

    private boolean initialized = false;
    private boolean enabled = true;
    
    private CemModelLoader modelLoader;
    private AnimationController animationController;

    public void initialize(CemModelLoader modelLoader, AnimationController animationController) {
        if (initialized) return;
        
        this.modelLoader = modelLoader;
        this.animationController = animationController;
        
        registerAnimationFunctions();
        
        initialized = true;
        enabled = true;
        
        CoreSplitMod.LOGGER.info("[CoreSplit] Entity Sound System (ESF) initialized");
    }

    private void registerAnimationFunctions() {
        if (animationController != null) {
            animationController.registerFunction("playsound", this::handlePlaySoundCommand);
            animationController.registerFunction("stopsound", this::handleStopSoundCommand);
            animationController.registerFunction("fadesound", this::handleFadeSoundCommand);
            
            animationController.addAnimationEventListener(this::onAnimationEvent);
        }
    }

    private void handlePlaySoundCommand(String entityId, String[] args) {
        if (args.length < 1) return;
        
        String soundId = args[0];
        String condition = args.length > 1 ? args[1] : "";
        float x = args.length > 2 ? parseFloat(args[2], 0) : 0;
        float y = args.length > 3 ? parseFloat(args[3], 0) : 0;
        float z = args.length > 4 ? parseFloat(args[4], 0) : 0;
        float attenuation = args.length > 5 ? parseFloat(args[5], 16) : 16;
        
        playSound(entityId, soundId, condition, x, y, z, attenuation);
    }

    private void handleStopSoundCommand(String entityId, String[] args) {
        if (args.length < 1) return;
        stopSound(entityId, args[0]);
    }

    private void handleFadeSoundCommand(String entityId, String[] args) {
        if (args.length < 2) return;
        
        String soundId = args[0];
        float targetVolume = parseFloat(args[1], 0);
        float duration = args.length > 2 ? parseFloat(args[2], 1) : 1;
        
        soundAnimationIntegrator.fadeSound(entityId, soundId, targetVolume, duration);
    }

    private void onAnimationEvent(String entityId, String eventName) {
        // 修复BUG: 原代码未判空，eventName为null或长度不足时会抛NPE或StringIndexOutOfBoundsException
        if (eventName == null || eventName.isEmpty()) return;
        
        if (eventName.endsWith(".start") && eventName.length() > 6) {
            String soundId = eventName.substring(0, eventName.length() - 6);
            soundDrivenVisualManager.onSoundStarted(entityId, soundId);
        } else if (eventName.endsWith(".end") && eventName.length() > 4) {
            String soundId = eventName.substring(0, eventName.length() - 4);
            soundDrivenVisualManager.onSoundStopped(entityId, soundId);
        }
    }

    public void loadEntitySoundProperties(String entityId, InputStream inputStream) {
        if (!enabled) return;
        
        SoundPropertiesParser.SoundProperties properties = SoundPropertiesParser.parse(inputStream);
        
        soundVariantManager.loadEntityProperties(entityId, properties);
        soundDrivenVisualManager.loadPlayingSoundConfigs(entityId, properties);
        
        EntitySoundState state = entityStates.computeIfAbsent(entityId, EntitySoundState::new);
        state.properties = properties;
        
        CoreSplitMod.LOGGER.debug("[CoreSplit] Loaded sound properties for entity: {}", entityId);
    }

    public void loadEntitySoundProperties(String entityId, Map<String, String> rawProperties) {
        if (!enabled) return;

        SoundPropertiesParser.SoundProperties properties = SoundPropertiesParser.parse(rawProperties);

        soundVariantManager.loadEntityProperties(entityId, properties);
        soundDrivenVisualManager.loadPlayingSoundConfigs(entityId, properties);
        // 注：此处不向 entityStates 写入。entityStates 按实体实例跟踪运行时状态
        // （lastSoundPlayed/soundPlayCount 等），而本方法传入的 entityId 通常是实体类型 ID
        // （如 minecraft:zombie），与 playSound 传入的实例 ID（如 zombie_1）不同；
        // 类型级配置由 soundVariantManager/soundDrivenVisualManager 各自缓存即可。
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
        
        if (!soundVariantManager.shouldPlaySound(entityId, soundId, condition)) {
            return;
        }
        
        SoundPropertiesParser.SoundDefinition soundDef = soundVariantManager.getSoundDefinition(entityId, soundId);
        
        float finalVolume = 1.0f;
        float finalPitch = 1.0f;
        
        if (soundDef != null) {
            finalVolume = soundDef.volume * soundVariantManager.getVolumeMultiplier();
            finalPitch = soundDef.pitch * soundVariantManager.getPitchMultiplier();
            attenuation = soundDef.distance > 0 ? soundDef.distance : attenuation;
            
            if (soundDef.delay > 0) {
                scheduleDelayedSound(entityId, soundId, condition, x, y, z, attenuation, soundDef.delay);
                return;
            }
        }
        
        soundAnimationIntegrator.playSound(entityId, soundId, condition, x, y, z, attenuation);
        
        soundDrivenVisualManager.onSoundStarted(entityId, soundId);
        
        EntitySoundState state = entityStates.computeIfAbsent(entityId, EntitySoundState::new);
        state.lastSoundPlayed = soundId;
        state.lastSoundTime = System.currentTimeMillis();
        state.soundPlayCount.incrementAndGet();
        
        fireSoundPlayed(entityId, soundId, condition, x, y, z, attenuation);
    }

    private void scheduleDelayedSound(String entityId, String soundId, String condition,
                                      float x, float y, float z, float attenuation, int delay) {
        // 修复BUG: 原代码每次 new Thread() 创建匿名非守护线程，无法管理且阻碍 JVM 退出；
        // 改用共享单线程调度器（守护线程）调度延迟播放，支持优雅关闭
        soundScheduler.schedule(() -> playSound(entityId, soundId, condition, x, y, z, attenuation),
                delay, TimeUnit.MILLISECONDS);
    }

    public void stopSound(String entityId, String soundId) {
        soundAnimationIntegrator.stopSound(entityId, soundId);
        soundDrivenVisualManager.onSoundStopped(entityId, soundId);
        
        fireSoundStopped(entityId, soundId);
    }

    public void stopAllSounds(String entityId) {
        soundAnimationIntegrator.stopAllSounds(entityId);
        soundDrivenVisualManager.clearEntityState(entityId);
        
        fireAllSoundsStopped(entityId);
    }

    public void stopAllSounds() {
        soundAnimationIntegrator.stopAllSounds();
        soundDrivenVisualManager.clearAllStates();
    }

    public void updateEntityTexture(String entityId, String textureVariant) {
        soundVariantManager.updateEntityTexture(entityId, textureVariant);
        
        EntitySoundState state = entityStates.computeIfAbsent(entityId, EntitySoundState::new);
        state.currentTexture = textureVariant;
        
        fireTextureChanged(entityId, textureVariant);
    }

    public void updateEntityModel(String entityId, String modelVariant) {
        soundVariantManager.updateEntityModel(entityId, modelVariant);
        
        EntitySoundState state = entityStates.computeIfAbsent(entityId, EntitySoundState::new);
        state.currentModel = modelVariant;
        
        fireModelChanged(entityId, modelVariant);
    }

    public void update(float deltaTime) {
        if (!enabled || !initialized) return;
        
        soundAnimationIntegrator.updateSounds(deltaTime);
        soundDrivenVisualManager.update(deltaTime);
        
        cleanupInactiveEntities();
    }

    private void cleanupInactiveEntities() {
        // 修复BUG: 原代码使用全局getActiveSoundCount()判断所有实体，逻辑错误
        // 应该针对每个实体检查是否有活跃声音
        long threshold = System.currentTimeMillis() - 300000;
        
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, EntitySoundState> entry : entityStates.entrySet()) {
            EntitySoundState state = entry.getValue();
            // 只有当实体超过5分钟未播放声音 且 该实体当前无活跃声音时才清理
            if (state.lastSoundTime < threshold && 
                !soundAnimationIntegrator.isEntityPlayingSound(entry.getKey())) {
                toRemove.add(entry.getKey());
            }
        }
        
        for (String entityId : toRemove) {
            entityStates.remove(entityId);
            soundVariantManager.clearEntityState(entityId);
            soundDrivenVisualManager.clearEntityState(entityId);
        }
    }

    public void onResourcePackReload() {
        CoreSplitMod.LOGGER.info("[CoreSplit] Resource pack reload detected, resetting sound system");
        
        stopAllSounds();
        
        soundVariantManager.reloadProperties();
        soundDrivenVisualManager.reloadConfigs();
        
        entityStates.clear();
        
        fireResourcePackReload();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] Sound system reset completed");
    }

    public void addListener(SoundSystemListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(SoundSystemListener listener) {
        listeners.remove(listener);
    }

    private void fireSoundPlayed(String entityId, String soundId, String condition,
                                  float x, float y, float z, float attenuation) {
        for (SoundSystemListener listener : listeners) {
            try {
                listener.onSoundPlayed(entityId, soundId, condition, x, y, z, attenuation);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error in sound played listener", e);
            }
        }
    }

    private void fireSoundStopped(String entityId, String soundId) {
        for (SoundSystemListener listener : listeners) {
            try {
                listener.onSoundStopped(entityId, soundId);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error in sound stopped listener", e);
            }
        }
    }

    private void fireAllSoundsStopped(String entityId) {
        for (SoundSystemListener listener : listeners) {
            try {
                listener.onAllSoundsStopped(entityId);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error in all sounds stopped listener", e);
            }
        }
    }

    private void fireTextureChanged(String entityId, String textureVariant) {
        for (SoundSystemListener listener : listeners) {
            try {
                listener.onTextureChanged(entityId, textureVariant);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error in texture changed listener", e);
            }
        }
    }

    private void fireModelChanged(String entityId, String modelVariant) {
        for (SoundSystemListener listener : listeners) {
            try {
                listener.onModelChanged(entityId, modelVariant);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error in model changed listener", e);
            }
        }
    }

    private void fireResourcePackReload() {
        for (SoundSystemListener listener : listeners) {
            try {
                listener.onResourcePackReload();
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error in resource pack reload listener", e);
            }
        }
    }

    private float parseFloat(String value, float defaultValue) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public SoundVariantManager getSoundVariantManager() {
        return soundVariantManager;
    }

    public SoundAnimationIntegrator getSoundAnimationIntegrator() {
        return soundAnimationIntegrator;
    }

    public SoundDrivenVisualManager getSoundDrivenVisualManager() {
        return soundDrivenVisualManager;
    }

    public EntitySoundState getEntityState(String entityId) {
        return entityStates.get(entityId);
    }

    public int getEntityCount() {
        return entityStates.size();
    }

    public int getActiveSoundCount() {
        return soundAnimationIntegrator.getActiveSoundCount();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        
        soundVariantManager.setEnabled(enabled);
        soundAnimationIntegrator.setEnabled(enabled);
        soundDrivenVisualManager.setEnabled(enabled);
        
        if (!enabled) {
            stopAllSounds();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void shutdown() {
        setEnabled(false);
        initialized = false;

        // 修复BUG: 关闭调度器，避免线程泄漏
        soundScheduler.shutdownNow();
        listeners.clear();
        entityStates.clear();

        CoreSplitMod.LOGGER.info("[CoreSplit] Entity Sound System (ESF) shutdown");
    }

    public static class EntitySoundState {
        public String entityId;
        public SoundPropertiesParser.SoundProperties properties;
        public String currentTexture = "";
        public String currentModel = "";
        public String lastSoundPlayed = "";
        public long lastSoundTime = 0;
        // 修复BUG: 原为普通 int，playSound 并发调用时 ++ 非原子会丢失计数；改为 AtomicInteger
        public final AtomicInteger soundPlayCount = new AtomicInteger(0);

        public EntitySoundState(String entityId) {
            this.entityId = entityId;
        }

        public void reset() {
            lastSoundPlayed = "";
            lastSoundTime = 0;
            soundPlayCount.set(0);
        }
    }

    public interface SoundSystemListener {
        void onSoundPlayed(String entityId, String soundId, String condition,
                           float x, float y, float z, float attenuation);
        
        void onSoundStopped(String entityId, String soundId);
        
        void onAllSoundsStopped(String entityId);
        
        void onTextureChanged(String entityId, String textureVariant);
        
        void onModelChanged(String entityId, String modelVariant);
        
        void onResourcePackReload();
    }
}