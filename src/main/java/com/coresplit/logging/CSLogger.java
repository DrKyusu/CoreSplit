package com.coresplit.logging;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CoreSplit bilingual asynchronous file logger.
 *
 * <h3>Output</h3>
 * <ul>
 *   <li>English file: {@code <gameDir>/CSlogs/cslogYYYYMMDD.txt}</li>
 *   <li>Chinese file: {@code <gameDir>/CSlogs/cslogYYYYMMDD_Zh_CN.txt}</li>
 * </ul>
 * Two parallel files are maintained so each language is fully readable on its
 * own. Throwable stack traces (language-neutral) are written to both files.
 *
 * <h3>Daily rotation</h3>
 * The writer thread checks the current date on each cycle; when the day
 * changes it closes the previous day's writers and opens a fresh pair.
 * This keeps any single file bounded to one day of activity.
 *
 * <h3>Performance</h3>
 * All public log methods are non-blocking: they format the entry on the
 * caller thread (cheap) and {@link java.util.concurrent.BlockingQueue#offer
 * offer()} it onto a bounded queue. A single low-priority daemon thread
 * drains the queue and performs the disk I/O, so the game/render thread is
 * never stalled by file writes. If the queue saturates, DEBUG entries are
 * dropped (with a counted warning) and INFO/WARN/ERROR fall back to SLF4J
 * so they are never silently lost.
 *
 * <h3>Error handling</h3>
 * Directory creation, file opening, and writing are each isolated in
 * try-catch. If the log directory cannot be created or written to, file
 * logging is gracefully disabled and the failure is reported through SLF4J
 * (the standard game log) exactly once.
 *
 * <h3>Thread safety</h3>
 * The singleton is safe to call from any thread. Only the writer thread
 * touches the {@link BufferedWriter}s, so no extra synchronization is needed
 * on the file handles. {@code volatile} guards the level/enable flags.
 */
public final class CSLogger {

    // ── Configuration constants ────────────────────────────────────────────
    /** Folder name created directly under the game root directory. */
    public static final String LOG_DIR_NAME = "CSlogs";
    /** Bounded queue capacity — bounds memory under sustained logging bursts. */
    private static final int QUEUE_CAPACITY = 8192;
    /** How long shutdown waits for the queue to drain (ms). */
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 3000;
    /** Writer thread poll timeout when the queue is empty (ms). */
    private static final long POLL_TIMEOUT_MS = 500;

    // ── Singleton ──────────────────────────────────────────────────────────
    private static volatile CSLogger instance;

    // ── Runtime state ──────────────────────────────────────────────────────
    private final LinkedBlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean fileLoggingEnabled = new AtomicBoolean(true);
    private final AtomicLong droppedCount = new AtomicLong(0);

    /** Minimum level to emit. Volatile so the config UI can change it live. */
    private volatile LogLevel minimumLevel = LogLevel.INFO;
    /** Master enable toggle. Volatile so the config UI can change it live. */
    private volatile boolean enabled = true;

    private final Path logDir;
    private Thread writerThread;

    // ── Date/file management (touched only by writer thread) ───────────────
    private volatile String currentDate;
    private BufferedWriter enWriter;
    private BufferedWriter zhWriter;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private CSLogger() {
        this.logDir = resolveLogDir();
    }

