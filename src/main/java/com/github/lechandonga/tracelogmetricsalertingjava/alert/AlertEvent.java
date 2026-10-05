package com.github.lechandonga.tracelogmetricsalertingjava.alert;

/**
 * 告警状态迁移事件（触发 / 恢复），保留触发与恢复时的指标依据，可查询。
 */
public record AlertEvent(String ruleName, AlertState fromState, AlertState toState,
                         double observedValue, long sampleCount, long timestampMillis,
                         long windowStart, long windowEnd) {

    public boolean isFired() {
        return fromState == AlertState.OK && toState == AlertState.FIRING;
    }

    public boolean isResolved() {
        return fromState == AlertState.FIRING && toState == AlertState.OK;
    }
}
