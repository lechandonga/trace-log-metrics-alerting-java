package com.github.lechandonga.tracelogmetricsalertingjava.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * 一个固定时间窗口的累加器。每个请求恰好落入一个窗口，使用 {@link LongAdder}
 * 保证并发写入下计数不重复、不遗漏。窗口对象滚动后不再被写入（由注册表保证）。
 */
public class MetricsWindow {

    private final long startMillis;
    private final long endMillis;
    private final LongAdder requestCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder totalDurationMs = new LongAdder();

    public MetricsWindow(long startMillis, long windowSizeMillis) {
        this.startMillis = startMillis;
        this.endMillis = startMillis + windowSizeMillis;
    }

    public void record(boolean failure, long durationMs) {
        requestCount.increment();
        if (failure) {
            failureCount.increment();
        }
        totalDurationMs.add(durationMs);
    }

    public long startMillis() {
        return startMillis;
    }

    public long endMillis() {
        return endMillis;
    }

    public WindowSnapshot snapshot() {
        long requests = requestCount.sum();
        long failures = failureCount.sum();
        return new WindowSnapshot(startMillis, endMillis, requests, failures,
                requests - failures, totalDurationMs.sum());
    }
}
