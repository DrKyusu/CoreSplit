package com.coresplit.compat.remote;

import com.coresplit.CoreSplitMod;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sodium 配置 schema。
 *
 * <p>从 GitHub 拉取的 {@code SodiumOptions.java} 源码中解析字段声明，
 * 用于让 CoreSplit 的反射读取跨 Sodium 版本自适应字段名变化。
 *
 * <p>Sodium 配置类 {@code net.caffeinemc.mods.sodium.client.gui.SodiumOptions}
 * 包含 5 个嵌套配置组（Quality/Performance/Advanced/Debug/Notification），
 * 本类解析顶层字段及嵌套记录类的字段名。
 *
 * <p>线程安全：构造后不可变。
 */
public final class SodiumConfigSchema {

    private static final int MAX_FIELDS = 256;

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

    public record FieldInfo(String name, String type, String defaultValue, boolean isStatic) {}

    public SodiumConfigSchema(Map<String, FieldInfo> fields, String sourceVersion) {
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

    public int getFieldCount() { return fields.size(); }

    public static SodiumConfigSchema parse(String javaSource) {
        Map<String, FieldInfo> result = new LinkedHashMap<>();
        if (javaSource == null || javaSource.isEmpty()) {
            return new SodiumConfigSchema(result, "empty");
        }
        try {
            Matcher m = FIELD_PATTERN.matcher(javaSource);
            while (m.find() && result.size() < MAX_FIELDS) {
                String name = m.group("name");
                String type = m.group("type");
                String value = m.group("value");
                String staticGroup = m.group("static");
                if (name != null && type != null && !name.isEmpty()) {
                    if (type.contains("(") || type.contains(")")) continue;
                    result.put(name, new FieldInfo(
                            name, type,
                            value == null ? "" : value.trim(),
                            staticGroup != null));
                }
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] SodiumConfigSchema parse failed, returning partial result", e);
        }
        return new SodiumConfigSchema(result, "remote-" + System.currentTimeMillis());
    }

    /**
     * 内置降级默认值（基于 Sodium 已知版本调研）。
     * Sodium 没有独立帧率限制；这些是 SodiumOptions 的已知字段。
     */
    public static SodiumConfigSchema builtIn() {
        Map<String, FieldInfo> f = new LinkedHashMap<>();
        f.put("cloudHeight", new FieldInfo("cloudHeight", "int", "192", false));
        f.put("biomeBlend", new FieldInfo("biomeBlend", "int", "10", false));
        f.put("ambientOcclusion", new FieldInfo("ambientOcclusion", "boolean", "true", false));
        f.put("entityCulling", new FieldInfo("entityCulling", "boolean", "true", false));
        f.put("useFogOcclusion", new FieldInfo("useFogOcclusion", "boolean", "true", false));
        f.put("smoothLighting", new FieldInfo("smoothLighting", "boolean", "true", false));
        return new SodiumConfigSchema(f, "builtin-fallback");
    }

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

    public static SodiumConfigSchema deserialize(String json) {
        if (json == null || json.isEmpty()) return builtIn();
        try {
            Map<String, FieldInfo> result = new LinkedHashMap<>();
            String sv = extractJsonStringField(json, "sourceVersion");
            int fieldsIdx = json.indexOf("\"fields\"");
            if (fieldsIdx >= 0) {
                int arrStart = json.indexOf('[', fieldsIdx);
                int arrEnd = json.lastIndexOf(']');
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arr = json.substring(arrStart + 1, arrEnd);
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
            return new SodiumConfigSchema(result, sv == null ? "deserialized" : sv);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] SodiumConfigSchema deserialize failed, using builtin", e);
            return builtIn();
        }
    }

    private static String extractJsonStringField(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
