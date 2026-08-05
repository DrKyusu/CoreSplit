package com.coresplit.compat.remote;

import com.coresplit.CoreSplitMod;
import com.coresplit.compat.RenderCompatDetector;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远程 schema 与 release 信息拉取器。
 *
 * <p>异步从 GitHub 拉取 Iris/Sodium 的配置类源码与最新 release 信息：
 * <ul>
 *   <li>Iris schema: raw.githubusercontent.com Iris 26.2 分支的 IrisConfig.java</li>
 *   <li>Sodium schema: raw.githubusercontent.com sodium trunk 分支的 SodiumOptions.java</li>
 *   <li>release 信息: api.github.com /releases/latest（限流 60/h 未认证）</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>守护线程异步执行，绝不阻塞主线程 / 客户端 tick</li>
 *   <li>24h 磁盘缓存（{@link SchemaCache}），离线降级到内置 fallback</li>
 *   <li>GitHub API 限流处理（403/429 + X-RateLimit-Reset header）</li>
 *   <li>日志告警用 {@link RateLimiter} 限速（≤10/10秒，项目硬约束）</li>
 *   <li>所有失败静默降级，保持当前内存值</li>
 * </ul>
 */
public class RemoteSchemaFetcher {

    // === URL 模板（已验证分支名：Iris 用 26.2，Sodium 用 trunk） ===
    private static final String IRIS_SCHEMA_URL =
            "https://raw.githubusercontent.com/IrisShaders/Iris/26.2/common/src/main/java/net/irisshaders/iris/config/IrisConfig.java";
    /** Iris 26.2 分支拉取失败时的回退分支 */
    private static final String IRIS_SCHEMA_URL_FALLBACK =
            "https://raw.githubusercontent.com/IrisShaders/Iris/26.1/common/src/main/java/net/irisshaders/iris/config/IrisConfig.java";
    private static final String SODIUM_SCHEMA_URL =
            "https://raw.githubusercontent.com/CaffeineMC/sodium/trunk/common/src/main/java/net/caffeinemc/mods/sodium/client/gui/SodiumOptions.java";
    private static final String IRIS_RELEASE_URL =
            "https://api.github.com/repos/IrisShaders/Iris/releases/latest";
    private static final String SODIUM_RELEASE_URL =
            "https://api.github.com/repos/CaffeineMC/sodium/releases/latest";

    // === 限流常量 ===
    private static final int GITHUB_API_RATE_LIMIT_PER_HOUR = 60;
    private static final long RATE_LIMIT_RESET_MS = 3_600_000L;
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000; // 24h
    private static final int HTTP_TIMEOUT_SECONDS = 10;

    // === 缓存文件名 ===
    private static final String IRIS_SCHEMA_CACHE = "iris-schema.json";
    private static final String SODIUM_SCHEMA_CACHE = "sodium-schema.json";
    private static final String IRIS_RELEASE_CACHE = "iris-release.json";
    private static final String SODIUM_RELEASE_CACHE = "sodium-release.json";

    // === 内置降级默认值 ===
    private static final IrisConfigSchema FALLBACK_IRIS_SCHEMA = IrisConfigSchema.builtIn();
    private static final SodiumConfigSchema FALLBACK_SODIUM_SCHEMA = SodiumConfigSchema.builtIn();

    // === 状态（volatile 供主线程读取） ===
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean githubRateLimited = new AtomicBoolean(false);
    private final AtomicLong githubRateLimitResetAt = new AtomicLong(0);
    private final AtomicInteger remainingGithubCalls = new AtomicInteger(GITHUB_API_RATE_LIMIT_PER_HOUR);

    private volatile IrisConfigSchema irisSchema = FALLBACK_IRIS_SCHEMA;
    private volatile SodiumConfigSchema sodiumSchema = FALLBACK_SODIUM_SCHEMA;
    private volatile ReleaseInfo irisLatestRelease = ReleaseInfo.UNKNOWN;
    private volatile ReleaseInfo sodiumLatestRelease = ReleaseInfo.UNKNOWN;

    private volatile HttpClient httpClient;
    private volatile ExecutorService daemonExecutor;

    private final Path cacheDir;
    private final SchemaCache schemaCache;
    private final RateLimiter logLimiter = new RateLimiter(10, 10_000L);

    // === 单例 ===
    private static volatile RemoteSchemaFetcher instance;

    public static RemoteSchemaFetcher getInstance() {
        RemoteSchemaFetcher result = instance;
        if (result == null) {
            synchronized (RemoteSchemaFetcher.class) {
                result = instance;
                if (result == null) {
                    result = new RemoteSchemaFetcher();
                    instance = result;
                }
            }
        }
        return result;
    }

