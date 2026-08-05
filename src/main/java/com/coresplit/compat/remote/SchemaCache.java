package com.coresplit.compat.remote;

import com.coresplit.CoreSplitMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 远程 schema 的磁盘缓存。
 *
 * <p>目录 {@code <configdir>/coresplit/cache/}，24h TTL。
 * 防御性约束（项目硬约束）：
 * <ul>
 *   <li>文件名仅允许 {@code [a-zA-Z0-9_\-]+\.json}，防止路径穿越</li>
 *   <li>单文件大小上限 1MB</li>
 *   <li>文件数上限 16，超量按 mtime 淘汰最旧</li>
 * </ul>
 *
 * <p>线程安全：用 ConcurrentMap 跟踪写入时间；文件操作无锁（单守护线程写入）。
 */
public class SchemaCache {

    private static final int MAX_FILES = 16;
    private static final long MAX_FILE_SIZE = 1L * 1024 * 1024; // 1 MB

    /** 仅允许简单 JSON 文件名，防止路径穿越 */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9_\\-]+\\.json");

    private final Path cacheDir;
    private final long ttlMs;
    private final ConcurrentMap<String, Long> lastWriteTime = new ConcurrentHashMap<>();

    public SchemaCache(Path cacheDir, long ttlMs) {
        this.cacheDir = cacheDir;
        this.ttlMs = ttlMs;
    }

    /**
     * 检查缓存文件是否新鲜（存在 + age < ttl + 大小合规）。
     */
    public boolean isFresh(String name) {
        if (!isSafeName(name)) return false;
        Path p = cacheDir.resolve(name);
        try {
            if (!Files.exists(p)) return false;
            long size = Files.size(p);
            if (size > MAX_FILE_SIZE) return false;
            FileTime mtime = Files.getLastModifiedTime(p);
            long age = System.currentTimeMillis() - mtime.toMillis();
            return age < ttlMs;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 加载缓存文件内容。不存在或不合规返回 null。
     */
    public String load(String name) {
        if (!isSafeName(name)) return null;
        try {
            Path p = cacheDir.resolve(name);
            if (!Files.exists(p)) return null;
            long size = Files.size(p);
            if (size > MAX_FILE_SIZE) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Cache file {} size {} exceeds limit, ignored", name, size);
                return null;
            }
            return Files.readString(p);
        } catch (IOException e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to read cache {}", name, e);
            return null;
        }
    }

    /**
     * 保存内容到缓存文件。内容过大或文件名不合规时拒绝。
     */
    public void save(String name, String content) {
        if (!isSafeName(name)) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Rejected unsafe cache name: {}", name);
            return;
        }
        if (content == null || content.length() > MAX_FILE_SIZE) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Cache content for {} too large or null, not saved", name);
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            Path p = cacheDir.resolve(name);
            Files.writeString(p, content);
            lastWriteTime.put(name, System.currentTimeMillis());
            evictIfTooMany();
        } catch (IOException e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to write cache {}", name, e);
        }
    }

    /**
     * 删除缓存目录下所有文件（用于清理）。
     */
    public void clear() {
        try (Stream<Path> s = Files.list(cacheDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> isSafeName(p.getFileName().toString()))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {
        }
        lastWriteTime.clear();
    }

    private boolean isSafeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    /**
     * 文件数超 MAX_FILES 时按 mtime 删除最旧的。
     */
    private void evictIfTooMany() {
        try (Stream<Path> s = Files.list(cacheDir)) {
            var files = s.filter(Files::isRegularFile)
                    .filter(p -> isSafeName(p.getFileName().toString()))
                    .map(p -> {
                        try {
                            return new Object[]{p, Files.getLastModifiedTime(p).toMillis()};
                        } catch (IOException e) {
                            return new Object[]{p, 0L};
                        }
                    })
                    .sorted(Comparator.comparingLong(a -> (Long) a[1]))
                    .toList();
            if (files.size() <= MAX_FILES) return;
            int toDelete = files.size() - MAX_FILES;
            for (int i = 0; i < toDelete; i++) {
                Path victim = (Path) files.get(i)[0];
                try {
                    Files.deleteIfExists(victim);
                    lastWriteTime.remove(victim.getFileName().toString());
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Cache eviction scan failed", e);
        }
    }

    public Path getCacheDir() { return cacheDir; }
}
