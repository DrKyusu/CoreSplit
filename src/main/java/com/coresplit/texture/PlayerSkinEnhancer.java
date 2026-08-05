package com.coresplit.texture;

import com.coresplit.CoreSplitMod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class PlayerSkinEnhancer {

    private static final int DEFAULT_TRANSPARENCY = 100;
    private static final float DEFAULT_ALPHA = 1.0f;

    private final TextureCache textureCache;
    private final ConcurrentHashMap<String, SkinEnhancementData> skinDataCache;
    
    private volatile boolean enabled = true;
    private volatile boolean noseCustomizationEnabled = true;
    private volatile boolean capeAnimationEnabled = true;
    private volatile boolean earAnimationEnabled = true;

    public PlayerSkinEnhancer(TextureCache textureCache) {
        this.textureCache = textureCache;
        this.skinDataCache = new ConcurrentHashMap<>();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] PlayerSkinEnhancer initialized");
    }

    public CompletableFuture<SkinEnhancementData> loadPlayerSkin(String playerName) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }

        SkinEnhancementData existing = skinDataCache.get(playerName);
        if (existing != null && existing.isValid()) {
            return CompletableFuture.completedFuture(existing);
        }

        return CompletableFuture.supplyAsync(() -> {
            SkinEnhancementData data = new SkinEnhancementData(playerName);
            
            CompletableFuture<TextureCache.CachedTexture> skinFuture = textureCache.getTexture(
                    getSkinTexturePath(playerName));
            CompletableFuture<TextureCache.CachedTexture> capeFuture = textureCache.getTexture(
                    getCapeTexturePath(playerName));
            CompletableFuture<TextureCache.CachedTexture> earsFuture = textureCache.getTexture(
                    getEarsTexturePath(playerName));
            CompletableFuture<TextureCache.CachedTexture> noseFuture = textureCache.getTexture(
                    getNoseTexturePath(playerName));

            data.skinTexture = skinFuture.join();
            data.capeTexture = capeFuture.join();
            data.earsTexture = earsFuture.join();
            data.noseTexture = noseFuture.join();

            if (data.skinTexture != null) {
                extractSkinLayers(data);
            }

            skinDataCache.put(playerName, data);
            return data;
        });
    }

    private String getSkinTexturePath(String playerName) {
        return "/textures/entity/player/" + playerName.toLowerCase() + ".png";
    }

    private String getCapeTexturePath(String playerName) {
        return "/textures/entity/player/" + playerName.toLowerCase() + "_cape.png";
    }

    private String getEarsTexturePath(String playerName) {
        return "/textures/entity/player/" + playerName.toLowerCase() + "_ears.png";
    }

    private String getNoseTexturePath(String playerName) {
        return "/textures/entity/player/" + playerName.toLowerCase() + "_nose.png";
    }

    private void extractSkinLayers(SkinEnhancementData data) {
        if (data.skinTexture == null || data.skinTexture.pixelData == null) {
            return;
        }

        int width = data.skinTexture.width;
        int height = data.skinTexture.height;
        
        if (width >= 64 && height >= 64) {
            data.hasSlimArms = (width == 64 && height == 64);
            extractLayer(data, 0, 0, 32, 16, SkinLayer.HEAD);
            extractLayer(data, 32, 0, 64, 16, SkinLayer.HAT);
            extractLayer(data, 0, 16, 16, 32, SkinLayer.BODY);
            extractLayer(data, 16, 16, 32, 48, SkinLayer.RIGHT_ARM);
            extractLayer(data, 48, 16, 64, 48, SkinLayer.LEFT_ARM);
            extractLayer(data, 16, 48, 32, 64, SkinLayer.RIGHT_LEG);
            extractLayer(data, 40, 48, 56, 64, SkinLayer.LEFT_LEG);
        }
    }

    private void extractLayer(SkinEnhancementData data, int startX, int startY, int endX, int endY, SkinLayer layer) {
        int width = endX - startX;
        int height = endY - startY;

        // 修复BUG: width/height可能为0或负数(若调用参数有误)，new int[负数]会抛NegativeArraySizeException
        if (width <= 0 || height <= 0) {
            return;
        }

        LayerData layerData = new LayerData(width, height);

        if (data.skinTexture != null && data.skinTexture.pixelData != null) {
            // 修复BUG: 直接操作共享ByteBuffer的position/limit会与其他读取线程冲突，使用duplicate保证线程安全
            java.nio.ByteBuffer readBuffer = data.skinTexture.pixelData.duplicate();
            int bufferCapacity = readBuffer.limit();
            int skinWidth = data.skinTexture.width;

            for (int y = startY; y < endY; y++) {
                for (int x = startX; x < endX; x++) {
                    int index = y * skinWidth * 4 + x * 4;
                    // 修复BUG: 未检查索引是否超出缓冲区容量，getInt()会抛BufferUnderflowException
                    if (index + 4 > bufferCapacity) {
                        continue;
                    }
                    readBuffer.position(index);

                    layerData.pixels[(y - startY) * width + (x - startX)] =
                            readBuffer.getInt();
                }
            }
        }

        data.layers.put(layer, layerData);
    }

    public void setSkinTransparency(String playerName, int transparency) {
        SkinEnhancementData data = skinDataCache.computeIfAbsent(playerName, SkinEnhancementData::new);
        data.transparency = Math.max(0, Math.min(100, transparency));
        data.alpha = data.transparency / 100.0f;
    }

    public void setSkinAlpha(String playerName, float alpha) {
        SkinEnhancementData data = skinDataCache.computeIfAbsent(playerName, SkinEnhancementData::new);
        data.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        data.transparency = (int) (data.alpha * 100);
    }

    public float getSkinAlpha(String playerName) {
        SkinEnhancementData data = skinDataCache.get(playerName);
        return data != null ? data.alpha : DEFAULT_ALPHA;
    }

    public int getSkinTransparency(String playerName) {
        SkinEnhancementData data = skinDataCache.get(playerName);
        return data != null ? data.transparency : DEFAULT_TRANSPARENCY;
    }

    public void updateCapeAnimation(float deltaTime) {
        if (!enabled || !capeAnimationEnabled) {
            return;
        }

        for (SkinEnhancementData data : skinDataCache.values()) {
            if (data.capeTexture != null) {
                data.capeAnimationPhase += deltaTime * 0.5f;
                data.capeAnimationPhase %= (float) Math.PI * 2;
            }
        }
    }

    public void updateEarAnimation(float deltaTime) {
        if (!enabled || !earAnimationEnabled) {
            return;
        }

        for (SkinEnhancementData data : skinDataCache.values()) {
            if (data.earsTexture != null) {
                data.earAnimationPhase += deltaTime * 0.3f;
                data.earAnimationPhase %= (float) Math.PI * 2;
            }
        }
    }

    public SkinEnhancementData getSkinData(String playerName) {
        return skinDataCache.get(playerName);
    }

    public void clearPlayerSkin(String playerName) {
        skinDataCache.remove(playerName);
    }

    public void clearAllSkins() {
        skinDataCache.clear();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAllSkins();
        }
    }

    public void setNoseCustomizationEnabled(boolean enabled) {
        this.noseCustomizationEnabled = enabled;
    }

    public void setCapeAnimationEnabled(boolean enabled) {
        this.capeAnimationEnabled = enabled;
    }

    public void setEarAnimationEnabled(boolean enabled) {
        this.earAnimationEnabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isNoseCustomizationEnabled() {
        return noseCustomizationEnabled;
    }

    public boolean isCapeAnimationEnabled() {
        return capeAnimationEnabled;
    }

    public boolean isEarAnimationEnabled() {
        return earAnimationEnabled;
    }

    public int getCachedSkinCount() {
        return skinDataCache.size();
    }

    public void shutdown() {
        clearAllSkins();
        CoreSplitMod.LOGGER.info("[CoreSplit] PlayerSkinEnhancer shutdown complete");
    }

    public static class SkinEnhancementData {
        public final String playerName;
        public TextureCache.CachedTexture skinTexture;
        public TextureCache.CachedTexture capeTexture;
        public TextureCache.CachedTexture earsTexture;
        public TextureCache.CachedTexture noseTexture;
        
        public int transparency = DEFAULT_TRANSPARENCY;
        public float alpha = DEFAULT_ALPHA;

        public boolean hasSlimArms = false;
        public final Map<SkinLayer, LayerData> layers = new ConcurrentHashMap<>();

        // 修复BUG: 跨线程读写的动画相位字段未声明volatile，渲染线程可能读到陈旧值
        public volatile float capeAnimationPhase = 0;
        public volatile float earAnimationPhase = 0;

        public SkinEnhancementData(String playerName) {
            this.playerName = playerName;
        }

        public boolean isValid() {
            return skinTexture != null && skinTexture.isValid();
        }
    }

    public enum SkinLayer {
        HEAD,
        HAT,
        BODY,
        RIGHT_ARM,
        LEFT_ARM,
        RIGHT_LEG,
        LEFT_LEG
    }

    public static class LayerData {
        public final int width;
        public final int height;
        public final int[] pixels;

        public LayerData(int width, int height) {
            this.width = width;
            this.height = height;
            // 修复BUG: width/height为负数时width*height为正但语义错误，为0时创建空数组，防御性clamp避免NegativeArraySizeException
            int safeWidth = Math.max(0, width);
            int safeHeight = Math.max(0, height);
            this.pixels = new int[safeWidth * safeHeight];
        }
    }
}