    private RemoteSchemaFetcher() {
        // 修复BUG: FabricLoader.getInstance() 在非 Fabric 环境（如单元测试）下返回 null，
        // 导致 getConfigDir() NPE，使 compareVersions/isVersionOutdated 等纯逻辑方法无法测试。
        // 防御性回退到系统临时目录，保证生产与测试环境均能构造实例。
        this.cacheDir = resolveCacheDir();
        this.schemaCache = new SchemaCache(cacheDir, CACHE_TTL_MS);
    }

    private static Path resolveCacheDir() {
        try {
            return FabricLoader.getInstance().getConfigDir()
                    .resolve("coresplit").resolve("cache");
        } catch (Throwable t) {
            // 非 Fabric 环境（单元测试）回退到系统临时目录
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"))
                    .resolve("coresplit").resolve("cache");
            try {
                Files.createDirectories(tmp);
            } catch (Exception ignored) {
                // SchemaCache.save 时会再次尝试创建
            }
            return tmp;
        }
    }

    // === 启动 ===

    /**
     * 启动异步拉取。幂等：重复调用无副作用。
     * 立即返回，不阻塞主线程。
     */
    public void startAsync() {
        if (!started.compareAndSet(false, true)) return;
        daemonExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .executor(daemonExecutor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        daemonExecutor.submit(this::fetchInitial);
        CoreSplitMod.LOGGER.info("[CoreSplit] RemoteSchemaFetcher started (async, cacheDir={})", cacheDir);
    }

    /**
     * 关闭守护线程。在客户端停止时调用。
     */
    public void shutdown() {
        ExecutorService ex = daemonExecutor;
        if (ex == null) return;
        try {
            ex.shutdown();
            if (!ex.awaitTermination(2, TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ex.shutdownNow();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] RemoteSchemaFetcher shutdown failed", e);
        }
    }

    // === 拉取流程 ===

    private void fetchInitial() {
        // 1. 先加载磁盘缓存（24h 内有效则直接用，避免启动后 0 信息可用）
        loadFromDiskCache();

        // 2. 异步拉取远端（schema 走 raw 不受限；release 走 API 受限流）
        daemonExecutor.submit(this::fetchIrisSchema);
        daemonExecutor.submit(this::fetchSodiumSchema);
        daemonExecutor.submit(this::fetchIrisRelease);
        daemonExecutor.submit(this::fetchSodiumRelease);
    }

    private void loadFromDiskCache() {
        try {
            if (schemaCache.isFresh(IRIS_SCHEMA_CACHE)) {
                String json = schemaCache.load(IRIS_SCHEMA_CACHE);
                if (json != null) {
                    irisSchema = IrisConfigSchema.deserialize(json);
                    CoreSplitMod.LOGGER.info("[CoreSplit] Loaded iris-schema.json from disk cache");
                }
            }
            if (schemaCache.isFresh(SODIUM_SCHEMA_CACHE)) {
                String json = schemaCache.load(SODIUM_SCHEMA_CACHE);
                if (json != null) {
                    sodiumSchema = SodiumConfigSchema.deserialize(json);
                    CoreSplitMod.LOGGER.info("[CoreSplit] Loaded sodium-schema.json from disk cache");
                }
            }
            if (schemaCache.isFresh(IRIS_RELEASE_CACHE)) {
                String json = schemaCache.load(IRIS_RELEASE_CACHE);
                if (json != null) {
                    irisLatestRelease = ReleaseInfo.deserialize(json);
                }
            }
            if (schemaCache.isFresh(SODIUM_RELEASE_CACHE)) {
                String json = schemaCache.load(SODIUM_RELEASE_CACHE);
                if (json != null) {
                    sodiumLatestRelease = ReleaseInfo.deserialize(json);
                }
            }
        } catch (Exception e) {
            if (logLimiter.tryAcquire()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] loadFromDiskCache failed", e);
            }
        }
    }

    private void fetchIrisSchema() {
        try {
            String src = fetchTextWithFallback(IRIS_SCHEMA_URL, IRIS_SCHEMA_URL_FALLBACK, false);
            if (src == null) return;
            IrisConfigSchema parsed = IrisConfigSchema.parse(src);
            irisSchema = parsed;
            schemaCache.save(IRIS_SCHEMA_CACHE, parsed.serialize());
            CoreSplitMod.LOGGER.info("[CoreSplit] Iris schema fetched ({} fields, source={})",
                    parsed.getFieldCount(), parsed.getSourceVersion());
        } catch (Exception e) {
            if (logLimiter.tryAcquire()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to fetch Iris schema, using current value", e);
            }
        }
    }

    private void fetchSodiumSchema() {
        try {
            String src = fetchText(SODIUM_SCHEMA_URL, false);
            if (src == null) return;
            SodiumConfigSchema parsed = SodiumConfigSchema.parse(src);
            sodiumSchema = parsed;
            schemaCache.save(SODIUM_SCHEMA_CACHE, parsed.serialize());
            CoreSplitMod.LOGGER.info("[CoreSplit] Sodium schema fetched ({} fields, source={})",
                    parsed.getFieldCount(), parsed.getSourceVersion());
        } catch (Exception e) {
            if (logLimiter.tryAcquire()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to fetch Sodium schema, using current value", e);
            }
        }
    }

    private void fetchIrisRelease() {
        if (!acquireGithubSlot()) return;
        try {
            String json = fetchText(IRIS_RELEASE_URL, true);
            if (json == null) return;
            ReleaseInfo info = ReleaseInfo.parseFromGithubJson(json);
            irisLatestRelease = info;
            schemaCache.save(IRIS_RELEASE_CACHE, info.serialize());
            checkVersionAlert("iris", info);
        } catch (Exception e) {
            if (logLimiter.tryAcquire()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to fetch Iris release info", e);
            }
        }
    }

    private void fetchSodiumRelease() {
        if (!acquireGithubSlot()) return;
        try {
            String json = fetchText(SODIUM_RELEASE_URL, true);
            if (json == null) return;
            ReleaseInfo info = ReleaseInfo.parseFromGithubJson(json);
            sodiumLatestRelease = info;
            schemaCache.save(SODIUM_RELEASE_CACHE, info.serialize());
            checkVersionAlert("sodium", info);
        } catch (Exception e) {
            if (logLimiter.tryAcquire()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to fetch Sodium release info", e);
            }
        }
    }

    /**
     * 拉取文本，主 URL 失败时尝试回退 URL。
     */
    private String fetchTextWithFallback(String primaryUrl, String fallbackUrl, boolean isGithubApi) {
        String result = fetchText(primaryUrl, isGithubApi);
        if (result != null) return result;
        if (fallbackUrl != null && !fallbackUrl.isEmpty()) {
            return fetchText(fallbackUrl, isGithubApi);
        }
        return null;
    }

    /**
     * 拉取文本。失败返回 null（不抛异常给调用方，由调用方决定是否记录）。
     */
    private String fetchText(String url, boolean isGithubApi) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("User-Agent", "CoreSplit-Mod/2.0")
                    .header("Accept", isGithubApi ? "application/vnd.github+json" : "text/plain")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 403 || code == 429) {
                handleRateLimit(resp);
                return null;
            }
            if (code != 200) {
                if (logLimiter.tryAcquire()) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] HTTP {} for {}", code, url);
                }
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            if (logLimiter.tryAcquire()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Fetch failed for {}", url, e);
            }
            return null;
        }
    }

    // === GitHub API 限流 ===

    private boolean acquireGithubSlot() {
        long now = System.currentTimeMillis();
        if (githubRateLimited.get() && now < githubRateLimitResetAt.get()) {
            return false;
        }
        if (githubRateLimited.get() && now >= githubRateLimitResetAt.get()) {
            // 限流窗口已过，重置
            if (githubRateLimited.compareAndSet(true, false)) {
                remainingGithubCalls.set(GITHUB_API_RATE_LIMIT_PER_HOUR);
            }
        }
        while (true) {
            int cur = remainingGithubCalls.get();
            if (cur <= 0) {
                githubRateLimited.set(true);
                githubRateLimitResetAt.set(now + RATE_LIMIT_RESET_MS);
                if (logLimiter.tryAcquire()) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] GitHub API rate limit reached, retrying in 1 hour");
                }
                return false;
            }
            if (remainingGithubCalls.compareAndSet(cur, cur - 1)) return true;
        }
    }

    private void handleRateLimit(HttpResponse<String> resp) {
        githubRateLimited.set(true);
        String reset = resp.headers().firstValue("X-RateLimit-Reset").orElse(null);
        if (reset != null) {
            try {
                githubRateLimitResetAt.set(Long.parseLong(reset) * 1000L);
            } catch (NumberFormatException ignored) {
                githubRateLimitResetAt.set(System.currentTimeMillis() + RATE_LIMIT_RESET_MS);
            }
        } else {
            githubRateLimitResetAt.set(System.currentTimeMillis() + RATE_LIMIT_RESET_MS);
        }
        remainingGithubCalls.set(0);
        if (logLimiter.tryAcquire()) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] GitHub API rate limited until {}",
                    new java.util.Date(githubRateLimitResetAt.get()));
        }
    }

    private void checkVersionAlert(String modId, ReleaseInfo info) {
        if (info == null || info == ReleaseInfo.UNKNOWN || "?".equals(info.tag())) return;
        RenderCompatDetector detector = RenderCompatDetector.getInstance();
        String installed = "iris".equals(modId) ? detector.getIrisVersion() : detector.getSodiumVersion();
        if (installed == null || "?".equals(installed)) return;
        if (isVersionOutdated(installed, info.tag())) {
            CoreSplitMod.LOGGER.info("[CoreSplit] {} update available: installed={}, latest={}",
                    modId, installed, info.tag());
        }
    }

    /**
     * 语义版本对比。a < b 返回负数，a == b 返回 0，a > b 返回正数。
     * 仅比较数字段，忽略非数字前缀（如 v1.2.3 → 1.2.3）。
     */
    public int compareVersions(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.]", "").split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int ia = i < pa.length && !pa[i].isEmpty() ? parseIntSafe(pa[i]) : 0;
            int ib = i < pb.length && !pb[i].isEmpty() ? parseIntSafe(pb[i]) : 0;
            if (ia != ib) return Integer.compare(ia, ib);
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    public boolean isVersionOutdated(String installedVersion, String latestTag) {
        if (installedVersion == null || latestTag == null
                || "?".equals(installedVersion) || "?".equals(latestTag)) return false;
        return compareVersions(installedVersion, latestTag) < 0;
    }

    // === 公开 API（供 CompatCoordinator / F3 overlay 读取） ===

    public IrisConfigSchema getIrisSchema() { return irisSchema; }
    public SodiumConfigSchema getSodiumSchema() { return sodiumSchema; }
    public ReleaseInfo getIrisLatestRelease() { return irisLatestRelease; }
    public ReleaseInfo getSodiumLatestRelease() { return sodiumLatestRelease; }

    public boolean isGithubRateLimited() { return githubRateLimited.get(); }
    public boolean isStarted() { return started.get(); }

    // === 内部类 ===

    private static class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "coresplit-schema-fetcher-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    }

    /**
     * Release 信息记录。
     */
    public record ReleaseInfo(String tag, String name, String publishedAt, String htmlUrl) {
        public static final ReleaseInfo UNKNOWN = new ReleaseInfo("?", "?", "?", "");

        /**
         * 从 GitHub API /releases/latest 的 JSON 响应中解析。
         * 用正则提取顶层字符串字段（不引入 Jackson）。
         */
        public static ReleaseInfo parseFromGithubJson(String json) {
            if (json == null || json.isEmpty()) return UNKNOWN;
            String tag = extractJsonField(json, "tag_name");
            String name = extractJsonField(json, "name");
            String publishedAt = extractJsonField(json, "published_at");
            String htmlUrl = extractJsonField(json, "html_url");
            return new ReleaseInfo(
                    tag == null ? "?" : tag,
                    name == null ? "?" : name,
                    publishedAt == null ? "?" : publishedAt,
                    htmlUrl == null ? "" : htmlUrl);
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"tag\":\"").append(esc(tag)).append("\",");
            sb.append("\"name\":\"").append(esc(name)).append("\",");
            sb.append("\"publishedAt\":\"").append(esc(publishedAt)).append("\",");
            sb.append("\"htmlUrl\":\"").append(esc(htmlUrl)).append("\"}");
            return sb.toString();
        }

        public static ReleaseInfo deserialize(String json) {
            if (json == null || json.isEmpty()) return UNKNOWN;
            try {
                String tag = extractJsonField(json, "tag");
                String name = extractJsonField(json, "name");
                String publishedAt = extractJsonField(json, "publishedAt");
                String htmlUrl = extractJsonField(json, "htmlUrl");
                return new ReleaseInfo(
                        tag == null ? "?" : tag,
                        name == null ? "?" : name,
                        publishedAt == null ? "?" : publishedAt,
                        htmlUrl == null ? "" : htmlUrl);
            } catch (Exception e) {
                return UNKNOWN;
            }
        }

        private static String extractJsonField(String json, String fieldName) {
            Pattern p = Pattern.compile(
                    "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
            Matcher m = p.matcher(json);
            if (m.find()) {
                return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            return null;
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
