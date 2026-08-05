package com.coresplit.texture;

import com.coresplit.CoreSplitMod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class OptiFinePropertiesParser {

    private static final String PROPERTY_PREFIX_TEXTURE = "texture.";
    private static final String PROPERTY_PREFIX_SKIN = "skin.";
    private static final String PROPERTY_PREFIX_EMISSIVE = "emissive.";
    private static final String PROPERTY_PREFIX_VARIANT = "variant.";
    private static final String PROPERTY_PREFIX_ANIMATION = "animation.";

    public static EntityTextureProperties parse(InputStream inputStream) {
        EntityTextureProperties properties = new EntityTextureProperties();

        // 修复BUG: inputStream为null时try-with-resources会抛NullPointerException
        if (inputStream == null) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Cannot parse entity texture properties from null InputStream");
            properties.finalizeProperties();
            return properties;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    continue;
                }
                
                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();
                
                parseProperty(properties, key, value);
            }
            
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to parse entity texture properties", e);
        }
        
        properties.finalizeProperties();
        return properties;
    }

    public static EntityTextureProperties parse(Map<String, String> rawProperties) {
        EntityTextureProperties properties = new EntityTextureProperties();

        // 修复BUG: rawProperties为null时entrySet()会抛NullPointerException
        if (rawProperties == null) {
            properties.finalizeProperties();
            return properties;
        }

        for (Map.Entry<String, String> entry : rawProperties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            // 修复BUG: key或value为null时parseProperty内调用startsWith会抛NullPointerException
            if (key == null || value == null) {
                continue;
            }
            parseProperty(properties, key, value);
        }

        properties.finalizeProperties();
        return properties;
    }

    private static void parseProperty(EntityTextureProperties properties, String key, String value) {
        if (key.startsWith(PROPERTY_PREFIX_TEXTURE)) {
            parseTextureProperty(properties, key.substring(PROPERTY_PREFIX_TEXTURE.length()), value);
        } else if (key.startsWith(PROPERTY_PREFIX_SKIN)) {
            parseSkinProperty(properties, key.substring(PROPERTY_PREFIX_SKIN.length()), value);
        } else if (key.startsWith(PROPERTY_PREFIX_EMISSIVE)) {
            parseEmissiveProperty(properties, key.substring(PROPERTY_PREFIX_EMISSIVE.length()), value);
        } else if (key.startsWith(PROPERTY_PREFIX_VARIANT)) {
            parseVariantProperty(properties, key.substring(PROPERTY_PREFIX_VARIANT.length()), value);
        } else if (key.startsWith(PROPERTY_PREFIX_ANIMATION)) {
            parseAnimationProperty(properties, key.substring(PROPERTY_PREFIX_ANIMATION.length()), value);
        } else {
            switch (key) {
                case "type":
                    properties.type = value;
                    break;
                case "texture":
                    properties.mainTexture = value;
                    break;
                case "texture.entity":
                    properties.entityTexture = value;
                    break;
                case "texture.model":
                    properties.modelTexture = value;
                    break;
                case "emissive":
                    properties.emissiveTexture = value;
                    break;
                case "emissive.texture":
                    properties.emissiveTexture = value;
                    break;
                case "alpha":
                    try {
                        properties.alpha = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "transparency":
                    try {
                        properties.transparency = Integer.parseInt(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "weight":
                    try {
                        properties.weight = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "biomes":
                    // 修复BUG: split后未trim，"plains, forest"会包含前导空格；未过滤空字符串
                    for (String biome : value.split(",")) {
                        String trimmed = biome.trim();
                        if (!trimmed.isEmpty()) {
                            properties.biomes.add(trimmed);
                        }
                    }
                    break;
                case "difficulty":
                    properties.difficulty = value;
                    break;
                case "gamemode":
                    properties.gamemode = value;
                    break;
                case "name":
                    properties.name = value;
                    break;
                case "names":
                    // 修复BUG: split后未trim，"name1, name2"会包含前导空格；未过滤空字符串
                    for (String name : value.split(",")) {
                        String trimmed = name.trim();
                        if (!trimmed.isEmpty()) {
                            properties.names.add(trimmed);
                        }
                    }
                    break;
                case "condition":
                    properties.condition = value;
                    break;
                case "overlay":
                    properties.overlay = value;
                    break;
                case "layer":
                    try {
                        properties.layer = Integer.parseInt(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "noses":
                    properties.noseTexture = value;
                    break;
                case "cape":
                    properties.capeTexture = value;
                    break;
                case "ears":
                    properties.earsTexture = value;
                    break;
                case "hat":
                    properties.hatTexture = value;
                    break;
            }
        }
    }

    private static void parseTextureProperty(EntityTextureProperties properties, String suffix, String value) {
        switch (suffix) {
            case "0":
            case "":
                properties.mainTexture = value;
                break;
            case "1":
                properties.texture1 = value;
                break;
            case "2":
                properties.texture2 = value;
                break;
            case "entity":
                properties.entityTexture = value;
                break;
            case "model":
                properties.modelTexture = value;
                break;
            case "overlay":
                properties.overlayTexture = value;
                break;
        }
    }

    private static void parseSkinProperty(EntityTextureProperties properties, String suffix, String value) {
        switch (suffix) {
            case "":
            case "0":
                properties.mainTexture = value;
                break;
            case "1":
                properties.texture1 = value;
                break;
            case "2":
                properties.texture2 = value;
                break;
            case "transparency":
                try {
                    properties.transparency = Integer.parseInt(value);
                } catch (NumberFormatException e) {}
                break;
            case "alpha":
                try {
                    properties.alpha = Float.parseFloat(value);
                } catch (NumberFormatException e) {}
                break;
            case "noses":
                properties.noseTexture = value;
                break;
            case "cape":
                properties.capeTexture = value;
                break;
            case "ears":
                properties.earsTexture = value;
                break;
            case "hat":
                properties.hatTexture = value;
                break;
            case "overlay":
                properties.overlayTexture = value;
                break;
        }
    }

    private static void parseEmissiveProperty(EntityTextureProperties properties, String suffix, String value) {
        switch (suffix) {
            case "":
            case "texture":
            case "0":
                properties.emissiveTexture = value;
                break;
            case "1":
                properties.emissiveTexture1 = value;
                break;
            case "2":
                properties.emissiveTexture2 = value;
                break;
            case "entity":
                properties.emissiveEntityTexture = value;
                break;
            case "model":
                properties.emissiveModelTexture = value;
                break;
            case "overlay":
                properties.emissiveOverlayTexture = value;
                break;
            case "strength":
                try {
                    properties.emissiveStrength = Float.parseFloat(value);
                } catch (NumberFormatException e) {}
                break;
            case "color":
                properties.emissiveColor = parseColor(value);
                break;
            case "render":
                properties.emissiveRenderMode = value;
                break;
        }
    }

    private static void parseVariantProperty(EntityTextureProperties properties, String suffix, String value) {
        try {
            int index = Integer.parseInt(suffix);
            properties.variants.put(index, value);
        } catch (NumberFormatException e) {
            properties.variantConditions.put(suffix, value);
        }
    }

    private static void parseAnimationProperty(EntityTextureProperties properties, String suffix, String value) {
        switch (suffix) {
            case "":
            case "frames":
                properties.animationFrames = parseIntArray(value);
                break;
            case "speed":
                try {
                    properties.animationSpeed = Float.parseFloat(value);
                } catch (NumberFormatException e) {}
                break;
            case "fps":
                try {
                    properties.animationFps = Float.parseFloat(value);
                } catch (NumberFormatException e) {}
                break;
            case "loop":
                properties.animationLoop = Boolean.parseBoolean(value);
                break;
            case "interpolate":
                properties.animationInterpolate = Boolean.parseBoolean(value);
                break;
            case "type":
                properties.animationType = value;
                break;
            case "width":
                try {
                    properties.animationWidth = Integer.parseInt(value);
                } catch (NumberFormatException e) {}
                break;
            case "height":
                try {
                    properties.animationHeight = Integer.parseInt(value);
                } catch (NumberFormatException e) {}
                break;
            case "row":
                try {
                    properties.animationRow = Integer.parseInt(value);
                } catch (NumberFormatException e) {}
                break;
            case "flip":
                properties.animationFlip = Boolean.parseBoolean(value);
                break;
        }
    }

    private static int[] parseIntArray(String value) {
        if (value == null || value.isEmpty()) {
            return new int[0];
        }
        
        String[] parts = value.split(",");
        int[] result = new int[parts.length];
        
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        
        return result;
    }

    private static int parseColor(String value) {
        // 修复BUG: value为null时调用startsWith会抛NullPointerException
        if (value == null || value.isEmpty()) {
            return 0xFFFFFFFF;
        }
        try {
            if (value.startsWith("#")) {
                String hex = value.substring(1);
                // 修复BUG: 6位hex颜色(如#FFFFFF)解析后alpha为0(透明)，应补全为8位并设alpha=0xFF
                if (hex.length() == 6) {
                    hex = "FF" + hex;
                }
                // 修复BUG: 8位hex如FFFFFFFF超过int正数上限，Integer.parseInt会抛NumberFormatException，改用Long.parseLong再转型
                return (int) Long.parseLong(hex, 16);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // 修复BUG: 颜色解析失败被静默吞掉，添加警告日志
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to parse color value: {}", value);
            return 0xFFFFFFFF;
        }
    }

    public static class EntityTextureProperties {
        public String type = "";
        public String mainTexture = "";
        public String texture1 = "";
        public String texture2 = "";
        public String entityTexture = "";
        public String modelTexture = "";
        public String overlayTexture = "";
        
        public String emissiveTexture = "";
        public String emissiveTexture1 = "";
        public String emissiveTexture2 = "";
        public String emissiveEntityTexture = "";
        public String emissiveModelTexture = "";
        public String emissiveOverlayTexture = "";
        public float emissiveStrength = 1.0f;
        public int emissiveColor = 0xFFFFFFFF;
        public String emissiveRenderMode = "additive";
        
        public String noseTexture = "";
        public String capeTexture = "";
        public String earsTexture = "";
        public String hatTexture = "";
        
        public float alpha = 1.0f;
        public int transparency = 100;
        public float weight = 1.0f;
        
        public List<String> biomes = new ArrayList<>();
        public String difficulty = "";
        public String gamemode = "";
        public String name = "";
        public List<String> names = new ArrayList<>();
        public String condition = "";
        
        public String overlay = "";
        public int layer = 0;
        
        public Map<Integer, String> variants = new LinkedHashMap<>();
        public Map<String, String> variantConditions = new LinkedHashMap<>();
        
        public int[] animationFrames = new int[0];
        public float animationSpeed = 1.0f;
        public float animationFps = 10.0f;
        public boolean animationLoop = true;
        public boolean animationInterpolate = false;
        public String animationType = "";
        public int animationWidth = 0;
        public int animationHeight = 0;
        public int animationRow = 0;
        public boolean animationFlip = false;

        public void finalizeProperties() {
            if (emissiveTexture.isEmpty() && !mainTexture.isEmpty()) {
                String emissivePath = mainTexture.replace(".png", "_e.png");
                emissiveTexture = emissivePath;
            }
            
            if (transparency < 0) transparency = 0;
            if (transparency > 100) transparency = 100;

            if (alpha < 0) alpha = 0;
            if (alpha > 1) alpha = 1;

            if (weight <= 0) weight = 0.001f;

            // 修复BUG: emissiveStrength未做范围校验，可能为负数或NaN导致渲染异常
            if (emissiveStrength < 0 || Float.isNaN(emissiveStrength)) emissiveStrength = 0;
            if (emissiveStrength > 2) emissiveStrength = 2;

            // 修复BUG: animationFps未校验，为0或负数时下游计算帧时间会除零
            if (animationFps <= 0 || Float.isNaN(animationFps)) animationFps = 10.0f;

            // 修复BUG: animationSpeed未校验，为0或负数或NaN时会导致动画异常
            if (animationSpeed <= 0 || Float.isNaN(animationSpeed)) animationSpeed = 1.0f;
            
            if (animationFrames.length == 0 && animationWidth > 0) {
                // 修复BUG: animationWidth未限制上限，过大值(如配置错误)会导致分配超大数组引发OutOfMemoryError
                int frameCount = Math.min(animationWidth, 1024);
                animationFrames = new int[frameCount];
                for (int i = 0; i < frameCount; i++) {
                    animationFrames[i] = i;
                }
            }
        }

        public boolean hasEmissiveTexture() {
            return !emissiveTexture.isEmpty() || 
                   !emissiveTexture1.isEmpty() || 
                   !emissiveTexture2.isEmpty();
        }

        public boolean hasAnimation() {
            return animationFrames.length > 0 || 
                   animationWidth > 0 || 
                   animationHeight > 0;
        }

        public boolean hasConditions() {
            return !biomes.isEmpty() || 
                   !difficulty.isEmpty() || 
                   !gamemode.isEmpty() || 
                   !name.isEmpty() || 
                   !names.isEmpty() || 
                   !condition.isEmpty();
        }

        public String getPrimaryTexture() {
            if (!mainTexture.isEmpty()) return mainTexture;
            if (!texture1.isEmpty()) return texture1;
            if (!entityTexture.isEmpty()) return entityTexture;
            return "";
        }

        public String getPrimaryEmissiveTexture() {
            if (!emissiveTexture.isEmpty()) return emissiveTexture;
            if (!emissiveTexture1.isEmpty()) return emissiveTexture1;
            if (hasEmissiveTexture() && !mainTexture.isEmpty()) {
                return mainTexture.replace(".png", "_e.png");
            }
            return "";
        }
    }
}