package com.github.lechandonga.tracelogmetricsalertingjava.metrics;

/**
 * 单个时间窗口的聚合快照（不可变值对象）。
 *
 * @param windowStart 窗口起始 epoch 毫秒（含）
 * @param windowEnd   窗口结束 epoch 毫秒（不含）
 * @param requestCount 请求总数
 * @param failureCount 失败请求数（VALIDATION / BUSINESS / SYSTEM）
 * @param totalDurationMs 窗口内全部请求耗时之和
 * @param successCount 成功请求数
 */
public record WindowSnapshot(long windowStart, long windowEnd, long requestCount, long failureCount,
                             long successCount, long totalDurationMs) {

    public double averageDurationMs() {
        return requestCount == 0 ? 0.0 : (double) totalDurationMs / requestCount;
    }

    public double failureRate() {
        return requestCount == 0 ? 0.0 : (double) failureCount / requestCount;
    }
}
