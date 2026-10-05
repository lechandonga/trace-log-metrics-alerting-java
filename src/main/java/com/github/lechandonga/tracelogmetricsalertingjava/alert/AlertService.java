package com.github.lechandonga.tracelogmetricsalertingjava.alert;

import com.github.lechandonga.tracelogmetricsalertingjava.log.StructuredLogger;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.InMemoryMetricsRegistry;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.TimeSource;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.WindowSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警评估器：周期性（或被显式触发）读取窗口指标，按规则做带滞回的状态迁移。
 * <p>
 * 语义保证：
 * <ul>
 *   <li><b>无流量不误报</b>：聚合请求量为 0（或低于规则 minSamples）时不产生触发事件；
 *       FIRING 状态下无流量也不自动恢复（无数据不等于恢复），恢复必须出现
 *       “样本充足且指标低于恢复阈值”的评估结果。</li>
 *   <li><b>阈值抖动不反复</b>：触发需 {@code value >= fireThreshold}，
 *       恢复需 {@code value < resolveThreshold}，两者之间为滞回带，状态保持不变。</li>
 *   <li><b>迁移可解释</b>：每次状态迁移生成 {@link AlertEvent}，记录迁移前后状态、
 *       观测值、样本数与依据窗口范围；当前状态可查询。</li>
 * </ul>
 * 单条规则的评估加锁串行化，避免并发评估产生重复迁移事件。
 */
@Service
public class AlertService {

    private final InMemoryMetricsRegistry metricsRegistry;
    private final TimeSource timeSource;
    private final StructuredLogger structuredLogger;
    private final Map<String, RuleState> ruleStates = new ConcurrentHashMap<>();
    private final List<AlertRule> rules = new CopyOnWriteArrayList<>();

    public AlertService(InMemoryMetricsRegistry metricsRegistry, TimeSource timeSource,
                        StructuredLogger structuredLogger) {
        this.metricsRegistry = metricsRegistry;
        this.timeSource = timeSource;
        this.structuredLogger = structuredLogger;
    }

    public void registerRule(AlertRule rule) {
        rules.add(rule);
        ruleStates.putIfAbsent(rule.name(), new RuleState(rule));
    }

    public List<AlertRule> getRules() {
        return List.copyOf(rules);
    }

    /** 评估全部规则，返回本次评估产生的迁移事件（可能为空）。线程安全。 */
    public List<AlertEvent> evaluateAll() {
        return evaluateAll(timeSource.epochMillis());
    }

    public List<AlertEvent> evaluateAll(long nowMillis) {
        List<AlertEvent> events = new ArrayList<>();
        for (AlertRule rule : rules) {
            AlertEvent event = ruleStates.get(rule.name()).evaluate(nowMillis);
            if (event != null) {
                events.add(event);
                logTransition(event);
            }
        }
        return events;
    }

    public AlertStatusView statusOf(String ruleName) {
        RuleState state = ruleStates.get(ruleName);
        if (state == null) {
            throw new IllegalArgumentException("unknown alert rule: " + ruleName);
        }
        return state.toView();
    }

    public List<AlertStatusView> allStatus() {
        return rules.stream().map(rule -> ruleStates.get(rule.name()).toView()).toList();
    }

    public List<AlertEvent> eventsOf(String ruleName) {
        RuleState state = ruleStates.get(ruleName);
        if (state == null) {
            throw new IllegalArgumentException("unknown alert rule: " + ruleName);
        }
        return state.eventsView();
    }

    private void logTransition(AlertEvent event) {
        String verb = event.isFired() ? "ALERT_FIRED" : "ALERT_RESOLVED";
        structuredLogger.stage("alert",
                verb + " rule=" + event.ruleName()
                        + " observedValue=" + event.observedValue()
                        + " sampleCount=" + event.sampleCount()
                        + " windowStart=" + event.windowStart()
                        + " windowEnd=" + event.windowEnd());
    }

    private double extractMetric(AlertMetricType type, WindowSnapshot snapshot) {
        return switch (type) {
            case FAILURE_RATE -> snapshot.failureRate();
            case AVG_DURATION_MS -> snapshot.averageDurationMs();
            case REQUEST_COUNT -> snapshot.requestCount();
        };
    }

    /** 单条规则的可变状态；评估方法整体同步，杜绝并发下的重复/丢失迁移。 */
    private final class RuleState {
        private final AlertRule rule;
        private final List<AlertEvent> events = new CopyOnWriteArrayList<>();
        private volatile AlertState state = AlertState.OK;
        private volatile double lastObservedValue;
        private volatile long lastSampleCount;
        private volatile long lastWindowStart;
        private volatile long lastWindowEnd;
        private volatile long evaluatedAt;

        private RuleState(AlertRule rule) {
            this.rule = rule;
        }

        private synchronized AlertEvent evaluate(long nowMillis) {
            WindowSnapshot aggregate =
                    metricsRegistry.aggregate(nowMillis, rule.windowCount());
            double value = extractMetric(rule.metricType(), aggregate);
            lastObservedValue = value;
            lastSampleCount = aggregate.requestCount();
            lastWindowStart = aggregate.windowStart();
            lastWindowEnd = aggregate.windowEnd();
            evaluatedAt = nowMillis;

            AlertState previous = state;
            AlertState next = previous;
            if (previous == AlertState.OK
                    && aggregate.requestCount() >= rule.minSamples()
                    && value >= rule.fireThreshold()) {
                next = AlertState.FIRING;
            } else if (previous == AlertState.FIRING
                    && aggregate.requestCount() >= rule.minSamples()
                    && value < rule.resolveThreshold()) {
                next = AlertState.OK;
            }
            // 样本不足（含无流量）以及滞回带内：状态保持不变，不产生事件
            if (next == previous) {
                return null;
            }
            state = next;
            AlertEvent event = new AlertEvent(rule.name(), previous, next, value,
                    aggregate.requestCount(), nowMillis,
                    aggregate.windowStart(), aggregate.windowEnd());
            events.add(event);
            return event;
        }

        private AlertStatusView toView() {
            return new AlertStatusView(rule.name(), rule.metricType(), state, lastObservedValue,
                    lastSampleCount, rule.fireThreshold(), rule.resolveThreshold(), evaluatedAt,
                    lastWindowStart, lastWindowEnd, eventsView());
        }

        private List<AlertEvent> eventsView() {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }
}
