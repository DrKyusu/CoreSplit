package com.coresplit.itemrender;

import com.coresplit.CoreSplitMod;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class ItemRenderConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit_item_render.toml");

    private static final int MIN_NEAR_DISTANCE = 4;
    private static final int MAX_NEAR_DISTANCE = 64;
    private static final int DEFAULT_NEAR_DISTANCE = 16;

    private static final int MIN_MID_DISTANCE = 8;
    private static final int MAX_MID_DISTANCE = 128;
    private static final int DEFAULT_MID_DISTANCE = 32;

    private static final int MIN_FAR_DISTANCE = 16;
    private static final int MAX_FAR_DISTANCE = 256;
    private static final int DEFAULT_FAR_DISTANCE = 64;

    private static final float MIN_FADE_DISTANCE = 0.5f;
    private static final float MAX_FADE_DISTANCE = 8.0f;
    private static final float DEFAULT_FADE_DISTANCE = 2.0f;

    private static final float MIN_FOV_MARGIN = 5.0f;
    private static final float MAX_FOV_MARGIN = 45.0f;
    private static final float DEFAULT_FOV_MARGIN = 15.0f;

    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 64;
    private static final int DEFAULT_BATCH_SIZE = 16;

    private boolean enabled = true;
    private boolean frustumCulling = true;
    private boolean distanceLod = true;
    private boolean offscreenAnimationPause = true;
    private boolean smoothFade = true;

    private int nearDistance = DEFAULT_NEAR_DISTANCE;
    private int midDistance = DEFAULT_MID_DISTANCE;
    private int farDistance = DEFAULT_FAR_DISTANCE;

    private float fadeTransitionDistance = DEFAULT_FADE_DISTANCE;
    private float fovMarginDegrees = DEFAULT_FOV_MARGIN;

    private int batchUpdateSize = DEFAULT_BATCH_SIZE;

    private static volatile ItemRenderConfig instance;

    public static ItemRenderConfig getInstance() {
        ItemRenderConfig result = instance;
        if (result == null) {
            synchronized (ItemRenderConfig.class) {
                result = instance;
                if (result == null) {
                    result = new ItemRenderConfig();
                    result.load();
                    instance = result;
                }
            }
        }
        return result;
    }

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try (FileConfig fc = FileConfig.of(CONFIG_PATH)) {
            fc.load();
            enabled = fc.getOrElse("enabled", true);
            frustumCulling = fc.getOrElse("frustum_culling", true);
            distanceLod = fc.getOrElse("distance_lod", true);
            offscreenAnimationPause = fc.getOrElse("offscreen_animation_pause", true);
            smoothFade = fc.getOrElse("smooth_fade", true);

            nearDistance = getInt(fc, "near_distance", DEFAULT_NEAR_DISTANCE);
            midDistance = getInt(fc, "mid_distance", DEFAULT_MID_DISTANCE);
            farDistance = getInt(fc, "far_distance", DEFAULT_FAR_DISTANCE);

            fadeTransitionDistance = getFloat(fc, "fade_transition_distance", DEFAULT_FADE_DISTANCE);
            fovMarginDegrees = getFloat(fc, "fov_margin_degrees", DEFAULT_FOV_MARGIN);

            batchUpdateSize = getInt(fc, "batch_update_size", DEFAULT_BATCH_SIZE);

            validate();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load item render config", e);
        }
    }

    private int getInt(FileConfig fc, String key, int defaultValue) {
        Object val = fc.getOrElse(key, defaultValue);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private float getFloat(FileConfig fc, String key, float defaultValue) {
        Object val = fc.getOrElse(key, defaultValue);
        return val instanceof Number ? ((Number) val).floatValue() : defaultValue;
    }

    public void save() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# CoreSplit Item Entity Render Optimization Configuration\n\n");
            sb.append("enabled = ").append(enabled).append("\n");
            sb.append("frustum_culling = ").append(frustumCulling).append("\n");
            sb.append("distance_lod = ").append(distanceLod).append("\n");
            sb.append("offscreen_animation_pause = ").append(offscreenAnimationPause).append("\n");
            sb.append("smooth_fade = ").append(smoothFade).append("\n\n");
            sb.append("# Distance thresholds (blocks)\n");
            sb.append("near_distance = ").append(nearDistance).append("\n");
            sb.append("mid_distance = ").append(midDistance).append("\n");
            sb.append("far_distance = ").append(farDistance).append("\n\n");
            sb.append("# Visual tuning\n");
            sb.append("fade_transition_distance = ").append(fadeTransitionDistance).append("\n");
            sb.append("fov_margin_degrees = ").append(fovMarginDegrees).append("\n");
            sb.append("batch_update_size = ").append(batchUpdateSize).append("\n");
            Files.writeString(CONFIG_PATH, sb.toString());
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to save item render config", e);
        }
    }

    private void validate() {
        nearDistance = Math.max(MIN_NEAR_DISTANCE, Math.min(MAX_NEAR_DISTANCE, nearDistance));
        midDistance = Math.max(MIN_MID_DISTANCE, Math.min(MAX_MID_DISTANCE, midDistance));
        farDistance = Math.max(MIN_FAR_DISTANCE, Math.min(MAX_FAR_DISTANCE, farDistance));
        fadeTransitionDistance = Math.max(MIN_FADE_DISTANCE, Math.min(MAX_FADE_DISTANCE, fadeTransitionDistance));
        fovMarginDegrees = Math.max(MIN_FOV_MARGIN, Math.min(MAX_FOV_MARGIN, fovMarginDegrees));
        batchUpdateSize = Math.max(MIN_BATCH_SIZE, Math.min(MAX_BATCH_SIZE, batchUpdateSize));

        if (midDistance <= nearDistance) {
            midDistance = nearDistance + 8;
        }
        if (farDistance <= midDistance) {
            farDistance = midDistance + 16;
        }
    }

    public boolean isEnabled() { return enabled; }
    public boolean isFrustumCulling() { return frustumCulling; }
    public boolean isDistanceLod() { return distanceLod; }
    public boolean isOffscreenAnimationPause() { return offscreenAnimationPause; }
    public boolean isSmoothFade() { return smoothFade; }

    public int getNearDistance() { return nearDistance; }
    public int getMidDistance() { return midDistance; }
    public int getFarDistance() { return farDistance; }

    public float getFadeTransitionDistance() { return fadeTransitionDistance; }
    public float getFovMarginDegrees() { return fovMarginDegrees; }
    public int getBatchUpdateSize() { return batchUpdateSize; }

    public void setEnabled(boolean v) { this.enabled = v; save(); }
    public void setFrustumCulling(boolean v) { this.frustumCulling = v; save(); }
    public void setDistanceLod(boolean v) { this.distanceLod = v; save(); }
    public void setOffscreenAnimationPause(boolean v) { this.offscreenAnimationPause = v; save(); }
    public void setSmoothFade(boolean v) { this.smoothFade = v; save(); }

    public void setNearDistance(int v) { this.nearDistance = v; validate(); save(); }
    public void setMidDistance(int v) { this.midDistance = v; validate(); save(); }
    public void setFarDistance(int v) { this.farDistance = v; validate(); save(); }

    public void setFadeTransitionDistance(float v) { this.fadeTransitionDistance = v; validate(); save(); }
    public void setFovMarginDegrees(float v) { this.fovMarginDegrees = v; validate(); save(); }
    public void setBatchUpdateSize(int v) { this.batchUpdateSize = v; validate(); save(); }
}
