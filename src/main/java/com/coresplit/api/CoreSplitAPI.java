package com.coresplit.api;

import com.coresplit.CoreSplitMod;
import com.coresplit.core.ModuleManager;

import java.util.function.Consumer;

public final class CoreSplitAPI {

    public static final String API_VERSION = "2.0.0";

    private CoreSplitAPI() {}

    public static String getApiVersion() {
        return API_VERSION;
    }

    public interface PBRTextureRegistry {
        void registerPBRMaterial(String blockId, PBRProperties properties);
        PBRProperties getPBRMaterial(String blockId);
        boolean hasPBRMaterial(String blockId);
    }

    public interface EntityTextureRegistry {
        void registerEntityTextureVariant(String entityId, String variantName, String texturePath);
        void registerEntitySoundMapping(String entityId, String textureVariant, String soundId);
    }

    public interface ModelRegistry {
        void registerCustomModel(String entityId, String modelPath);
        void registerAnimationOverride(String entityId, String animationName, String animationPath);
    }

    public interface ChunkEngineAPI {
        int getCurrentChunkLoadRate();
        int getPendingChunkCount();
        void setChunkLoadPriority(int priority);
        void preloadChunks(int x, int z, int radius);
    }

    public record PBRProperties(
            float metallic,
            float roughness,
            float ao,
            float normalStrength,
            float emission,
            String normalMap,
            String specularMap,
            String emissionMap
    ) {
        public static PBRProperties defaultProperties() {
            return new PBRProperties(0, 1, 1, 1, 0, null, null, null);
        }
    }

    public record ResourceRegistration(
            String id,
            String location,
            ResourceType type,
            String description
    ) {}

    public enum ResourceType {
        TEXTURE,
        MODEL,
        SOUND,
        ANIMATION,
        SHADER
    }

    public static class APIManager {
        
        private static volatile PBRTextureRegistry pbrTextureRegistry;
        private static volatile EntityTextureRegistry entityTextureRegistry;
        private static volatile ModelRegistry modelRegistry;
        private static volatile ChunkEngineAPI chunkEngineAPI;

        public static void setPBRTextureRegistry(PBRTextureRegistry registry) {
            pbrTextureRegistry = registry;
        }

        public static void setEntityTextureRegistry(EntityTextureRegistry registry) {
            entityTextureRegistry = registry;
        }

        public static void setModelRegistry(ModelRegistry registry) {
            modelRegistry = registry;
        }

        public static void setChunkEngineAPI(ChunkEngineAPI api) {
            chunkEngineAPI = api;
        }

        public static PBRTextureRegistry getPBRTextureRegistry() {
            return pbrTextureRegistry;
        }

        public static EntityTextureRegistry getEntityTextureRegistry() {
            return entityTextureRegistry;
        }

        public static ModelRegistry getModelRegistry() {
            return modelRegistry;
        }

        public static ChunkEngineAPI getChunkEngineAPI() {
            return chunkEngineAPI;
        }
    }

    public static void registerPBRMaterial(String blockId, PBRProperties properties) {
        // 修复BUG: 原代码未校验入参，blockId或properties为null会传递给registry实现导致NPE
        if (blockId == null || blockId.isEmpty() || properties == null) {
            CoreSplitMod.LOGGER.warn("[CoreSplit API] Invalid arguments for registerPBRMaterial: blockId={}, properties={}", blockId, properties);
            return;
        }
        PBRTextureRegistry registry = APIManager.getPBRTextureRegistry();
        if (registry != null) {
            registry.registerPBRMaterial(blockId, properties);
            CoreSplitMod.LOGGER.info("[CoreSplit API] Registered PBR material for {}", blockId);
        }
    }

    public static PBRProperties getPBRMaterial(String blockId) {
        PBRTextureRegistry registry = APIManager.getPBRTextureRegistry();
        return registry != null ? registry.getPBRMaterial(blockId) : PBRProperties.defaultProperties();
    }

