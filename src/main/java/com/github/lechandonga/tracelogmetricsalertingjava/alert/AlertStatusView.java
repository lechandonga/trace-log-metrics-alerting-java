package com.github.lechandonga.tracelogmetricsalertingjava.alert;

import java.util.List;

/** 告警规则的当前状态视图。 */
public record AlertStatusView(String ruleName, AlertMetricType metricType, AlertState state,
                              double observedValue, long sampleCount, double fireThreshold,
                              double resolveThreshold, long evaluatedAtMillis,
                              long windowStart, long windowEnd, List<AlertEvent> recentEvents) {
}
