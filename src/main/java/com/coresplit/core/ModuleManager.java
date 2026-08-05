package com.coresplit.core;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModuleManager {

    public enum ModuleCategory {
        PERFORMANCE("Performance"),
        RENDERING("Rendering"),
        SOUND("Sound"),
        COMPATIBILITY("Compatibility"),
        UTILITY("Utility");

        private final String displayName;
        ModuleCategory(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    public enum ModuleState {
        ENABLED,
        DISABLED,
        DISABLED_BY_DEPENDENCY,
        ERROR
    }

    public enum ConflictLevel {
        NONE,
        WARNING,
        SEVERE
    }

    public static class Module {
        public final String id;
        public final String name;
        public final String description;
        public final ModuleCategory category;
        public final Set<String> dependencies = new HashSet<>();
        public final Set<String> conflicts = new HashSet<>();
        public final List<Runnable> onEnable = new CopyOnWriteArrayList<>();
        public final List<Runnable> onDisable = new CopyOnWriteArrayList<>();
        
        private volatile ModuleState state = ModuleState.DISABLED;
        private volatile boolean initialized = false;

        public Module(String id, String name, String description, ModuleCategory category) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.category = category;
        }

        public Module dependsOn(String... moduleIds) {
            Collections.addAll(dependencies, moduleIds);
            return this;
        }

        public Module conflictsWith(String... moduleIds) {
            Collections.addAll(conflicts, moduleIds);
            return this;
        }

        public Module onEnable(Runnable callback) {
            onEnable.add(callback);
            return this;
        }

        public Module onDisable(Runnable callback) {
            onDisable.add(callback);
            return this;
        }

        public ModuleState getState() { return state; }
        public boolean isEnabled() { return state == ModuleState.ENABLED; }

        void setState(ModuleState newState) {
            if (state == newState) return;

            ModuleState oldState = this.state;
            this.state = newState;

            if (newState == ModuleState.ENABLED && oldState != ModuleState.ENABLED) {
                initialized = true;
                boolean hasError = false;
                for (Runnable callback : onEnable) {
                    try {
                        callback.run();
                    } catch (Exception e) {
                        CoreSplitMod.LOGGER.error("[CoreSplit] Failed to enable module: {}", id, e);
                        hasError = true;
                    }
                }
                // 修复BUG: 原代码在循环中修改state导致后续回调仍执行但状态已变，改为循环结束后统一设置
                if (hasError) {
                    this.state = ModuleState.ERROR;
                }
            } else if (newState != ModuleState.ENABLED && oldState == ModuleState.ENABLED) {
                for (Runnable callback : onDisable) {
                    try {
                        callback.run();
                    } catch (Exception e) {
                        CoreSplitMod.LOGGER.error("[CoreSplit] Failed to disable module: {}", id, e);
                    }
                }
            }
        }

        public boolean isInitialized() { return initialized; }
    }

    public static class Preset {
        public final String id;
        public final String name;
        public final String description;
        public final Map<String, Boolean> moduleStates;

        public Preset(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.moduleStates = new LinkedHashMap<>();
        }

        public Preset setModule(String moduleId, boolean enabled) {
            moduleStates.put(moduleId, enabled);
            return this;
        }
    }

    private static final Path MODULE_CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit_modules.toml");

    private final Map<String, Module> modules = new ConcurrentHashMap<>();
    private final Map<String, Preset> presets = new ConcurrentHashMap<>();
    private final Map<String, ConflictLevel> moduleConflicts = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    private static ModuleManager instance;

    public static synchronized ModuleManager getInstance() {
        if (instance == null) {
            instance = new ModuleManager();
        }
        return instance;
    }

    private ModuleManager() {}

    public Module registerModule(String id, String name, String description, ModuleCategory category) {
        Module module = new Module(id, name, description, category);
        modules.put(id, module);
        return module;
    }

    public void registerPreset(Preset preset) {
        presets.put(preset.id, preset);
    }

    public void load() {
        if (loaded) return;

        initializeDefaultModules();
        initializeDefaultPresets();
        
        loadConfig();
        resolveDependencies();
        checkConflicts();
        
        loaded = true;
        CoreSplitMod.LOGGER.info("[CoreSplit] ModuleManager loaded with {} modules", modules.size());
    }

    private void initializeDefaultModules() {
        registerModule("chunk_engine", "Chunk Engine", "Multi-threaded chunk loading and generation", ModuleCategory.PERFORMANCE)
                .onEnable(() -> {
                    try {
                        Class.forName("com.coresplit.chunk.ChunkEngine");
                        CoreSplitMod.LOGGER.info("[CoreSplit] Chunk Engine enabled");
                    } catch (Exception e) {
                        CoreSplitMod.LOGGER.error("[CoreSplit] Failed to enable Chunk Engine", e);
                    }
                });

        registerModule("texture_system", "Texture System", "Advanced texture caching and PBR support", ModuleCategory.RENDERING);

        registerModule("model_system", "Model System", "Enhanced entity model loading and animation", ModuleCategory.RENDERING)
                .dependsOn("texture_system");

        registerModule("sound_system", "Sound System", "Dynamic sound variant system", ModuleCategory.SOUND)
                .dependsOn("model_system");

        registerModule("performance_governor", "Performance Governor", "Automatic quality adjustment", ModuleCategory.PERFORMANCE);

        registerModule("task_scheduler", "Task Scheduler", "Advanced multi-threaded task scheduling", ModuleCategory.PERFORMANCE);

        registerModule("overlay", "Overlay", "Performance monitoring overlay", ModuleCategory.UTILITY);

        registerModule("compatibility_mode", "Compatibility Mode", "Conflict detection and resolution", ModuleCategory.COMPATIBILITY);

        // 新增高负载场景优化模块
        registerModule("explosion_optimization", "Explosion Optimization",
                        "Async explosion processing with frame batching and particle limiting", ModuleCategory.PERFORMANCE)
                .dependsOn("task_scheduler");

        registerModule("ai_optimization", "AI Optimization",
                        "Distance-based AI throttle, path caching and sharing", ModuleCategory.PERFORMANCE)
                .dependsOn("chunk_engine");

        registerModule("memory_optimization", "Memory Optimization",
                        "Object pooling, resource eviction and entity data unloading", ModuleCategory.PERFORMANCE);

        registerModule("item_render_optimization", "Item Render Optimization",
                        "Frustum culling, distance LOD and animation throttling for dropped items", ModuleCategory.RENDERING);
    }

    private void initializeDefaultPresets() {
        Preset performance = new Preset("performance", "Performance Priority", "Maximize FPS and reduce lag");
        performance.setModule("chunk_engine", true);
        performance.setModule("task_scheduler", true);
        performance.setModule("performance_governor", true);
        performance.setModule("texture_system", false);
        performance.setModule("model_system", false);
        performance.setModule("sound_system", false);
        performance.setModule("overlay", true);
        performance.setModule("compatibility_mode", true);
        performance.setModule("explosion_optimization", true);
        performance.setModule("ai_optimization", true);
        performance.setModule("memory_optimization", true);
        performance.setModule("item_render_optimization", true);
        registerPreset(performance);

        Preset quality = new Preset("quality", "Quality Priority", "Enhanced visuals and sounds");
        quality.setModule("chunk_engine", true);
        quality.setModule("task_scheduler", true);
        quality.setModule("performance_governor", false);
        quality.setModule("texture_system", true);
        quality.setModule("model_system", true);
        quality.setModule("sound_system", true);
        quality.setModule("overlay", true);
        quality.setModule("compatibility_mode", true);
        quality.setModule("explosion_optimization", false);
        quality.setModule("ai_optimization", false);
        quality.setModule("memory_optimization", false);
        quality.setModule("item_render_optimization", false);
        registerPreset(quality);

        Preset balanced = new Preset("balanced", "Balanced", "Optimal balance between performance and quality");
        balanced.setModule("chunk_engine", true);
        balanced.setModule("task_scheduler", true);
        balanced.setModule("performance_governor", true);
        balanced.setModule("texture_system", true);
        balanced.setModule("model_system", true);
        balanced.setModule("sound_system", true);
        balanced.setModule("overlay", true);
        balanced.setModule("compatibility_mode", true);
        balanced.setModule("explosion_optimization", true);
        balanced.setModule("ai_optimization", true);
        balanced.setModule("memory_optimization", true);
        balanced.setModule("item_render_optimization", true);
        registerPreset(balanced);

        Preset minimal = new Preset("minimal", "Minimal", "Only essential performance improvements");
        minimal.setModule("chunk_engine", true);
        minimal.setModule("task_scheduler", false);
        minimal.setModule("performance_governor", false);
        minimal.setModule("texture_system", false);
        minimal.setModule("model_system", false);
        minimal.setModule("sound_system", false);
        minimal.setModule("overlay", false);
        minimal.setModule("compatibility_mode", true);
        minimal.setModule("explosion_optimization", false);
        minimal.setModule("ai_optimization", false);
        minimal.setModule("memory_optimization", false);
        minimal.setModule("item_render_optimization", false);
        registerPreset(minimal);
    }

    private void loadConfig() {
        if (!Files.exists(MODULE_CONFIG_PATH)) {
            applyPreset("balanced");
            saveConfig();
            return;
        }

        try {
            String content = Files.readString(MODULE_CONFIG_PATH);
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) continue;
                
                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();
                
                if (key.startsWith("module.")) {
                    String moduleId = key.substring(7);
                    Module module = modules.get(moduleId);
                    if (module != null) {
                        module.setState(Boolean.parseBoolean(value) ? ModuleState.ENABLED : ModuleState.DISABLED);
                    }
                }
            }
        } catch (IOException e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load module config, using defaults", e);
            applyPreset("balanced");
        }
    }

    public void saveConfig() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# CoreSplit Module Configuration\n");
            sb.append("# Automatically generated - do not edit manually\n\n");
            
            for (Module module : modules.values()) {
                sb.append("module.").append(module.id).append(" = ").append(module.isEnabled()).append("\n");
            }
            
            Files.writeString(MODULE_CONFIG_PATH, sb.toString());
        } catch (IOException e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to save module config", e);
        }
    }

    private void resolveDependencies() {
        boolean changed = true;
        int iterations = 0;
        final int maxIterations = 10;

        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;

            for (Module module : modules.values()) {
                if (module.state == ModuleState.ENABLED) {
                    for (String depId : module.dependencies) {
                        Module dep = modules.get(depId);
                        if (dep != null && dep.state != ModuleState.ENABLED) {
                            dep.setState(ModuleState.ENABLED);
                            changed = true;
                        }
                    }
                }
            }

            for (Module module : modules.values()) {
                if (module.state == ModuleState.ENABLED) {
                    for (String depId : module.dependencies) {
                        Module dep = modules.get(depId);
                        if (dep != null && dep.state == ModuleState.DISABLED) {
                            module.setState(ModuleState.DISABLED_BY_DEPENDENCY);
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private void checkConflicts() {
        moduleConflicts.clear();
        
        for (Module module : modules.values()) {
            if (!module.isEnabled()) continue;
            
            for (String conflictId : module.conflicts) {
                Module conflict = modules.get(conflictId);
                if (conflict != null && conflict.isEnabled()) {
                    moduleConflicts.put(module.id, ConflictLevel.SEVERE);
                    moduleConflicts.put(conflictId, ConflictLevel.SEVERE);
                }
            }
        }
    }

    public boolean setModuleEnabled(String moduleId, boolean enabled) {
        Module module = modules.get(moduleId);
        if (module == null) return false;

        if (enabled) {
            module.setState(ModuleState.ENABLED);
            resolveDependencies();
        } else {
            module.setState(ModuleState.DISABLED);
            resolveDependencies();
        }
        
        checkConflicts();
        saveConfig();
        return true;
    }

    public boolean applyPreset(String presetId) {
        Preset preset = presets.get(presetId);
        if (preset == null) return false;

        for (Map.Entry<String, Boolean> entry : preset.moduleStates.entrySet()) {
            Module module = modules.get(entry.getKey());
            if (module != null) {
                module.setState(entry.getValue() ? ModuleState.ENABLED : ModuleState.DISABLED);
            }
        }

        resolveDependencies();
        checkConflicts();
        saveConfig();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] Applied preset: {}", preset.name);
        return true;
    }

    public Module getModule(String moduleId) {
        return modules.get(moduleId);
    }

    public Collection<Module> getModules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    public Collection<Module> getModulesByCategory(ModuleCategory category) {
        return modules.values().stream()
                .filter(m -> m.category == category)
                .toList();
    }

    public Collection<Preset> getPresets() {
        return Collections.unmodifiableCollection(presets.values());
    }

    public Preset getPreset(String presetId) {
        return presets.get(presetId);
    }

    public ConflictLevel getModuleConflictLevel(String moduleId) {
        return moduleConflicts.getOrDefault(moduleId, ConflictLevel.NONE);
    }

    public int getEnabledModuleCount() {
        return (int) modules.values().stream().filter(Module::isEnabled).count();
    }

    public void resetToDefaults() {
        applyPreset("balanced");
    }
}