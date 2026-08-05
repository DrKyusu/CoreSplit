package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class JemFileParser {

    // 修复BUG: 原解析警告无限流，压力测试下大量损坏模型文件会导致日志风暴；
    // 采用令牌桶限流（每 10 秒最多 10 条解析警告），避免刷屏。
    private static final long WARN_RATE_LIMIT_MS = 10_000L;
    private static final int WARN_MAX_PER_WINDOW = 10;
    private static final AtomicLong lastWarnTime = new AtomicLong(0);
    private static final AtomicLong warnCount = new AtomicLong(0);

    private static boolean allowWarn() {
        long now = System.currentTimeMillis();
        long last = lastWarnTime.get();
        if (now - last > WARN_RATE_LIMIT_MS) {
            lastWarnTime.set(now);
            warnCount.set(1);
            return true;
        }
        return warnCount.incrementAndGet() <= WARN_MAX_PER_WINDOW;
    }

    private static final String KEY_MODEL = "model";
    private static final String KEY_BONE = "bone";
    private static final String KEY_CUBE = "cube";
    private static final String KEY_TEXTURE = "texture";
    private static final String KEY_ANIMATION = "animation";
    private static final String KEY_ROTATION = "rotation";
    private static final String KEY_TRANSLATION = "translation";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_CONDITION = "condition";
    private static final String KEY_VISIBILITY = "visibility";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_SIZE = "size";
    private static final String KEY_UV = "uv";
    private static final String KEY_PIVOT = "pivot";
    private static final String KEY_INFLUENCE = "influence";
    private static final String KEY_FRAME = "frame";
    private static final String KEY_TIME = "time";
    private static final String KEY_LOOP = "loop";
    private static final String KEY_INTERPOLATE = "interpolate";
    private static final String KEY_TYPE = "type";
    private static final String KEY_NAME = "name";
    private static final String KEY_PARENT = "parent";
    private static final String KEY_MIRRORED = "mirrored";
    private static final String KEY_SHADE = "shade";
    private static final String KEY_ALPHA = "alpha";

    public static CemModel parse(InputStream inputStream) {
        CemModel model = new CemModel();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            String line;
            int lineNumber = 0;
            String currentSection = "";
            Bone currentBone = null;
            Cube currentCube = null;
            Animation currentAnimation = null;
            AnimationFrame currentFrame = null;
            
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                if (line.startsWith("[") && line.endsWith("]") && line.length() >= 2) {
            // 修复BUG: 原代码未检查行长度和闭合括号，单字符行"["会导致substring越界
            String newSection = line.substring(1, line.length() - 1).trim().toLowerCase();
            if (newSection.isEmpty()) {
                continue;
            }
            if (!newSection.equals("frame")) {
                currentAnimation = null;
            }
            if (!newSection.equals("cube") && !newSection.equals("frame")) {
                currentBone = null;
            }
            currentSection = newSection;
            currentCube = null;
            currentFrame = null;
            continue;
        }
                
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    continue;
                }
                
                String key = line.substring(0, equalsIndex).trim().toLowerCase();
                String value = line.substring(equalsIndex + 1).trim();
                
                try {
                    switch (currentSection) {
                        case "model":
                            parseModelProperty(model, key, value);
                            break;
                        case "bone":
                            if (currentBone == null) {
                                currentBone = new Bone();
                                model.bones.add(currentBone);
                            }
                            parseBoneProperty(currentBone, key, value);
                            if (key.equals(KEY_NAME) && !value.isEmpty()) {
                                currentBone.name = value;
                            }
                            break;
                        case "cube":
                            if (currentCube == null) {
                                currentCube = new Cube();
                                if (currentBone != null) {
                                    currentBone.cubes.add(currentCube);
                                } else {
                                    model.rootCubes.add(currentCube);
                                }
                            }
                            parseCubeProperty(currentCube, key, value);
                            break;
                        case "animation":
                            if (currentAnimation == null) {
                                currentAnimation = new Animation();
                                model.animations.add(currentAnimation);
                            }
                            parseAnimationProperty(currentAnimation, key, value);
                            break;
                        case "frame":
                            if (currentFrame == null) {
                                currentFrame = new AnimationFrame();
                                if (currentAnimation != null) {
                                    currentAnimation.frames.add(currentFrame);
                                }
                            }
                            parseFrameProperty(currentFrame, key, value);
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Parse error at line {}: {} = {}",
                                lineNumber, key, value, e);
                    }
                }

                // 修复BUG: 原代码此处检查line.equals("}")是死代码，
                // 因为前面startsWith("[")的分支已continue，且trim后的非section行不会等于"}"
                // 移除无法到达的死代码块
            }
            
        } catch (Exception e) {
            if (allowWarn()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to parse .jem file", e);
            }
            return null;
        }
        
        model.finalizeModel();
        return model;
    }

    private static void parseModelProperty(CemModel model, String key, String value) {
        switch (key) {
            case KEY_NAME:
                model.name = value;
                break;
            case KEY_TEXTURE:
                model.texturePath = value;
                break;
            case KEY_MIRRORED:
                model.mirrored = Boolean.parseBoolean(value);
                break;
            case KEY_SHADE:
                model.shade = Boolean.parseBoolean(value);
                break;
            case KEY_ALPHA:
                try {
                    model.alpha = Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    // 修复BUG: 原代码吞掉异常无日志，现记录警告便于排查
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Invalid alpha value: {}", value);
                    }
                }
                break;
        }
    }

    private static void parseBoneProperty(Bone bone, String key, String value) {
        switch (key) {
            case KEY_NAME:
                bone.name = value;
                break;
            case KEY_PARENT:
                bone.parent = value;
                break;
            case KEY_PIVOT:
                bone.pivot = parseFloatArray(value);
                break;
            case KEY_ROTATION:
                bone.rotation = parseFloatArray(value);
                break;
            case KEY_TRANSLATION:
                bone.translation = parseFloatArray(value);
                break;
            case KEY_SCALE:
                bone.scale = parseFloatArray(value);
                break;
            case KEY_INFLUENCE:
                try {
                    bone.influence = Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    // 修复BUG: 原代码吞掉异常无日志
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Invalid influence value: {}", value);
                    }
                }
                break;
            case KEY_VISIBILITY:
                bone.visibility = parseVisibility(value);
                break;
        }
    }

    private static void parseCubeProperty(Cube cube, String key, String value) {
        switch (key) {
            case KEY_NAME:
                cube.name = value;
                break;
            case KEY_OFFSET:
                cube.offset = parseFloatArray(value);
                break;
            case KEY_SIZE:
                cube.size = parseFloatArray(value);
                break;
            case KEY_UV:
                cube.uv = parseFloatArray(value);
                break;
            case KEY_ROTATION:
                cube.rotation = parseFloatArray(value);
                break;
            case KEY_PIVOT:
                cube.pivot = parseFloatArray(value);
                break;
            case KEY_SCALE:
                cube.scale = parseFloatArray(value);
                break;
            case KEY_SHADE:
                cube.shade = Boolean.parseBoolean(value);
                break;
            case KEY_ALPHA:
                try {
                    cube.alpha = Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    // 修复BUG: 原代码吞掉异常无日志
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Invalid cube alpha value: {}", value);
                    }
                }
                break;
            case KEY_TEXTURE:
                cube.textureIndex = parseTextureIndex(value);
                break;
        }
    }

    private static void parseAnimationProperty(Animation animation, String key, String value) {
        switch (key) {
            case KEY_NAME:
                animation.name = value;
                break;
            case KEY_BONE:
                animation.targetBone = value;
                break;
            case KEY_TYPE:
                animation.type = AnimationType.fromString(value);
                break;
            case KEY_TIME:
                try {
                    animation.duration = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    // 修复BUG: 原代码吞掉异常无日志
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Invalid animation duration value: {}", value);
                    }
                }
                break;
            case KEY_LOOP:
                animation.loop = Boolean.parseBoolean(value);
                break;
            case KEY_INTERPOLATE:
                animation.interpolate = Boolean.parseBoolean(value);
                break;
            case KEY_CONDITION:
                animation.condition = value;
                break;
            case KEY_FRAME:
                animation.frameCount = parseIntValue(value);
                break;
        }
    }

    private static void parseFrameProperty(AnimationFrame frame, String key, String value) {
        switch (key) {
            case KEY_TIME:
                try {
                    frame.time = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    // 修复BUG: 原代码吞掉异常无日志
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Invalid frame time value: {}", value);
                    }
                }
                break;
            case KEY_ROTATION:
                frame.rotation = parseFloatArray(value);
                break;
            case KEY_TRANSLATION:
                frame.translation = parseFloatArray(value);
                break;
            case KEY_SCALE:
                frame.scale = parseFloatArray(value);
                break;
            case KEY_VISIBILITY:
                frame.visibility = parseVisibility(value);
                break;
        }
    }

    private static float[] parseFloatArray(String value) {
        if (value == null || value.isEmpty()) {
            return new float[]{0, 0, 0};
        }
        String[] parts = value.split(",");
        float[] result = new float[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                result[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static int parseIntValue(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Cube.Visibility parseVisibility(String value) {
        // 修复BUG: 原代码未判空，value.toLowerCase()在value为null时抛出NPE
        if (value == null || value.isEmpty()) {
            return Cube.Visibility.PARENT;
        }
        switch (value.toLowerCase()) {
            case "false":
                return Cube.Visibility.FALSE;
            case "true":
                return Cube.Visibility.TRUE;
            case "parent":
                return Cube.Visibility.PARENT;
            default:
                return Cube.Visibility.PARENT;
        }
    }

    private static int parseTextureIndex(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static class CemModel {
        public String name = "";
        public String texturePath = "";
        public boolean mirrored = false;
        public boolean shade = true;
        public float alpha = 1.0f;
        
        public List<Bone> bones = new ArrayList<>();
        public List<Cube> rootCubes = new ArrayList<>();
        public List<Animation> animations = new ArrayList<>();
        
        public Map<String, Bone> boneMap = new HashMap<>();
        public Map<String, Animation> animationMap = new HashMap<>();
        
        public void finalizeModel() {
            for (Bone bone : bones) {
                boneMap.put(bone.name, bone);
            }
            
            for (Animation animation : animations) {
                animationMap.put(animation.name, animation);
            }
            
            for (Bone bone : bones) {
                if (!bone.parent.isEmpty()) {
                    Bone parentBone = boneMap.get(bone.parent);
                    if (parentBone != null) {
                        parentBone.children.add(bone);
                        bone.parentBone = parentBone;
                    }
                }
            }
            
            if (alpha < 0) alpha = 0;
            if (alpha > 1) alpha = 1;
        }
        
        public boolean hasAnimations() {
            return !animations.isEmpty();
        }
        
        public Animation getAnimation(String name) {
            return animationMap.get(name);
        }
        
        public Bone getBone(String name) {
            return boneMap.get(name);
        }
    }

    public static class Bone {
        public String name = "";
        public String parent = "";
        public Bone parentBone;
        public List<Bone> children = new ArrayList<>();
        public List<Cube> cubes = new ArrayList<>();
        
        public float[] pivot = new float[]{0, 0, 0};
        public float[] rotation = new float[]{0, 0, 0};
        public float[] translation = new float[]{0, 0, 0};
        public float[] scale = new float[]{1, 1, 1};
        public float influence = 1.0f;
        
        public Cube.Visibility visibility = Cube.Visibility.PARENT;
        public boolean enabled = true;
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            for (Bone child : children) {
                child.setEnabled(enabled);
            }
        }
    }

    public static class Cube {
        public String name = "";
        public float[] offset = new float[]{0, 0, 0};
        public float[] size = new float[]{1, 1, 1};
        public float[] uv = new float[]{0, 0};
        public float[] rotation = new float[]{0, 0, 0};
        public float[] pivot = new float[]{0, 0, 0};
        public float[] scale = new float[]{1, 1, 1};
        
        public boolean shade = true;
        public float alpha = 1.0f;
        public int textureIndex = 0;
        
        public Visibility visibility = Visibility.PARENT;
        
        public enum Visibility {
            TRUE, FALSE, PARENT
        }
    }

    public static class Animation {
        public String name = "";
        public String targetBone = "";
        public AnimationType type = AnimationType.ROTATION;
        public int duration = 1000;
        public int frameCount = 0;
        public boolean loop = true;
        public boolean interpolate = true;
        public String condition = "";
        
        public List<AnimationFrame> frames = new ArrayList<>();
        
        public boolean hasCondition() {
            return !condition.isEmpty();
        }
        
        public boolean matchesCondition(String gameState) {
            if (!hasCondition()) return true;
            return condition.equalsIgnoreCase(gameState);
        }
    }

    public static class AnimationFrame {
        public int time = 0;
        public float[] rotation = new float[]{0, 0, 0};
        public float[] translation = new float[]{0, 0, 0};
        public float[] scale = new float[]{1, 1, 1};
        public Cube.Visibility visibility = Cube.Visibility.PARENT;
    }

    public enum AnimationType {
        ROTATION,
        TRANSLATION,
        SCALE,
        VISIBILITY,
        ALL;
        
        public static AnimationType fromString(String value) {
            if (value == null) return ROTATION;
            switch (value.toLowerCase()) {
                case "rotation":
                    return ROTATION;
                case "translation":
                    return TRANSLATION;
                case "scale":
                    return SCALE;
                case "visibility":
                    return VISIBILITY;
                case "all":
                    return ALL;
                default:
                    return ROTATION;
            }
        }
    }
}