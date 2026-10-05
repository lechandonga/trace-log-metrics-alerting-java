package com.github.lechandonga.tracelogmetricsalertingjava.alert;

/** 告警判定所依据的指标类型。 */
public enum AlertMetricType {
    /** 最近 N 个窗口的失败率（0.0~1.0）。 */
    FAILURE_RATE,
    /** 最近 N 个窗口的平均请求耗时（毫秒）。 */
    AVG_DURATION_MS,
    /** 最近 N 个窗口的总请求量。 */
    REQUEST_COUNT
}
