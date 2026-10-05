package com.github.lechandonga.tracelogmetricsalertingjava.alert;

/**
 * 告警状态机。{@code OK -> FIRING -> RESOLVED/OK}：
 * 只有跨越触发阈值才进入 FIRING；只有回落到恢复阈值（滞回带以下）才恢复，
 * 阈值附近的抖动不会造成反复触发/恢复。
 */
public enum AlertState {
    /** 正常（含从未触发）。 */
    OK,
    /** 告警触发中。 */
    FIRING
}
