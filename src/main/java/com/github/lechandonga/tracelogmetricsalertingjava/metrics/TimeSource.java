package com.github.lechandonga.tracelogmetricsalertingjava.metrics;

import java.time.Instant;

/** 时间源抽象，测试中可替换为可控时钟。 */
@FunctionalInterface
public interface TimeSource {
    long epochMillis();

    static TimeSource system() {
        return System::currentTimeMillis;
    }

    default Instant instant() {
        return Instant.ofEpochMilli(epochMillis());
    }
}
