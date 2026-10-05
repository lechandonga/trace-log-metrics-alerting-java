package com.github.lechandonga.tracelogmetricsalertingjava.alert;

/**
 * 一条告警规则。
 *
 * @param name 规则唯一名称
 * @param metricType 判定指标
 * @param windowCount 每次评估聚合最近多少个窗口
 * @param fireThreshold 触发阈值：指标值 >= 该值且请求量达 minSamples 时触发
 * @param resolveThreshold 恢复阈值（严格小于才恢复，必须 < fireThreshold，形成滞回带）
 * @param minSamples 触发所需的最小请求样本量，无流量 / 样本不足绝不触发
 */
public record AlertRule(String name, AlertMetricType metricType, int windowCount,
                        double fireThreshold, double resolveThreshold, long minSamples) {

    public AlertRule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("rule name is required");
        }
        if (windowCount <= 0) {
            throw new IllegalArgumentException("windowCount must be positive");
        }
        if (!(resolveThreshold < fireThreshold)) {
            throw new IllegalArgumentException(
                    "resolveThreshold must be strictly less than fireThreshold (hysteresis)");
        }
        if (minSamples < 0) {
            throw new IllegalArgumentException("minSamples must not be negative");
        }
    }
}