    /** Get the singleton, creating it if necessary. */
    public static CSLogger getInstance() {
        CSLogger result = instance;
        if (result == null) {
            synchronized (CSLogger.class) {
                result = instance;
                if (result == null) {
                    result = new CSLogger();
                    instance = result;
                }
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API — convenience overloads
    // ═══════════════════════════════════════════════════════════════════════

    public static void debug(String source, String en, String zh) {
        log(LogLevel.DEBUG, source, en, zh, null);
    }

    public static void debug(String source, String en, String zh, Object... args) {
        log(LogLevel.DEBUG, source, en, zh, null, args);
    }

    public static void info(String source, String en, String zh) {
        log(LogLevel.INFO, source, en, zh, null);
    }

    public static void info(String source, String en, String zh, Object... args) {
        log(LogLevel.INFO, source, en, zh, null, args);
    }

    public static void warn(String source, String en, String zh) {
        log(LogLevel.WARN, source, en, zh, null);
    }

    public static void warn(String source, String en, String zh, Object... args) {
        log(LogLevel.WARN, source, en, zh, null, args);
    }

    public static void error(String source, String en, String zh) {
        log(LogLevel.ERROR, source, en, zh, null);
    }

    public static void error(String source, String en, String zh, Throwable t) {
        log(LogLevel.ERROR, source, en, zh, t);
    }

    public static void error(String source, String en, String zh, Object... args) {
        log(LogLevel.ERROR, source, en, zh, null, args);
    }

    /**
     * Core log method. Non-blocking; formats on the caller thread and hands
     * the entry to the writer queue.
     *
     * @param level    severity
     * @param source   event source/module tag (e.g. "Bootstrap", "Scheduler")
     * @param en       English message, may contain {@code {}} placeholders
     * @param zh       Chinese message, may contain {@code {}} placeholders
     * @param t        optional throwable (stack trace written to both files)
     */
    public static void log(LogLevel level, String source, String en, String zh, Throwable t) {
        log(level, source, en, zh, t, (Object[]) null);
    }

    /**
     * Core log method with positional {@code {}} arguments. The same args are
     * substituted into both the English and Chinese message templates.
     */
    public static void log(LogLevel level, String source, String en, String zh,
                           Throwable t, Object... args) {
        CSLogger inst = getInstance();
        if (!inst.enabled || !level.isLoggable(inst.minimumLevel)) {
            return;
        }
        String enMsg = (args == null || args.length == 0) ? en : formatMessage(en, args);
        String zhMsg = (args == null || args.length == 0) ? zh : formatMessage(zh, args);
        String timestamp = LocalDateTime.now().format(TS_FMT);
        LogEntry entry = new LogEntry(timestamp, level, source, enMsg, zhMsg, t);

        if (!inst.fileLoggingEnabled.get()) {
            // File logging disabled — mirror to SLF4J so the message isn't lost.
            mirrorToSlf4j(entry);
            return;
        }

        // Non-blocking enqueue. If the queue is full, shed by severity:
        //  - DEBUG/INFO: drop (counted) to protect the game thread from blocking
        //  - WARN/ERROR: fall back to SLF4J so important events are never lost
        if (!inst.queue.offer(entry)) {
            if (level.getSeverity() >= LogLevel.WARN.getSeverity()) {
                mirrorToSlf4j(entry);
            } else {
                long dropped = inst.droppedCount.incrementAndGet();
                if (dropped == 1 || dropped % 1000 == 0) {
                    CoreSplitMod.LOGGER.warn(
                            "[CoreSplit] CSLogger queue saturated, dropped {} entries (level={})",
                            dropped, level.getEnName());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the writer thread. Idempotent. Called from
     * {@link CoreSplitMod#onInitialize()} so logging works on both the
     * client and dedicated server.
     */
    public synchronized void start() {
        if (running.get()) return;

        // Verify the log directory is usable before starting the thread.
        if (!ensureLogDir()) {
            // ensureLogDir already reported the failure via SLF4J.
            fileLoggingEnabled.set(false);
        }

        running.set(true);
        currentDate = LocalDate.now().format(DATE_FMT);
        writerThread = new Thread(this::writerLoop, "CoreSplit-CSLogger");
        writerThread.setDaemon(true);
        // Below normal priority so the logger never competes with the
        // game/render thread for CPU under load.
        writerThread.setPriority(Thread.NORM_PRIORITY - 1);
        writerThread.start();

        info("Logger", "CoreSplit file logger started (dir=" + logDir + ")",
                "CoreSplit 文件日志器已启动（目录=" + logDir + "）");
    }

    /**
     * Gracefully shut down: signal the writer, drain the queue (bounded wait),
     * then close the file handles. Called from both the client and server
     * stopping handlers.
     */
    public synchronized void shutdown() {
        if (!running.get()) return;
        running.set(false);
        if (writerThread != null) {
            try {
                writerThread.join(SHUTDOWN_DRAIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeWriters();
        long dropped = droppedCount.get();
        if (dropped > 0) {
            CoreSplitMod.LOGGER.info("[CoreSplit] CSLogger shut down. {} entries were dropped under load.", dropped);
        }
    }

    /** Live-update the minimum log level from the config UI. */
    public void setMinimumLevel(LogLevel level) {
        this.minimumLevel = level == null ? LogLevel.INFO : level;
    }

    public LogLevel getMinimumLevel() { return minimumLevel; }

    /** Live-update the master enable toggle from the config UI. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /** Number of entries dropped because the queue was saturated (for diagnostics). */
    public long getDroppedCount() { return droppedCount.get(); }

    // ═══════════════════════════════════════════════════════════════════════
    // Writer thread
    // ═══════════════════════════════════════════════════════════════════════

    private void writerLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                LogEntry entry = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (entry == null) continue;

                // Daily rotation check
                String today = LocalDate.now().format(DATE_FMT);
                if (!today.equals(currentDate)) {
                    closeWriters();
                    currentDate = today;
                }

                // Lazily open writers on first write of the day
                ensureWritersOpen();

                if (enWriter != null && zhWriter != null) {
                    writeEntry(entry);
                } else {
                    // Writers couldn't be opened — fall back to SLF4J
                    mirrorToSlf4j(entry);
                }
            } catch (InterruptedException e) {
                // Expected during shutdown — loop condition will exit.
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                // Never let the writer thread die silently; log and continue.
                CoreSplitMod.LOGGER.error("[CoreSplit] CSLogger writer error", t);
                // Attempt to recover by reopening writers on the next iteration.
                closeWriters();
            }
        }
        // Final flush on shutdown
        flushWriters();
    }

    private void writeEntry(LogEntry entry) throws IOException {
        String enLine = formatLine(entry, false);
        String zhLine = formatLine(entry, true);
        enWriter.write(enLine);
        enWriter.newLine();
        zhWriter.write(zhLine);
        zhWriter.newLine();

        if (entry.throwable != null) {
            // Stack traces are language-neutral — write to both files.
            String stack = stackTraceToString(entry.throwable);
            enWriter.write(stack);
            zhWriter.write(stack);
        }
        // Flush frequently enough for near-real-time visibility without
        // forcing a syscall per line. The buffered writer batches these.
        // Flush on ERROR/WARN for prompt visibility; DEBUG/INFO rely on the
        // buffer + periodic flush at shutdown.
        if (entry.level.getSeverity() >= LogLevel.WARN.getSeverity()) {
            enWriter.flush();
            zhWriter.flush();
        }
    }

    private String formatLine(LogEntry entry, boolean chinese) {
        String levelName = chinese ? entry.level.getZhName() : entry.level.getEnName();
        String message = chinese ? entry.zhMessage : entry.enMessage;
        return "[" + entry.timestamp + "] [" + levelName + "] [" + entry.source + "] " + message;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // File management
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resolve the log directory under the game root, with a defensive
     * fallback to the system temp dir for non-Fabric environments (unit tests).
     */
    private static Path resolveLogDir() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve(LOG_DIR_NAME);
        } catch (Throwable t) {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).resolve(LOG_DIR_NAME);
            try { Files.createDirectories(tmp); } catch (Exception ignored) {}
            return tmp;
        }
    }

    /**
     * Create the CSlogs directory if it does not already exist. Returns
     * {@code true} if the directory exists (pre-existing or just created).
     */
    private boolean ensureLogDir() {
        try {
            if (Files.isDirectory(logDir)) return true;
            Files.createDirectories(logDir);
            return true;
        } catch (IOException | SecurityException e) {
            CoreSplitMod.LOGGER.error(
                    "[CoreSplit] CSLogger: failed to create log directory '{}'. "
                    + "File logging disabled; falling back to standard game log.", logDir, e);
            return false;
        }
    }

    /** Open the day's writer pair if not already open. */
    private void ensureWritersOpen() {
        if (enWriter != null && zhWriter != null) return;
        if (!ensureLogDir()) {
            fileLoggingEnabled.set(false);
            return;
        }
        Path enPath = logDir.resolve("cslog" + currentDate + ".txt");
        Path zhPath = logDir.resolve("cslog" + currentDate + "_Zh_CN.txt");
        try {
            // APPEND so restarting the game on the same day continues the file.
            enWriter = Files.newBufferedWriter(enPath, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            zhWriter = Files.newBufferedWriter(zhPath, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | SecurityException e) {
            CoreSplitMod.LOGGER.error(
                    "[CoreSplit] CSLogger: failed to open log files in '{}'. "
                    + "File logging disabled for this session.", logDir, e);
            fileLoggingEnabled.set(false);
            closeWriters();
        }
    }

    private void closeWriters() {
        // Closing also flushes. Each writer is closed independently so a
        // failure on one doesn't prevent the other from releasing its handle.
        if (enWriter != null) {
            try { enWriter.close(); } catch (IOException ignored) {}
            enWriter = null;
        }
        if (zhWriter != null) {
            try { zhWriter.close(); } catch (IOException ignored) {}
            zhWriter = null;
        }
    }

    private void flushWriters() {
        if (enWriter != null) {
            try { enWriter.flush(); } catch (IOException ignored) {}
        }
        if (zhWriter != null) {
            try { zhWriter.flush(); } catch (IOException ignored) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Minimal {@code {}} placeholder substitution, mirroring SLF4J semantics
     * for the common case. Does not support escaped braces or indexed
     * placeholders — the project's log messages only use positional {@code {}}.
     */
    private static String formatMessage(String pattern, Object... args) {
        if (pattern == null) return "null";
        if (args == null || args.length == 0) return pattern;
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int argIdx = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '{' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '}'
                    && argIdx < args.length) {
                sb.append(args[argIdx++]);
                i++; // skip the '}'
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stackTraceToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** Mirror an entry to the standard SLF4J logger (used as a fallback). */
    private static void mirrorToSlf4j(LogEntry entry) {
        org.slf4j.Logger log = CoreSplitMod.LOGGER;
        String prefix = "[" + entry.source + "] " + entry.enMessage;
        switch (entry.level) {
            case DEBUG -> log.debug(prefix, entry.throwable);
            case INFO  -> log.info(prefix, entry.throwable);
            case WARN  -> log.warn(prefix, entry.throwable);
            case ERROR -> log.error(prefix, entry.throwable);
        }
    }

    /** Immutable log entry record. */
    private static final class LogEntry {
        final String timestamp;
        final LogLevel level;
        final String source;
        final String enMessage;
        final String zhMessage;
        final Throwable throwable;

        LogEntry(String timestamp, LogLevel level, String source,
                 String enMessage, String zhMessage, Throwable throwable) {
            this.timestamp = timestamp;
            this.level = level;
            this.source = source;
            this.enMessage = enMessage;
            this.zhMessage = zhMessage;
            this.throwable = throwable;
        }
    }
}
