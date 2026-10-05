package com.github.lechandonga.tracelogmetricsalertingjava.config;

import com.github.lechandonga.tracelogmetricsalertingjava.alert.AlertService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期性告警评估，间隔由 {@code observability.alert-evaluate-interval-ms} 控制（默认 5s）。 */
@Component
public class AlertScheduler {

    private final AlertService alertService;

    public AlertScheduler(AlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${observability.alert-evaluate-interval-ms:5000}",
            initialDelayString = "${observability.alert-evaluate-interval-ms:5000}")
    public void evaluate() {
        alertService.evaluateAll();
    }
}
