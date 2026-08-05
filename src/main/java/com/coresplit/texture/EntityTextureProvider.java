package com.coresplit.texture;

import com.coresplit.CoreSplitMod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class EntityTextureProvider {

    private final TextureManager textureManager;
    private final EmissiveRenderer emissiveRenderer;
    private final ConcurrentHashMap<String, TextureVariantCache> variantCaches;
    
    private volatile boolean enabled = true;

    public EntityTextureProvider(TextureManager textureManager, EmissiveRenderer emissiveRenderer) {
        this.textureManager = textureManager;
        this.emissiveRenderer = emissiveRenderer;
        this.variantCaches = new ConcurrentHashMap<>();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] EntityTextureProvider initialized");
    }

    public CompletableFuture<TextureResult> getEntityTexture(String entityId) {
        return getEntityTexture(entityId, null, null, null, null, null, 0);
    }

    public CompletableFuture<TextureResult> getEntityTexture(String entityId, String biome, 
                                                             String difficulty, String gamemode,
                                                             String entityName, String condition,
                                                             int variantIndex) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }

        TextureVariantCache cache = variantCaches.computeIfAbsent(entityId, k -> new TextureVariantCache());
        
        String cacheKey = buildCacheKey(biome, difficulty, gamemode, entityName, condition, variantIndex);
        
        TextureResult cached = cache.get(cacheKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            OptiFinePropertiesParser.EntityTextureProperties props = 
                    selectMatchingProperties(entityId, biome, difficulty, gamemode, entityName, condition);
            
            if (props == null) {
                return null;
            }

            TextureCache.CachedTexture baseTexture = null;
            TextureCache.CachedTexture emissiveTexture = null;

            if (!props.getPrimaryTexture().isEmpty()) {
                baseTexture = textureManager.getTextureCache().getTexture(props.getPrimaryTexture()).join();
            }

            if (props.hasEmissiveTexture() && !props.getPrimaryEmissiveTexture().isEmpty()) {
                emissiveTexture = textureManager.getTextureCache().getTexture(props.getPrimaryEmissiveTexture()).join();
                
                if (emissiveTexture != null) {
                    emissiveRenderer.preprocessEmissiveTexture(emissiveTexture);
                }
            }

            TextureResult result = new TextureResult(baseTexture, emissiveTexture, props);
            cache.put(cacheKey, result);
            
            return result;
        });
    }

    private String buildCacheKey(String biome, String difficulty, String gamemode,
                                 String entityName, String condition, int variantIndex) {
        StringBuilder key = new StringBuilder();
        key.append(biome != null ? biome : "null").append("|");
        key.append(difficulty != null ? difficulty : "null").append("|");
        key.append(gamemode != null ? gamemode : "null").append("|");
        key.append(entityName != null ? entityName : "null").append("|");
        key.append(condition != null ? condition : "null").append("|");
        key.append(variantIndex);
        return key.toString();
    }

    private OptiFinePropertiesParser.EntityTextureProperties selectMatchingProperties(
            String entityId, String biome, String difficulty, String gamemode,
            String entityName, String condition) {
        
        Map<String, ? extends java.util.List<OptiFinePropertiesParser.EntityTextureProperties>> entityTextures = 
                textureManager.getEntityTextures();
        
        java.util.List<OptiFinePropertiesParser.EntityTextureProperties> textures = entityTextures.get(entityId);
        if (textures == null || textures.isEmpty()) {
            return null;
        }

        // 修复BUG: 直接迭代entityTextures中的List，若另一线程调用registerEntityTexture并发修改会触发ConcurrentModificationException
        java.util.List<OptiFinePropertiesParser.EntityTextureProperties> texturesCopy = new java.util.ArrayList<>(textures);

        java.util.List<OptiFinePropertiesParser.EntityTextureProperties> candidates = new java.util.ArrayList<>();

        for (OptiFinePropertiesParser.EntityTextureProperties props : texturesCopy) {
            if (matchesConditions(props, biome, difficulty, gamemode, entityName, condition)) {
                candidates.add(props);
            }
        }

        if (candidates.isEmpty()) {
            return textures.get(0);
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        return selectWeightedCandidate(candidates);
    }

    private boolean matchesConditions(OptiFinePropertiesParser.EntityTextureProperties props,
                                      String biome, String difficulty, String gamemode,
                                      String entityName, String condition) {
        
        if (!props.biomes.isEmpty() && biome != null && !props.biomes.contains(biome)) {
            return false;
        }

        if (!props.difficulty.isEmpty() && difficulty != null && !props.difficulty.equalsIgnoreCase(difficulty)) {
            return false;
        }

        if (!props.gamemode.isEmpty() && gamemode != null && !props.gamemode.equalsIgnoreCase(gamemode)) {
            return false;
        }

        if (!props.name.isEmpty() && entityName != null && !props.name.equalsIgnoreCase(entityName)) {
            return false;
        }

        if (!props.names.isEmpty() && entityName != null) {
            boolean nameMatches = false;
            for (String name : props.names) {
                if (name.equalsIgnoreCase(entityName)) {
                    nameMatches = true;
                    break;
                }
            }
            if (!nameMatches) {
                return false;
            }
        }

        if (!props.condition.isEmpty() && condition != null && !evaluateCondition(props.condition, condition)) {
            return false;
        }

        return true;
    }

    private boolean evaluateCondition(String conditionExpr, String gameCondition) {
        try {
            if (conditionExpr.contains("==")) {
                String[] parts = conditionExpr.split("==");
                return parts.length == 2 && parts[1].trim().equalsIgnoreCase(gameCondition);
            }
            if (conditionExpr.contains("!=")) {
                String[] parts = conditionExpr.split("!=");
                return parts.length == 2 && !parts[1].trim().equalsIgnoreCase(gameCondition);
            }
            if (conditionExpr.contains("contains")) {
                String search = conditionExpr.replace("contains", "").trim();
                return gameCondition != null && gameCondition.contains(search);
            }
            return conditionExpr.equalsIgnoreCase(gameCondition);
        } catch (Exception e) {
            // 修复BUG: 异常被静默吞掉，添加debug日志便于排查条件表达式解析问题
            CoreSplitMod.LOGGER.debug("[CoreSplit] Failed to evaluate condition expression: {}", conditionExpr, e);
            return true;
        }
    }

    private OptiFinePropertiesParser.EntityTextureProperties selectWeightedCandidate(
            java.util.List<OptiFinePropertiesParser.EntityTextureProperties> candidates) {
        
        float totalWeight = 0;
        for (OptiFinePropertiesParser.EntityTextureProperties c : candidates) {
            totalWeight += c.weight;
        }

        float random = (float) Math.random() * totalWeight;
        float cumulative = 0;

        for (OptiFinePropertiesParser.EntityTextureProperties c : candidates) {
            cumulative += c.weight;
            if (random <= cumulative) {
                return c;
            }
        }

        return candidates.get(0);
    }

    public void invalidateEntityTextures(String entityId) {
        textureManager.unregisterEntityTexture(entityId);
        variantCaches.remove(entityId);
    }

    public void invalidateAll() {
        variantCaches.clear();
        textureManager.reloadTextures();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            invalidateAll();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCachedVariantCount(String entityId) {
        TextureVariantCache cache = variantCaches.get(entityId);
        return cache != null ? cache.size() : 0;
    }

    public int getTotalCachedVariants() {
        int total = 0;
        for (TextureVariantCache cache : variantCaches.values()) {
            total += cache.size();
        }
        return total;
    }

    public void shutdown() {
        variantCaches.clear();
        CoreSplitMod.LOGGER.info("[CoreSplit] EntityTextureProvider shutdown complete");
    }

    public static class TextureResult {
        public final TextureCache.CachedTexture baseTexture;
        public final TextureCache.CachedTexture emissiveTexture;
        public final OptiFinePropertiesParser.EntityTextureProperties properties;

        public TextureResult(TextureCache.CachedTexture baseTexture, 
                            TextureCache.CachedTexture emissiveTexture,
                            OptiFinePropertiesParser.EntityTextureProperties properties) {
            this.baseTexture = baseTexture;
            this.emissiveTexture = emissiveTexture;
            this.properties = properties;
        }

        public boolean hasEmissive() {
            return emissiveTexture != null && emissiveTexture.isValid();
        }

        public boolean hasAnimation() {
            return properties != null && properties.hasAnimation();
        }
    }

    private static class TextureVariantCache {
        private final ConcurrentHashMap<String, TextureResult> cache = new ConcurrentHashMap<>();

        public TextureResult get(String key) {
            return cache.get(key);
        }

        public void put(String key, TextureResult result) {
            cache.put(key, result);
        }

        public int size() {
            return cache.size();
        }

        public void clear() {
            cache.clear();
        }
    }
}