package com.github.lechandonga.tracelogmetricsalertingjava.web;

import com.github.lechandonga.tracelogmetricsalertingjava.alert.AlertService;
import com.github.lechandonga.tracelogmetricsalertingjava.alert.AlertStatusView;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.InMemoryMetricsRegistry;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.WindowSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地观测接口：窗口指标查询、告警状态查询与手动触发评估。 */
@RestController
@RequestMapping("/actuator-local")
public class ObservabilityController {

    private final InMemoryMetricsRegistry metricsRegistry;
    private final AlertService alertService;

    public ObservabilityController(InMemoryMetricsRegistry metricsRegistry,
                                   AlertService alertService) {
        this.metricsRegistry = metricsRegistry;
        this.alertService = alertService;
    }

    @GetMapping("/metrics/windows")
    public List<WindowSnapshot> windows(@RequestParam(defaultValue = "6") int count) {
        return metricsRegistry.queryRecentWindows(count);
    }

    @GetMapping("/metrics/aggregate")
    public WindowSnapshot aggregate(@RequestParam(defaultValue = "3") int count) {
        return metricsRegistry.aggregateRecent(count);
    }

    @GetMapping("/alerts")
    public List<AlertStatusView> alerts() {
        return alertService.allStatus();
    }

    @GetMapping("/alerts/{name}")
    public AlertStatusView alert(@PathVariable String name) {
        return alertService.statusOf(name);
    }

    @PostMapping("/alerts/evaluate")
    public Map<String, Object> evaluate() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transitions", alertService.evaluateAll());
        result.put("status", alertService.allStatus());
        return result;
    }
}
