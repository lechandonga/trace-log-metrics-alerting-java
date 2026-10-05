package com.github.lechandonga.tracelogmetricsalertingjava.alert;

import com.github.lechandonga.tracelogmetricsalertingjava.log.SensitiveDataMasker;
import com.github.lechandonga.tracelogmetricsalertingjava.log.StructuredLogger;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.InMemoryMetricsRegistry;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.TimeSource;
import com.github.lechandonga.tracelogmetricsalertingjava.tests.BusinessDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessDomain("阈值告警: 无流量/滞回/触发与恢复迁移")
class AlertServiceTest {

    private static final long WINDOW = 1000L;
    private InMemoryMetricsRegistry registry;
    private AlertService alertService;
    private AlertRule failureRateRule;

    private static StructuredLogger logger() {
        return new StructuredLogger(new ObjectMapper(), new SensitiveDataMasker());
    }

    @BeforeEach
    void setUp() {
        registry = new InMemoryMetricsRegistry(WINDOW, 20, TimeSource.system());
        alertService = new AlertService(registry, TimeSource.system(), logger());
        // 聚合 3 个窗口，失败率 >=0.6 触发，<0.3 恢复，至少 10 个样本
        failureRateRule = new AlertRule("fr", AlertMetricType.FAILURE_RATE, 3, 0.6, 0.3, 10);
        alertService.registerRule(failureRateRule);
    }

    @Test
    @DisplayName("无流量绝不告警")
    void noTrafficDoesNotFire() {
        assertTrue(alertService.evaluateAll(3_000L).isEmpty());
        assertEquals(AlertState.OK, alertService.statusOf("fr").state());
        assertTrue(alertService.eventsOf("fr").isEmpty());
    }

    @Test
    @DisplayName("样本不足(低于 minSamples)不触发")
    void insufficientSamplesDoNotFire() {
        for (int i = 0; i < 5; i++) {
            registry.recordAt(1_500L, true, 10);
        }
        assertTrue(alertService.evaluateAll(3_000L).isEmpty());
        assertEquals(AlertState.OK, alertService.statusOf("fr").state());
    }

    @Test
    @DisplayName("达到触发阈值产生唯一 FIRING 迁移, 事件记录依据")
    void fireOnceWithEvidence() {
        for (int i = 0; i < 10; i++) {
            registry.recordAt(1_500L, i < 7, 10);
        }
        List<AlertEvent> events = alertService.evaluateAll(3_000L);
        assertEquals(1, events.size());
        AlertEvent event = events.get(0);
        assertTrue(event.isFired());
        assertEquals(AlertState.OK, event.fromState());
        assertEquals(AlertState.FIRING, event.toState());
        assertEquals(0.7, event.observedValue(), 0.0001);
        assertEquals(10, event.sampleCount());

        assertTrue(alertService.evaluateAll(3_500L).isEmpty(), "保持 FIRING, 不重复触发");
        AlertStatusView view = alertService.statusOf("fr");
        assertEquals(AlertState.FIRING, view.state());
    }

    @Test
    @DisplayName("滞回带内波动不触发也不恢复")
    void hysteresisBandKeepsState() {
        // 10 个请求, 6 失败 => 0.6 触发
        for (int i = 0; i < 10; i++) {
            registry.recordAt(1_500L, i < 6, 10);
        }
        alertService.evaluateAll(3_000L);
        assertEquals(AlertState.FIRING, alertService.statusOf("fr").state());

        // 下一批窗口失败率 0.4, 位于 [0.3, 0.6) 滞回带 => 保持 FIRING
        InMemoryMetricsRegistry second = registry;
        for (int i = 0; i < 10; i++) {
            second.recordAt(4_500L, i < 4, 10);
        }
        assertTrue(alertService.evaluateAll(6_000L).isEmpty());
        assertEquals(AlertState.FIRING, alertService.statusOf("fr").state());
    }

