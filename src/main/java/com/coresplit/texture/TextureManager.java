package com.coresplit.texture;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public class TextureManager {

    private final TextureCache textureCache;
    private final ConcurrentHashMap<String, OptiFinePropertiesParser.EntityTextureProperties> propertiesCache;
    private final ConcurrentHashMap<String, List<OptiFinePropertiesParser.EntityTextureProperties>> entityTextures;
    
    private volatile boolean enabled = true;
    private volatile boolean emissiveEnabled = true;
    private volatile boolean skinEnhancementEnabled = true;

    public TextureManager() {
        this.textureCache = new TextureCache();
        this.propertiesCache = new ConcurrentHashMap<>();
        this.entityTextures = new ConcurrentHashMap<>();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] TextureManager initialized");
    }

    public CompletableFuture<TextureCache.CachedTexture> loadEntityTexture(String entityId, int variantIndex) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }

        List<OptiFinePropertiesParser.EntityTextureProperties> textures = entityTextures.get(entityId);
        if (textures == null || textures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        OptiFinePropertiesParser.EntityTextureProperties props = selectTextureVariant(textures, variantIndex);
        if (props == null) {
            return CompletableFuture.completedFuture(null);
        }

        String texturePath = props.getPrimaryTexture();
        if (texturePath.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return textureCache.getTexture(texturePath);
    }

    public CompletableFuture<TextureCache.CachedTexture> loadEmissiveTexture(String entityId, int variantIndex) {
        if (!enabled || !emissiveEnabled) {
            return CompletableFuture.completedFuture(null);
        }

        List<OptiFinePropertiesParser.EntityTextureProperties> textures = entityTextures.get(entityId);
        if (textures == null || textures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        OptiFinePropertiesParser.EntityTextureProperties props = selectTextureVariant(textures, variantIndex);
        if (props == null || !props.hasEmissiveTexture()) {
            return CompletableFuture.completedFuture(null);
        }

        String emissivePath = props.getPrimaryEmissiveTexture();
        if (emissivePath.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return textureCache.getTexture(emissivePath);
    }

    public OptiFinePropertiesParser.EntityTextureProperties getTextureProperties(String entityId) {
        List<OptiFinePropertiesParser.EntityTextureProperties> textures = entityTextures.get(entityId);
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        return selectTextureVariant(textures, 0);
    }

    private OptiFinePropertiesParser.EntityTextureProperties selectTextureVariant(
            List<OptiFinePropertiesParser.EntityTextureProperties> textures, int variantIndex) {
        
        if (textures.size() == 1) {
            return textures.get(0);
        }

        List<OptiFinePropertiesParser.EntityTextureProperties> matchingVariants = new ArrayList<>();
        List<OptiFinePropertiesParser.EntityTextureProperties> weightedVariants = new ArrayList<>();
        List<OptiFinePropertiesParser.EntityTextureProperties> defaultVariants = new ArrayList<>();

        for (OptiFinePropertiesParser.EntityTextureProperties props : textures) {
            if (props.variants.containsKey(variantIndex)) {
                matchingVariants.add(props);
            } else if (props.weight > 0) {
                weightedVariants.add(props);
            } else {
                defaultVariants.add(props);
            }
        }

        if (!matchingVariants.isEmpty()) {
            return matchingVariants.get(0);
        }

        if (!weightedVariants.isEmpty()) {
            return selectWeightedVariant(weightedVariants);
        }

        return defaultVariants.isEmpty() ? null : defaultVariants.get(0);
    }

    private OptiFinePropertiesParser.EntityTextureProperties selectWeightedVariant(
            List<OptiFinePropertiesParser.EntityTextureProperties> variants) {
        
        float totalWeight = 0;
        for (OptiFinePropertiesParser.EntityTextureProperties v : variants) {
            totalWeight += v.weight;
        }

        float random = (float) Math.random() * totalWeight;
        float cumulative = 0;

        for (OptiFinePropertiesParser.EntityTextureProperties v : variants) {
            cumulative += v.weight;
            if (random <= cumulative) {
                return v;
            }
        }

        return variants.get(0);
    }

    public void registerEntityTexture(String entityId, OptiFinePropertiesParser.EntityTextureProperties properties) {
        entityTextures.compute(entityId, (key, existing) -> {
            if (existing == null) {
                // 修复BUG: 使用普通ArrayList，当其他线程通过getEntityTextures()迭代该List时并发调用add会触发ConcurrentModificationException
                existing = new CopyOnWriteArrayList<>();
            }
            existing.add(properties);
            return existing;
        });
        
        String propsKey = entityId + ":" + properties.getPrimaryTexture();
        propertiesCache.put(propsKey, properties);
        
        CoreSplitMod.LOGGER.debug("[CoreSplit] Registered texture for entity {}: {}", entityId, properties.getPrimaryTexture());
    }

    public void unregisterEntityTexture(String entityId) {
        List<OptiFinePropertiesParser.EntityTextureProperties> removed = entityTextures.remove(entityId);
        if (removed != null) {
            for (OptiFinePropertiesParser.EntityTextureProperties props : removed) {
                String propsKey = entityId + ":" + props.getPrimaryTexture();
                propertiesCache.remove(propsKey);
                
                if (!props.getPrimaryTexture().isEmpty()) {
                    textureCache.invalidate(props.getPrimaryTexture());
                }
                if (!props.getPrimaryEmissiveTexture().isEmpty()) {
                    textureCache.invalidate(props.getPrimaryEmissiveTexture());
                }
            }
        }
    }

    public void loadResourcePackTextures(java.util.function.Supplier<Map<String, java.io.InputStream>> resourceProvider) {
        Map<String, java.io.InputStream> resources = resourceProvider.get();

        // 修复BUG: resourceProvider可能返回null，后续entrySet()会抛NullPointerException
        if (resources == null) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Resource provider returned null, skipping texture loading");
            return;
        }

        for (Map.Entry<String, java.io.InputStream> entry : resources.entrySet()) {
            String path = entry.getKey();
            java.io.InputStream stream = entry.getValue();

            // 修复BUG: path为null时调用endsWith会抛NullPointerException
            if (path == null || stream == null) {
                continue;
            }

            if (path.endsWith(".properties")) {
                try {
                    OptiFinePropertiesParser.EntityTextureProperties props =
                            OptiFinePropertiesParser.parse(stream);

                    String entityId = extractEntityIdFromPath(path);
                    if (!entityId.isEmpty()) {
                        registerEntityTexture(entityId, props);
                    }
                } catch (Exception e) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load texture properties: {}", path, e);
                }
            } else {
                // 修复BUG: 非properties资源的InputStream未关闭会导致文件描述符泄漏
                try {
                    stream.close();
                } catch (java.io.IOException e) {
                    CoreSplitMod.LOGGER.debug("[CoreSplit] Failed to close non-properties resource stream: {}", path, e);
                }
            }
        }
    }

    private String extractEntityIdFromPath(String path) {
        if (path.contains("/entity/")) {
            int start = path.indexOf("/entity/") + 8;
            int end = path.lastIndexOf('/');
            if (start < end) {
                return path.substring(start, end);
            }
        } else if (path.contains("/textures/entity/")) {
            int start = path.indexOf("/textures/entity/") + 17;
            int end = path.lastIndexOf('.');
            if (start < end) {
                return path.substring(start, end);
            }
        }
        return "";
    }

    public void reloadTextures() {
        textureCache.invalidateAll();
        propertiesCache.clear();
        entityTextures.clear();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] TextureManager textures reloaded");
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reloadTextures();
        }
    }

    public void setEmissiveEnabled(boolean enabled) {
        this.emissiveEnabled = enabled;
    }

    public void setSkinEnhancementEnabled(boolean enabled) {
        this.skinEnhancementEnabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEmissiveEnabled() {
        return emissiveEnabled;
    }

    public boolean isSkinEnhancementEnabled() {
        return skinEnhancementEnabled;
    }

    public TextureCache getTextureCache() {
        return textureCache;
    }

    public int getRegisteredEntityCount() {
        return entityTextures.size();
    }

    public int getTexturePropertiesCount() {
        return propertiesCache.size();
    }

    public void shutdown() {
        textureCache.shutdown();
        propertiesCache.clear();
        entityTextures.clear();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] TextureManager shutdown complete");
    }

    public Map<String, List<OptiFinePropertiesParser.EntityTextureProperties>> getEntityTextures() {
        return Collections.unmodifiableMap(entityTextures);
    }
}