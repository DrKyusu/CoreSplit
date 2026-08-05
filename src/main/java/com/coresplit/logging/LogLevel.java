package com.coresplit.logging;

/**
 * Log severity levels for the CoreSplit bilingual file logger.
 *
 * <p>Levels are ordered by severity: {@link #DEBUG} &lt; {@link #INFO} &lt; {@link #WARN} &lt; {@link #ERROR}.
 * A minimum level filter (configurable via the settings UI) suppresses any
 * level below it. Each level carries both an English and a Chinese display
 * name so the two parallel log files can be fully localized.
 */
public enum LogLevel {
    DEBUG(0, "DEBUG", "调试"),
    INFO(1, "INFO", "信息"),
    WARN(2, "WARN", "警告"),
    ERROR(3, "ERROR", "错误");

    private final int severity;
    private final String enName;
    private final String zhName;

    LogLevel(int severity, String enName, String zhName) {
        this.severity = severity;
        this.enName = enName;
        this.zhName = zhName;
    }

    /** Numeric severity — higher means more severe. Used for level filtering. */
    public int getSeverity() { return severity; }

    /** English label written to {@code cslogYYYYMMDD.txt}. */
    public String getEnName() { return enName; }

    /** Chinese label written to {@code cslogYYYYMMDD_Zh_CN.txt}. */
    public String getZhName() { return zhName; }

    /**
     * Whether a message at this level should be emitted given a configured
     * minimum level.
     */
    public boolean isLoggable(LogLevel minimum) {
        return this.severity >= minimum.severity;
    }
}
