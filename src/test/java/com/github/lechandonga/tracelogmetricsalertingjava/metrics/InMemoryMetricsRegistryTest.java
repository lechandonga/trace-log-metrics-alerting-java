package com.github.lechandonga.tracelogmetricsalertingjava.metrics;

import com.github.lechandonga.tracelogmetricsalertingjava.tests.BusinessDomain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessDomain("窗口指标聚合: 边界/并发/无流量补零")
class InMemoryMetricsRegistryTest {

    @Test
    @DisplayName("窗口边界对齐: floor(now/size)*size, 边界两侧落入不同窗口")
    void windowBoundaries() {
        InMemoryMetricsRegistry registry = new InMemoryMetricsRegistry(1000, 6, TimeSource.system());
        assertEquals(10_000L, registry.alignToWindow(10_999L));
        assertEquals(10_000L, registry.alignToWindow(10_000L));
        assertEquals(9_000L, registry.alignToWindow(9_999L));

        registry.recordAt(9_999L, false, 10);
        registry.recordAt(10_000L, true, 20);
        List<WindowSnapshot> windows = registry.queryRecentWindows(10_000L, 2);
        WindowSnapshot first = windows.get(0);
        WindowSnapshot second = windows.get(1);
        assertEquals(9_000L, first.windowStart());
        assertEquals(1, first.requestCount());
        assertEquals(0, first.failureCount());
        assertEquals(10_000L, second.windowStart());
        assertEquals(1, second.requestCount());
        assertEquals(1, second.failureCount());
    }

    @Test
    @DisplayName("无流量窗口补零返回, 不误判为有数据")
    void emptyWindowsAreZeroFilled() {
        InMemoryMetricsRegistry registry = new InMemoryMetricsRegistry(1000, 6, TimeSource.system());
        registry.recordAt(10_000L, false, 5);
        List<WindowSnapshot> windows = registry.queryRecentWindows(50_000L, 3);
        assertEquals(3, windows.size());
        windows.forEach(snapshot -> {
            assertEquals(0, snapshot.requestCount());
            assertEquals(0, snapshot.failureCount());
            assertEquals(0.0, snapshot.averageDurationMs());
            assertEquals(0.0, snapshot.failureRate());
        });
    }

    @Test
    @DisplayName("过期窗口被淘汰, 保留期内窗口可查")
    void eviction() {
        InMemoryMetricsRegistry registry = new InMemoryMetricsRegistry(1000, 3, TimeSource.system());
        registry.recordAt(0, false, 1);
        registry.recordAt(1_000, false, 1);
        registry.recordAt(2_000, true, 1);
        registry.recordAt(3_000, false, 1);
        List<WindowSnapshot> windows = registry.queryRecentWindows(5_000L, 3);
        assertEquals(3_000L, windows.get(0).windowStart());
        assertEquals(6_000L, windows.get(2).windowEnd());
        assertEquals(1, windows.get(0).requestCount());
    }

    @Test
    @DisplayName("并发写入同一窗口: 请求量/失败数/耗时不重复不遗漏")
    void concurrentRecordCountsExactly() throws InterruptedException {
        InMemoryMetricsRegistry registry = new InMemoryMetricsRegistry(60_000, 6, TimeSource.system());
        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            boolean failing = t % 2 == 0;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        registry.recordAt(1_000L, failing, 3);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS));
        pool.shutdown();
        WindowSnapshot snapshot = registry.queryRecentWindows(1_000L, 1).get(0);
        assertEquals(threads * perThread, snapshot.requestCount());
        assertEquals(threads / 2 * perThread, snapshot.failureCount());
        assertEquals(threads * perThread * 3L, snapshot.totalDurationMs());
        assertEquals(0.5, snapshot.failureRate(), 0.0001);
        assertEquals(3.0, snapshot.averageDurationMs(), 0.0001);
    }

    @Test
    @DisplayName("多窗口聚合: 请求量/失败数/平均耗时合计正确")
    void aggregationAcrossWindows() {
        InMemoryMetricsRegistry registry = new InMemoryMetricsRegistry(1000, 6, TimeSource.system());
        registry.recordAt(1_000L, false, 10);
        registry.recordAt(2_000L, true, 30);
        registry.recordAt(3_000L, true, 50);
        WindowSnapshot aggregate = registry.aggregate(3_500L, 3);
        assertEquals(3, aggregate.requestCount());
        assertEquals(2, aggregate.failureCount());
        assertEquals(90, aggregate.totalDurationMs());
        assertEquals(30.0, aggregate.averageDurationMs(), 0.0001);
        assertEquals(2.0 / 3, aggregate.failureRate(), 0.0001);
        assertEquals(1_000L, aggregate.windowStart());
        assertEquals(4_000L, aggregate.windowEnd());
    }
}
