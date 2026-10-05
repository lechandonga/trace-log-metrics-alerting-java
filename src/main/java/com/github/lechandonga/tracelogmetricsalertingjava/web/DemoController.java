package com.github.lechandonga.tracelogmetricsalertingjava.web;

import com.github.lechandonga.tracelogmetricsalertingjava.common.BusinessException;
import com.github.lechandonga.tracelogmetricsalertingjava.log.StructuredLogger;
import com.github.lechandonga.tracelogmetricsalertingjava.trace.TraceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 演示接口，覆盖三类业务结果与异步处理：
 * <ul>
 *   <li>GET /api/hello：同步成功路径；</li>
 *   <li>GET /api/hello-async：异步分段处理，所有阶段日志携带同一 traceId；</li>
 *   <li>GET /api/reject：参数校验失败（VALIDATION）与业务拒绝（BUSINESS）；</li>
 *   <li>GET /api/boom：系统异常（SYSTEM），异常消息中的令牌会被脱敏；</li>
 *   <li>POST /api/echo：password/token/Authorization 脱敏演示。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final StructuredLogger structuredLogger;
    private final Executor traceTaskExecutor;

    public DemoController(StructuredLogger structuredLogger,
                          Executor traceTaskExecutor) {
        this.structuredLogger = structuredLogger;
        this.traceTaskExecutor = traceTaskExecutor;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "world") String name) {
        String traceId = TraceContext.require();
        structuredLogger.stage("controller", "hello received name=" + name);
        return successBody(traceId, "sync");
    }

    @GetMapping("/hello-async")
    public CompletableFuture<Map<String, Object>> helloAsync(
            @RequestParam(defaultValue = "world") String name) {
        String traceId = TraceContext.require();
        structuredLogger.stage("controller", "hello-async received name=" + name);
        return CompletableFuture.supplyAsync(() -> {
            structuredLogger.stage("async-stage-1", "processing in worker thread");
            return "async";
        }, traceTaskExecutor).thenApplyAsync(mode -> {
            structuredLogger.stage("async-stage-2", "finalizing in worker thread");
            return successBody(traceId, mode);
        }, traceTaskExecutor);
    }

    @GetMapping("/reject")
    public Map<String, Object> reject(@RequestParam String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (orderId.startsWith("X")) {
            throw new BusinessException("ORDER_NOT_ALLOWED", "order " + orderId + " is not allowed");
        }
        return successBody(TraceContext.require(), "accepted");
    }

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        throw new IllegalStateException("downstream failure: token=abcdef1234567890 should be masked");
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam String username,
                                    @RequestParam(required = false) String password,
                                    @RequestParam(required = false) String token) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        structuredLogger.stage("controller",
                "echo login username=" + username + " password=" + password + " token=" + token);
        return successBody(TraceContext.require(), "echoed");
    }

    private Map<String, Object> successBody(String traceId, String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("mode", mode);
        body.put("result", "ok");
        return body;
    }
}
