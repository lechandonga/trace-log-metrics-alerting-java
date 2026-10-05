package com.github.lechandonga.tracelogmetricsalertingjava.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 指标配置（前缀 {@code observability.metrics}）。
 *
 * @param windowSizeMillis 单个窗口大小（毫秒）
 * @param retentionWindows 内存中保留的窗口数量
 */
@ConfigurationProperties(prefix = "observability.metrics")
public record MetricsProperties(long windowSizeMillis, int retentionWindows) {

    public static final long DEFAULT_WINDOW_SIZE_MILLIS = 10_000L;
    public static final int DEFAULT_RETENTION_WINDOWS = 12;

    public MetricsProperties {
        if (windowSizeMillis <= 0) {
            windowSizeMillis = DEFAULT_WINDOW_SIZE_MILLIS;
        }
        if (retentionWindows <= 0) {
            retentionWindows = DEFAULT_RETENTION_WINDOWS;
        }
    }

    public MetricsProperties() {
        this(DEFAULT_WINDOW_SIZE_MILLIS, DEFAULT_RETENTION_WINDOWS);
    }
}
