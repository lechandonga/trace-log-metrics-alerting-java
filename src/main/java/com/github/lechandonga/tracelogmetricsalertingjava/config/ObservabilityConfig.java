package com.github.lechandonga.tracelogmetricsalertingjava.config;

import com.github.lechandonga.tracelogmetricsalertingjava.alert.AlertMetricType;
import com.github.lechandonga.tracelogmetricsalertingjava.alert.AlertRule;
import com.github.lechandonga.tracelogmetricsalertingjava.alert.AlertService;
import com.github.lechandonga.tracelogmetricsalertingjava.log.SensitiveDataMasker;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.InMemoryMetricsRegistry;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.MetricsProperties;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.TimeSource;
import com.github.lechandonga.tracelogmetricsalertingjava.trace.TraceIdGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/** 观测能力的 Bean 装配与默认告警规则。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MetricsProperties.class)
public class ObservabilityConfig {

    @Bean
    public SensitiveDataMasker sensitiveDataMasker() {
        return new SensitiveDataMasker();
    }

    @Bean
    public TraceIdGenerator traceIdGenerator() {
        return new TraceIdGenerator();
    }

    @Bean
    public TimeSource timeSource() {
        return TimeSource.system();
    }

    @Bean
    public InMemoryMetricsRegistry metricsRegistry(MetricsProperties properties, TimeSource timeSource) {
        return new InMemoryMetricsRegistry(properties.windowSizeMillis(),
                properties.retentionWindows(), timeSource);
    }

    /**
     * 默认规则（可通过 observability.alerts.* 配置覆盖语义参考 README）：
     * 失败率 >= 0.6 触发、&lt; 0.3 恢复，要求聚合窗口至少 10 个样本，避免无流量误报。
     */
    @Bean
    public List<AlertRule> defaultAlertRules() {
        return List.of(
                new AlertRule("high-failure-rate", AlertMetricType.FAILURE_RATE, 3,
                        0.6, 0.3, 10),
                new AlertRule("slow-latency", AlertMetricType.AVG_DURATION_MS, 3,
                        300.0, 150.0, 10)
        );
    }

    @Bean
    public AlertService alertService(InMemoryMetricsRegistry metricsRegistry, TimeSource timeSource,
                                     com.github.lechandonga.tracelogmetricsalertingjava.log.StructuredLogger structuredLogger,
                                     List<AlertRule> rules) {
        AlertService service = new AlertService(metricsRegistry, timeSource, structuredLogger);
        rules.forEach(service::registerRule);
        return service;
    }
}