    public static void registerEntityTextureVariant(String entityId, String variantName, String texturePath) {
        // 修复BUG: 原代码未校验入参，null/空字符串会传递给registry实现导致NPE
        if (entityId == null || entityId.isEmpty() || variantName == null || variantName.isEmpty()
                || texturePath == null || texturePath.isEmpty()) {
            CoreSplitMod.LOGGER.warn("[CoreSplit API] Invalid arguments for registerEntityTextureVariant");
            return;
        }
        EntityTextureRegistry registry = APIManager.getEntityTextureRegistry();
        if (registry != null) {
            registry.registerEntityTextureVariant(entityId, variantName, texturePath);
            CoreSplitMod.LOGGER.info("[CoreSplit API] Registered texture variant {} for entity {}", variantName, entityId);
        }
    }

    public static void registerEntitySoundMapping(String entityId, String textureVariant, String soundId) {
        // 修复BUG: 原代码未校验入参，null会传递给registry实现导致NPE
        if (entityId == null || entityId.isEmpty() || textureVariant == null || textureVariant.isEmpty()
                || soundId == null || soundId.isEmpty()) {
            CoreSplitMod.LOGGER.warn("[CoreSplit API] Invalid arguments for registerEntitySoundMapping");
            return;
        }
        EntityTextureRegistry registry = APIManager.getEntityTextureRegistry();
        if (registry != null) {
            registry.registerEntitySoundMapping(entityId, textureVariant, soundId);
            CoreSplitMod.LOGGER.info("[CoreSplit API] Registered sound mapping: {} -> {} -> {}", entityId, textureVariant, soundId);
        }
    }

    public static void registerCustomModel(String entityId, String modelPath) {
        // 修复BUG: 原代码未校验入参，null会传递给registry实现导致NPE
        if (entityId == null || entityId.isEmpty() || modelPath == null || modelPath.isEmpty()) {
            CoreSplitMod.LOGGER.warn("[CoreSplit API] Invalid arguments for registerCustomModel");
            return;
        }
        ModelRegistry registry = APIManager.getModelRegistry();
        if (registry != null) {
            registry.registerCustomModel(entityId, modelPath);
            CoreSplitMod.LOGGER.info("[CoreSplit API] Registered custom model for {}", entityId);
        }
    }

    public static void registerAnimationOverride(String entityId, String animationName, String animationPath) {
        // 修复BUG: 原代码未校验入参，null会传递给registry实现导致NPE
        if (entityId == null || entityId.isEmpty() || animationName == null || animationName.isEmpty()
                || animationPath == null || animationPath.isEmpty()) {
            CoreSplitMod.LOGGER.warn("[CoreSplit API] Invalid arguments for registerAnimationOverride");
            return;
        }
        ModelRegistry registry = APIManager.getModelRegistry();
        if (registry != null) {
            registry.registerAnimationOverride(entityId, animationName, animationPath);
            CoreSplitMod.LOGGER.info("[CoreSplit API] Registered animation override {} for {}", animationName, entityId);
        }
    }

    public static void onAPILoaded(Consumer<CoreSplitAPI> callback) {
        // 修复BUG: 原代码未判空callback，null会导致callback.accept()抛NPE
        if (callback == null) {
            CoreSplitMod.LOGGER.warn("[CoreSplit API] onAPILoaded callback is null, skipping");
            return;
        }
        try {
            callback.accept(new CoreSplitAPI());
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit API] Error during API callback", e);
        }
    }

    public static boolean isModuleEnabled(String moduleId) {
        ModuleManager manager = ModuleManager.getInstance();
        ModuleManager.Module module = manager.getModule(moduleId);
        return module != null && module.isEnabled();
    }

    public static void enableModule(String moduleId) {
        ModuleManager.getInstance().setModuleEnabled(moduleId, true);
    }

    public static void disableModule(String moduleId) {
        ModuleManager.getInstance().setModuleEnabled(moduleId, false);
    }

    public static void applyPreset(String presetId) {
        ModuleManager.getInstance().applyPreset(presetId);
    }

    public static void registerModule(String id, String name, String description, ModuleManager.ModuleCategory category) {
        ModuleManager.getInstance().registerModule(id, name, description, category);
    }

    public static void registerPreset(ModuleManager.Preset preset) {
        ModuleManager.getInstance().registerPreset(preset);
    }
}