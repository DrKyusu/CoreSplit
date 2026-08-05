package com.coresplit.sound;

import com.coresplit.CoreSplitMod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SoundPropertiesParser {

    private static final String PROPERTY_PREFIX_SOUND = "sound.";
    private static final String PROPERTY_PREFIX_VARIANT = "variant.";
    private static final String PROPERTY_PREFIX_PLAYING = "playingSound.";

    public static SoundProperties parse(InputStream inputStream) {
        SoundProperties properties = new SoundProperties();
        
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
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to parse sound properties", e);
        }
        
        properties.finalizeProperties();
        return properties;
    }

    public static SoundProperties parse(Map<String, String> rawProperties) {
        SoundProperties properties = new SoundProperties();

        // 修复BUG: 原代码未判空rawProperties，传入null会抛NPE
        if (rawProperties == null) {
            properties.finalizeProperties();
            return properties;
        }

        for (Map.Entry<String, String> entry : rawProperties.entrySet()) {
            // 修复BUG: 原代码未判空key/value，null key会导致parseProperty内startsWith抛NPE
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            parseProperty(properties, key, value);
        }

        properties.finalizeProperties();
        return properties;
    }

    private static void parseProperty(SoundProperties properties, String key, String value) {
        if (key.startsWith(PROPERTY_PREFIX_SOUND)) {
            parseSoundProperty(properties, key.substring(PROPERTY_PREFIX_SOUND.length()), value);
        } else if (key.startsWith(PROPERTY_PREFIX_VARIANT)) {
            parseVariantProperty(properties, key.substring(PROPERTY_PREFIX_VARIANT.length()), value);
        } else if (key.startsWith(PROPERTY_PREFIX_PLAYING)) {
            parsePlayingSoundProperty(properties, key.substring(PROPERTY_PREFIX_PLAYING.length()), value);
        } else {
            switch (key) {
                case "volume":
                    try {
                        properties.globalVolume = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "pitch":
                    try {
                        properties.globalPitch = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "distance":
                    try {
                        properties.globalDistance = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "category":
                    properties.globalCategory = value;
                    break;
                case "sound.volume":
                    try {
                        properties.globalVolume = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "sound.pitch":
                    try {
                        properties.globalPitch = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "sound.distance":
                    try {
                        properties.globalDistance = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "sound.category":
                    properties.globalCategory = value;
                    break;
            }
        }
    }

    private static void parseSoundProperty(SoundProperties properties, String suffix, String value) {
        int dotIndex = suffix.indexOf('.');
        if (dotIndex > 0) {
            String soundId = suffix.substring(0, dotIndex);
            String property = suffix.substring(dotIndex + 1);
            
            SoundDefinition sound = properties.sounds.computeIfAbsent(soundId, k -> new SoundDefinition());
            sound.id = soundId;
            
            switch (property.toLowerCase()) {
                case "volume":
                    try {
                        sound.volume = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "pitch":
                    try {
                        sound.pitch = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "distance":
                case "attenuation":
                    try {
                        sound.distance = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "category":
                    sound.category = value;
                    break;
                case "type":
                    sound.type = SoundType.fromString(value);
                    break;
                case "file":
                    sound.filePath = value;
                    break;
                case "loop":
                    sound.loop = Boolean.parseBoolean(value);
                    break;
                case "delay":
                    try {
                        // 修复BUG: 原代码未校验delay范围，负数延迟无意义
                        int delayVal = Integer.parseInt(value);
                        sound.delay = Math.max(0, delayVal);
                    } catch (NumberFormatException e) {}
                    break;
                case "chance":
                    try {
                        // 修复BUG: 原代码未校验chance范围，应为0.0-1.0
                        float chanceVal = Float.parseFloat(value);
                        sound.chance = Math.max(0f, Math.min(1f, chanceVal));
                    } catch (NumberFormatException e) {}
                    break;
                case "condition":
                    sound.condition = value;
                    break;
                case "texture":
                    sound.textureVariant = value;
                    break;
                case "model":
                    sound.modelVariant = value;
                    break;
            }
        } else {
            properties.sounds.put(suffix, new SoundDefinition(suffix, value));
        }
    }

    private static void parseVariantProperty(SoundProperties properties, String suffix, String value) {
        int dotIndex = suffix.indexOf('.');
        if (dotIndex > 0) {
            String variantId = suffix.substring(0, dotIndex);
            String property = suffix.substring(dotIndex + 1);
            
            SoundVariant variant = properties.variants.computeIfAbsent(variantId, k -> new SoundVariant());
            variant.id = variantId;
            
            switch (property.toLowerCase()) {
                case "sound":
                case "sounds":
                    // 修复BUG: 原代码split(",")对空字符串返回[""]，会把空串加入列表
                    for (String s : value.split(",")) {
                        String trimmed = s.trim();
                        if (!trimmed.isEmpty()) {
                            variant.soundIds.add(trimmed);
                        }
                    }
                    break;
                case "volume":
                    try {
                        variant.volume = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "pitch":
                    try {
                        variant.pitch = Float.parseFloat(value);
                    } catch (NumberFormatException e) {}
                    break;
                case "chance":
                    try {
                        // 修复BUG: 原代码未校验chance范围，应为0.0-1.0
                        float chanceVal = Float.parseFloat(value);
                        variant.chance = Math.max(0f, Math.min(1f, chanceVal));
                    } catch (NumberFormatException e) {}
                    break;
                case "texture":
                    variant.textureVariant = value;
                    break;
            }
        } else {
            properties.variants.put(suffix, new SoundVariant(suffix, value));
        }
    }

    private static void parsePlayingSoundProperty(SoundProperties properties, String suffix, String value) {
        int dotIndex = suffix.indexOf('.');
        if (dotIndex > 0) {
            String playingId = suffix.substring(0, dotIndex);
            String property = suffix.substring(dotIndex + 1);

            PlayingSoundState playingState = properties.playingSounds.computeIfAbsent(playingId, k -> new PlayingSoundState());
            playingState.id = playingId;

            switch (property.toLowerCase()) {
                case "texture":
                    playingState.textureChange = value;
                    break;
                case "model":
                    playingState.modelChange = value;
                    break;
                case "transparency":
                    try {
                        // 修复BUG: 原代码未校验transparency范围，应为0-255
                        int transVal = Integer.parseInt(value);
                        playingState.transparency = Math.max(0, Math.min(255, transVal));
                    } catch (NumberFormatException e) {}
                    break;
                case "scale":
                    playingState.scale = parseFloatArray(value);
                    break;
                case "color":
                    playingState.color = parseColor(value);
                    break;
                case "chance":
                    try {
                        // 修复BUG: 原代码未校验chance范围，应为0.0-1.0
                        float chanceVal = Float.parseFloat(value);
                        playingState.chance = Math.max(0f, Math.min(1f, chanceVal));
                    } catch (NumberFormatException e) {}
                    break;
                case "condition":
                    playingState.condition = value;
                    break;
            }
        }
        // 修复BUG: 原代码当suffix无点时（如"playingSound.myState"无属性）静默忽略，
        // 此处保持忽略行为，因无属性名无法赋值
    }

    private static float[] parseFloatArray(String value) {
        if (value == null || value.isEmpty()) {
            return new float[]{1, 1, 1};
        }
        String[] parts = value.split(",");
        float[] result = new float[]{1, 1, 1};
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                result[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 1;
            }
        }
        return result;
    }

    private static int parseColor(String value) {
        // 修复BUG: 原代码未判空value，null调用startsWith会抛NPE
        if (value == null || value.isEmpty()) {
            return 0xFFFFFFFF;
        }
        try {
            if (value.startsWith("#")) {
                String hex = value.substring(1);
                // 修复BUG: 原代码对8位hex直接Long.parseLong后强转int，超过Integer.MAX_VALUE时
                // 强转行为虽正确（补码），但显式用parseUnsignedLong更清晰表达ARGB意图
                if (hex.length() == 6) {
                    // 6位RGB前补FF alpha通道，返回0xFFRRGGBB
                    return (int) Long.parseUnsignedLong("FF" + hex, 16);
                } else if (hex.length() == 8) {
                    // 8位ARGB直接解析
                    return (int) Long.parseUnsignedLong(hex, 16);
                }
                // 其他长度视为无效
                return 0xFFFFFFFF;
            }
            // 修复BUG: 原代码Integer.parseInt对超过int范围的值会抛异常，这里捕获返回默认值
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    public static class SoundProperties {
        public float globalVolume = 1.0f;
        public float globalPitch = 1.0f;
        public float globalDistance = 16.0f;
        public String globalCategory = "hostile";
        
        public Map<String, SoundDefinition> sounds = new LinkedHashMap<>();
        public Map<String, SoundVariant> variants = new LinkedHashMap<>();
        public Map<String, PlayingSoundState> playingSounds = new LinkedHashMap<>();
        
        public void finalizeProperties() {
            if (globalVolume < 0) globalVolume = 0;
            if (globalVolume > 2) globalVolume = 2;

            if (globalPitch < 0.5f) globalPitch = 0.5f;
            if (globalPitch > 2.0f) globalPitch = 2.0f;

            if (globalDistance < 1) globalDistance = 1;
            if (globalDistance > 64) globalDistance = 64;

            for (SoundDefinition sound : sounds.values()) {
                // 修复BUG: 原代码仅判断==0赋默认值，未处理负数和超出范围的非法值
                if (sound.volume <= 0) sound.volume = globalVolume;
                else sound.volume = Math.max(0, Math.min(2, sound.volume));

                if (sound.pitch <= 0) sound.pitch = globalPitch;
                else sound.pitch = Math.max(0.5f, Math.min(2.0f, sound.pitch));

                if (sound.distance <= 0) sound.distance = globalDistance;
                else sound.distance = Math.max(1, Math.min(64, sound.distance));

                if (sound.category == null || sound.category.isEmpty()) sound.category = globalCategory;
            }
        }
        
        public SoundDefinition getSound(String id) {
            return sounds.get(id);
        }
        
        public SoundVariant getVariant(String id) {
            return variants.get(id);
        }
        
        public PlayingSoundState getPlayingSound(String id) {
            return playingSounds.get(id);
        }
        
        public boolean hasSounds() {
            return !sounds.isEmpty();
        }
        
        public boolean hasVariants() {
            return !variants.isEmpty();
        }
        
        public boolean hasPlayingSounds() {
            return !playingSounds.isEmpty();
        }
    }

    public static class SoundDefinition {
        public String id = "";
        public String filePath = "";
        public float volume = 0;
        public float pitch = 0;
        public float distance = 0;
        public String category = "";
        public SoundType type = SoundType.EVENT;
        public boolean loop = false;
        public int delay = 0;
        public float chance = 1.0f;
        public String condition = "";
        public String textureVariant = "";
        public String modelVariant = "";
        
        public SoundDefinition() {}
        
        public SoundDefinition(String id, String filePath) {
            this.id = id;
            this.filePath = filePath;
        }
        
        public boolean hasCondition() {
            return !condition.isEmpty();
        }
        
        public boolean matchesCondition(String state) {
            if (!hasCondition()) return true;
            // 注意: equalsIgnoreCase(null)返回false而非抛NPE，故无需额外判空
            return condition.equalsIgnoreCase(state);
        }
    }

    public static class SoundVariant {
        public String id = "";
        public List<String> soundIds = new ArrayList<>();
        public float volume = 1.0f;
        public float pitch = 1.0f;
        public float chance = 1.0f;
        public String textureVariant = "";
        
        public SoundVariant() {}
        
        public SoundVariant(String id, String soundIds) {
            this.id = id;
            // 修复BUG: 原代码split(",")对空字符串返回[""]，会把空串加入列表
            if (soundIds != null) {
                for (String s : soundIds.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        this.soundIds.add(trimmed);
                    }
                }
            }
        }
        
        public boolean hasSounds() {
            return !soundIds.isEmpty();
        }
        
        public String getRandomSound() {
            if (soundIds.isEmpty()) return "";
            return soundIds.get((int) (Math.random() * soundIds.size()));
        }
    }

    public static class PlayingSoundState {
        public String id = "";
        public String textureChange = "";
        public String modelChange = "";
        public int transparency = -1;
        public float[] scale = new float[]{1, 1, 1};
        public int color = 0;
        public float chance = 1.0f;
        public String condition = "";
        
        public boolean hasTextureChange() {
            return !textureChange.isEmpty();
        }
        
        public boolean hasModelChange() {
            return !modelChange.isEmpty();
        }
        
        public boolean shouldApply() {
            return Math.random() < chance;
        }
    }

    public enum SoundType {
        EVENT,
        AMBIENT,
        ATTACK,
        DAMAGE,
        DEATH,
        HURT,
        STEP,
        IDLE;
        
        public static SoundType fromString(String value) {
            if (value == null) return EVENT;
            switch (value.toLowerCase()) {
                case "ambient":
                    return AMBIENT;
                case "attack":
                    return ATTACK;
                case "damage":
                    return DAMAGE;
                case "death":
                    return DEATH;
                case "hurt":
                    return HURT;
                case "step":
                    return STEP;
                case "idle":
                    return IDLE;
                default:
                    return EVENT;
            }
        }
    }
}