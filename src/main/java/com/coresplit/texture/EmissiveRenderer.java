package com.coresplit.texture;

import com.coresplit.CoreSplitMod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EmissiveRenderer {

    private static final float DEFAULT_EMISSIVE_STRENGTH = 1.0f;
    private static final int DEFAULT_EMISSIVE_COLOR = 0xFFFFFFFF;

    private final ConcurrentHashMap<Integer, EmissiveRenderData> emissiveDataCache;
    
    private volatile boolean enabled = true;
    private volatile boolean useAdditiveBlending = true;
    private volatile float globalEmissiveStrength = 1.0f;

    public EmissiveRenderer() {
        this.emissiveDataCache = new ConcurrentHashMap<>();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] EmissiveRenderer initialized");
    }

    public void renderEmissive(int textureId, int emissiveTextureId, 
                               float strength, int color, String renderMode) {
        if (!enabled || emissiveTextureId == 0) {
            return;
        }

        EmissiveRenderData data = getOrCreateEmissiveData(emissiveTextureId);

        float finalStrength = strength * globalEmissiveStrength;

        // 修复BUG: renderMode可能为null，直接调用toLowerCase()会抛NullPointerException
        String mode = renderMode != null ? renderMode.toLowerCase() : "additive";

        switch (mode) {
            case "additive":
                renderAdditive(textureId, emissiveTextureId, finalStrength, color);
                break;
            case "multiply":
                renderMultiply(textureId, emissiveTextureId, finalStrength, color);
                break;
            case "overlay":
                renderOverlay(textureId, emissiveTextureId, finalStrength, color);
                break;
            default:
                renderAdditive(textureId, emissiveTextureId, finalStrength, color);
                break;
        }
    }

    public void renderEmissive(TextureCache.CachedTexture baseTexture, 
                               TextureCache.CachedTexture emissiveTexture,
                               float strength, int color, String renderMode) {
        if (!enabled || emissiveTexture == null || !emissiveTexture.isValid()) {
            return;
        }

        int baseId = baseTexture != null && baseTexture.isValid() ? baseTexture.glTextureId : 0;
        renderEmissive(baseId, emissiveTexture.glTextureId, strength, color, renderMode);
    }

    private void renderAdditive(int textureId, int emissiveTextureId, float strength, int color) {
        setupAdditiveBlending();
        
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        
        setEmissiveUniforms(r, g, b, a * strength);
        
        bindEmissiveTexture(emissiveTextureId);
        
        drawEmissiveGeometry();
        
        resetBlending();
    }

    private void renderMultiply(int textureId, int emissiveTextureId, float strength, int color) {
        setupMultiplyBlending();
        
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        
        setEmissiveUniforms(r, g, b, a * strength);
        
        bindEmissiveTexture(emissiveTextureId);
        
        drawEmissiveGeometry();
        
        resetBlending();
    }

    private void renderOverlay(int textureId, int emissiveTextureId, float strength, int color) {
        setupOverlayBlending();
        
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        
        setEmissiveUniforms(r, g, b, a * strength);
        
        bindEmissiveTexture(emissiveTextureId);
        
        drawEmissiveGeometry();
        
        resetBlending();
    }

    private void setupAdditiveBlending() {
    }

    private void setupMultiplyBlending() {
    }

    private void setupOverlayBlending() {
    }

    private void resetBlending() {
    }

    private void setEmissiveUniforms(float r, float g, float b, float a) {
    }

    private void bindEmissiveTexture(int textureId) {
    }

    private void drawEmissiveGeometry() {
    }

    private EmissiveRenderData getOrCreateEmissiveData(int textureId) {
        return emissiveDataCache.computeIfAbsent(textureId, 
                id -> new EmissiveRenderData(id));
    }

    public void preprocessEmissiveTexture(TextureCache.CachedTexture texture) {
        if (!enabled || texture == null) {
            return;
        }

        EmissiveRenderData data = getOrCreateEmissiveData(texture.glTextureId);
        data.width = texture.width;
        data.height = texture.height;
        data.pixelData = texture.pixelData;
        
        extractEmissiveMask(data);
    }

    private void extractEmissiveMask(EmissiveRenderData data) {
        if (data.pixelData == null) {
            return;
        }

        // 修复BUG: width/height未校验，可能为0或负数导致分配空数组或负长度数组
        if (data.width <= 0 || data.height <= 0) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Invalid emissive texture dimensions: {}x{}", data.width, data.height);
            return;
        }

        int pixelCount = data.width * data.height;

        // 修复BUG: 直接操作共享ByteBuffer的position/limit会与其他读取线程冲突，使用duplicate保证线程安全
        java.nio.ByteBuffer readBuffer = data.pixelData.duplicate();
        readBuffer.rewind();

        // 修复BUG: 未检查缓冲区剩余容量是否足够读取pixelCount*4字节，可能BufferUnderflowException
        if (readBuffer.remaining() < (long) pixelCount * 4) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Emissive pixel buffer too small: needed {} bytes, has {}",
                    pixelCount * 4, readBuffer.remaining());
            return;
        }

        float[] mask = new float[pixelCount * 3];

        for (int i = 0; i < pixelCount; i++) {
            float r = (readBuffer.get() & 0xFF) / 255.0f;
            float g = (readBuffer.get() & 0xFF) / 255.0f;
            float b = (readBuffer.get() & 0xFF) / 255.0f;
            readBuffer.get();

            float intensity = (r + g + b) / 3.0f;

            mask[i * 3] = r * intensity;
            mask[i * 3 + 1] = g * intensity;
            mask[i * 3 + 2] = b * intensity;
        }

        // 修复BUG: 先填充本地数组再赋值到共享字段，配合volatile保证其他线程不会读到部分填充的数组
        data.emissiveMask = mask;
    }

    public void updateEmissiveStrength(float strength) {
        this.globalEmissiveStrength = Math.max(0.0f, Math.min(2.0f, strength));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public float getGlobalEmissiveStrength() {
        return globalEmissiveStrength;
    }

    public int getCachedEmissiveDataCount() {
        return emissiveDataCache.size();
    }

    public void clearCache() {
        emissiveDataCache.clear();
    }

    public void shutdown() {
        clearCache();
        CoreSplitMod.LOGGER.info("[CoreSplit] EmissiveRenderer shutdown complete");
    }

    private static class EmissiveRenderData {
        final int textureId;
        // 修复BUG: 跨线程读写的字段未声明volatile，可能读到未初始化或部分写入的值
        volatile int width;
        volatile int height;
        volatile java.nio.ByteBuffer pixelData;
        volatile float[] emissiveMask;

        EmissiveRenderData(int textureId) {
            this.textureId = textureId;
        }
    }
}