    @Test
    @DisplayName("低于恢复阈值才恢复, 触发与恢复事件成对可查")
    void resolveOnlyBelowThreshold() {
        for (int i = 0; i < 10; i++) {
            registry.recordAt(1_500L, i < 8, 10);
        }
        alertService.evaluateAll(3_000L);

        for (int i = 0; i < 10; i++) {
            registry.recordAt(4_500L, i < 2, 10);
        }
        // 评估时刻 6000, 聚合窗口 4000/5000/6000 只包含 4500 的数据 => 0.2
        List<AlertEvent> events = alertService.evaluateAll(6_000L);
        assertEquals(1, events.size());
        AlertEvent resolve = events.get(0);
        assertTrue(resolve.isResolved());
        assertEquals(0.2, resolve.observedValue(), 0.0001);
        assertEquals(AlertState.OK, alertService.statusOf("fr").state());

        List<AlertEvent> history = alertService.eventsOf("fr");
        assertEquals(2, history.size());
        assertTrue(history.get(0).isFired());
        assertTrue(history.get(1).isResolved());
    }

    @Test
    @DisplayName("FIRING 期间无流量不自动恢复(无数据不等于恢复)")
    void firingSurvivesSilence() {
        for (int i = 0; i < 10; i++) {
            registry.recordAt(1_500L, true, 10);
        }
        alertService.evaluateAll(3_000L);
        // 滚动到所有含样本窗口离开聚合范围, 且无新流量
        assertTrue(alertService.evaluateAll(20_000L).isEmpty());
        assertEquals(AlertState.FIRING, alertService.statusOf("fr").state());
    }

    @Test
    @DisplayName("阈值附近抖动: 多个周期反复跨越不产生抖动事件")
    void thresholdFlappingDoesNotToggle() {
        // 触发
        for (int i = 0; i < 10; i++) {
            registry.recordAt(1_500L, i < 7, 10);
        }
        alertService.evaluateAll(3_000L);
        // 滞回带 (0.5)
        for (int i = 0; i < 10; i++) {
            registry.recordAt(4_500L, i < 5, 10);
        }
        alertService.evaluateAll(6_000L);
        // 再次升高到 0.6 但仍在 FIRING => 无新事件
        for (int i = 0; i < 10; i++) {
            registry.recordAt(7_500L, i < 6, 10);
        }
        alertService.evaluateAll(9_000L);
        assertEquals(1, alertService.eventsOf("fr").size(), "滞回带内抖动不应产生事件");
    }

    @Test
    @DisplayName("并发评估不产生重复迁移事件")
    void concurrentEvaluationEmitsSingleTransition() throws Exception {
        for (int i = 0; i < 10; i++) {
            registry.recordAt(1_500L, i < 8, 10);
        }
        int evaluators = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(evaluators);
        AtomicInteger eventCount = new AtomicInteger();
        for (int i = 0; i < evaluators; i++) {
            pool.submit(() -> {
                try {
                    eventCount.addAndGet(alertService.evaluateAll(3_000L).size());
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, eventCount.get());
        assertEquals(1, alertService.eventsOf("fr").size());
    }

    @Test
    @DisplayName("平均耗时规则: 触发与恢复")
    void latencyRuleFiresAndResolves() {
        AlertRule latencyRule = new AlertRule("lat", AlertMetricType.AVG_DURATION_MS, 3,
                300, 150, 5);
        alertService.registerRule(latencyRule);
        for (int i = 0; i < 5; i++) {
            registry.recordAt(2_500L, false, 400);
        }
        alertService.evaluateAll(3_000L);
        assertEquals(AlertState.FIRING, alertService.statusOf("lat").state());

        for (int i = 0; i < 5; i++) {
            registry.recordAt(5_500L, false, 100);
        }
        alertService.evaluateAll(6_000L);
        assertEquals(AlertState.OK, alertService.statusOf("lat").state());
    }
}
