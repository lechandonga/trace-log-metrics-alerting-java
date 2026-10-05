package com.github.lechandonga.tracelogmetricsalertingjava.metrics;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存指标注册表：按固定大小的滚动（翻滚）窗口组织计数。
 * <ul>
 *   <li>窗口边界对齐到 epoch：{@code start = floor(now / size) * size}，
 *       因此所有写入方对“同一条请求属于哪个窗口”结论一致。</li>
 *   <li>窗口对象惰性创建、永不替换；对同一窗口的全部写入累计到同一组计数器，
 *       并发写入不重复、不遗漏。</li>
 *   <li>查询时滚动淘汰早于 {@code retentionWindows} 的窗口；无流量的窗口不会
 *       被创建，查询时按真实时间边界补零返回，避免“无数据”被误判为零流量或
 *       反之。</li>
 * </ul>
 */
public class InMemoryMetricsRegistry {

    private final long windowSizeMillis;
    private final int retentionWindows;
    private final TimeSource timeSource;
    private final ConcurrentMap<Long, MetricsWindow> windows = new ConcurrentHashMap<>();

    public InMemoryMetricsRegistry(MetricsProperties properties) {
        this(properties.windowSizeMillis(), properties.retentionWindows(), TimeSource.system());
    }

    public InMemoryMetricsRegistry(long windowSizeMillis, int retentionWindows, TimeSource timeSource) {
        if (windowSizeMillis <= 0) {
            throw new IllegalArgumentException("windowSizeMillis must be positive");
        }
        if (retentionWindows <= 0) {
            throw new IllegalArgumentException("retentionWindows must be positive");
        }
        this.windowSizeMillis = windowSizeMillis;
        this.retentionWindows = retentionWindows;
        this.timeSource = timeSource;
    }

    public long windowSizeMillis() {
        return windowSizeMillis;
    }

    /** 记录一条请求。failure=true 计入失败数；该方法线程安全。 */
    public void record(boolean failure, long durationMs) {
        recordAt(timeSource.epochMillis(), failure, durationMs);
    }

    /** 按指定时间记录，便于测试窗口边界。 */
    public void recordAt(long timestampMillis, boolean failure, long durationMs) {
        long start = alignToWindow(timestampMillis);
        MetricsWindow window = windows.computeIfAbsent(start,
                key -> new MetricsWindow(key, windowSizeMillis));
        window.record(failure, durationMs);
    }

    public long alignToWindow(long timestampMillis) {
        return Math.floorDiv(timestampMillis, windowSizeMillis) * windowSizeMillis;
    }

    /**
     * 查询最近 {@code count} 个窗口（含当前窗口），按时间升序返回。
     * 无流量窗口以全零快照补齐；超出保留期的窗口被淘汰。
     */
    public List<WindowSnapshot> queryRecentWindows(int count) {
        return queryRecentWindows(timeSource.epochMillis(), count);
    }

    public List<WindowSnapshot> queryRecentWindows(long nowMillis, int count) {
        evictExpired(nowMillis);
        long currentStart = alignToWindow(nowMillis);
        List<WindowSnapshot> result = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            long start = currentStart - (long) i * windowSizeMillis;
            MetricsWindow window = windows.get(start);
            if (window != null) {
                result.add(window.snapshot());
            } else {
                result.add(new WindowSnapshot(start, start + windowSizeMillis, 0, 0, 0, 0));
            }
        }
        return result;
    }

    /** 返回最近 {@code count} 个窗口的合计（无流量窗口按零计入）。 */
    public WindowSnapshot aggregateRecent(int count) {
        return aggregate(timeSource.epochMillis(), count);
    }

    public WindowSnapshot aggregate(long nowMillis, int count) {
        List<WindowSnapshot> snapshots = queryRecentWindows(nowMillis, count);
        long requests = 0;
        long failures = 0;
        long duration = 0;
        long firstStart = snapshots.isEmpty() ? alignToWindow(nowMillis) : snapshots.get(0).windowStart();
        long lastEnd = snapshots.isEmpty() ? firstStart + windowSizeMillis
                : snapshots.get(snapshots.size() - 1).windowEnd();
        for (WindowSnapshot snapshot : snapshots) {
            requests += snapshot.requestCount();
            failures += snapshot.failureCount();
            duration += snapshot.totalDurationMs();
        }
        return new WindowSnapshot(firstStart, lastEnd, requests, failures, requests - failures, duration);
    }

    private void evictExpired(long nowMillis) {
        long oldestKept = alignToWindow(nowMillis) - (long) (retentionWindows - 1) * windowSizeMillis;
        windows.keySet().removeIf(start -> start < oldestKept);
    }
}
