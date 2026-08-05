package com.coresplit.compat.remote;

import com.coresplit.CoreSplitMod;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris 配置 schema。
 *
 * <p>从 GitHub 拉取的 {@code IrisConfig.java} 源码中解析字段声明（名称/类型/默认值/是否静态），
 * 用于让 CoreSplit 的反射读取跨 Iris 版本自适应字段名变化。
 *
 * <p>当远程拉取失败或离线时，使用 {@link #builtIn()} 提供的内置降级默认值
 * （基于 Iris 已知版本的调研结论）。
 *
 * <p>线程安全：构造后不可变（字段 Map 用 unmodifiableMap 包装）。
 */
public final class IrisConfigSchema {

    /** 字段数量上限，防止恶意/异常源码注入超大 map（项目约束 256-1024） */
    private static final int MAX_FIELDS = 256;

    /** 匹配 Java 字段声明：(private|protected|public) [static] [final] Type name = value; */
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?:private|protected|public)\\s+" +
                    "(?<static>static\\s+)?" +
                    "(?:final\\s+)?" +
                    "(?<type>[A-Za-z_$][\\w$.\\[\\]]*)\\s+" +
                    "(?<name>[A-Za-z_$][\\w$]*)\\s*" +
                    "(?:=\\s*(?<value>[^;\\n]+))?\\s*;"
    );

    private final Map<String, FieldInfo> fields;
    private final String sourceVersion;
    private final long parsedAt;

    /** 字段信息记录 */
    public record FieldInfo(String name, String type, String defaultValue, boolean isStatic) {}

    public IrisConfigSchema(Map<String, FieldInfo> fields, String sourceVersion) {
        if (fields == null) fields = Collections.emptyMap();
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Field count exceeds limit: " + fields.size());
        }
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.sourceVersion = sourceVersion == null ? "unknown" : sourceVersion;
        this.parsedAt = System.currentTimeMillis();
    }

    public Map<String, FieldInfo> getFields() { return fields; }

    public FieldInfo getField(String name) { return fields.get(name); }

    public String getSourceVersion() { return sourceVersion; }

    public long getParsedAt() { return parsedAt; }

    public int getFieldCount() { return fields.size(); }

    /**
     * 从 GitHub 拉取的 IrisConfig.java 源码中解析字段声明。
     * 解析失败（空源码等）返回空 schema，不抛异常。
     */
    public static IrisConfigSchema parse(String javaSource) {
        Map<String, FieldInfo> result = new LinkedHashMap<>();
        if (javaSource == null || javaSource.isEmpty()) {
            return new IrisConfigSchema(result, "empty");
        }
        try {
            Matcher m = FIELD_PATTERN.matcher(javaSource);
            while (m.find() && result.size() < MAX_FIELDS) {
                String name = m.group("name");
                String type = m.group("type");
                String value = m.group("value");
                String staticGroup = m.group("static");
                if (name != null && type != null && !name.isEmpty()) {
                    // 过滤掉方法声明的误匹配（type 含括号说明是方法）
                    if (type.contains("(") || type.contains(")")) continue;
                    result.put(name, new FieldInfo(
                            name, type,
                            value == null ? "" : value.trim(),
                            staticGroup != null));
                }
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] IrisConfigSchema parse failed, returning partial result", e);
        }
        return new IrisConfigSchema(result, "remote-" + System.currentTimeMillis());
    }

    /**
     * 内置降级默认值（基于 Iris 已知版本调研）。
     * 离线或远程拉取失败时使用。
     */
    public static IrisConfigSchema builtIn() {
        Map<String, FieldInfo> f = new LinkedHashMap<>();
        f.put("shaderPack", new FieldInfo("shaderPack", "String", "(internal)", false));
        f.put("enableShaders", new FieldInfo("enableShaders", "boolean", "true", false));
        f.put("allowUnknownShaders", new FieldInfo("allowUnknownShaders", "boolean", "false", false));
        f.put("enableDebugOptions", new FieldInfo("enableDebugOptions", "boolean", "false", false));
        f.put("disableUpdateMessage", new FieldInfo("disableUpdateMessage", "boolean", "false", false));
        f.put("maxShadowRenderDistance", new FieldInfo("maxShadowRenderDistance", "int", "32", false));
        f.put("colorSpace", new FieldInfo("colorSpace", "String", "SRGB", false));
        return new IrisConfigSchema(f, "builtin-fallback");
    }

    /**
     * 序列化为简单 JSON 字符串（磁盘缓存用，不引入 Jackson 依赖）。
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sourceVersion\":\"").append(escapeJson(sourceVersion)).append("\",");
        sb.append("\"parsedAt\":").append(parsedAt).append(",");
        sb.append("\"fields\":[");
        boolean first = true;
        for (FieldInfo fi : fields.values()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":\"").append(escapeJson(fi.name())).append("\",");
            sb.append("\"type\":\"").append(escapeJson(fi.type())).append("\",");
            sb.append("\"defaultValue\":\"").append(escapeJson(fi.defaultValue())).append("\",");
            sb.append("\"isStatic\":").append(fi.isStatic()).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 从 JSON 反序列化。解析失败返回 builtIn()。
     */
    public static IrisConfigSchema deserialize(String json) {
        if (json == null || json.isEmpty()) return builtIn();
        try {
            Map<String, FieldInfo> result = new LinkedHashMap<>();
            String sv = extractJsonStringField(json, "sourceVersion");
            // 提取 fields 数组
            int fieldsIdx = json.indexOf("\"fields\"");
            if (fieldsIdx >= 0) {
                int arrStart = json.indexOf('[', fieldsIdx);
                int arrEnd = json.lastIndexOf(']');
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arr = json.substring(arrStart + 1, arrEnd);
                    // 简单按对象分割
                    int pos = 0;
                    while (pos < arr.length() && result.size() < MAX_FIELDS) {
                        int objStart = arr.indexOf('{', pos);
                        if (objStart < 0) break;
                        int objEnd = arr.indexOf('}', objStart);
                        if (objEnd < 0) break;
                        String obj = arr.substring(objStart + 1, objEnd);
                        String name = extractJsonStringField(obj, "name");
                        String type = extractJsonStringField(obj, "type");
                        String value = extractJsonStringField(obj, "defaultValue");
                        boolean isStatic = obj.contains("\"isStatic\":true");
                        if (name != null) {
                            result.put(name, new FieldInfo(name,
                                    type == null ? "" : type,
                                    value == null ? "" : value,
                                    isStatic));
                        }
                        pos = objEnd + 1;
                    }
                }
            }
            return new IrisConfigSchema(result, sv == null ? "deserialized" : sv);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] IrisConfigSchema deserialize failed, using builtin", e);
            return builtIn();
        }
    }

    private static String extractJsonStringField(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\n", "\n")
                .replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }
}
