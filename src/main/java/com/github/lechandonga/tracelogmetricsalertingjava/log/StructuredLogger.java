package com.github.lechandonga.tracelogmetricsalertingjava.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化日志器：所有访问日志 / 异常日志输出为单行 JSON，字段稳定、可检索。
 * 输出前统一经过 {@link SensitiveDataMasker}，敏感内容不落盘。
 */
@Component
public class StructuredLogger {

    public static final String MDC_TRACE_ID = "traceId";

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("ACCESS");
    private static final Logger ERROR_LOG = LoggerFactory.getLogger("ERROR");

    private final ObjectMapper objectMapper;
    private final SensitiveDataMasker masker;

    public StructuredLogger(ObjectMapper objectMapper, SensitiveDataMasker masker) {
        this.objectMapper = objectMapper;
        this.masker = masker;
    }

    /** 访问日志：每条请求处理结束恰好输出一条。 */
    public void access(AccessLogEntry entry) {
        Map<String, Object> fields = baseFields(entry.traceId());
        fields.put("logType", "access");
        fields.put("method", entry.method());
        fields.put("path", entry.path());
        fields.put("status", entry.status());
        fields.put("durationMs", entry.durationMs());
        fields.put("errorCategory", entry.errorCategory());
        fields.put("errorCode", entry.errorCode());
        fields.put("traceIdSource", entry.traceIdSource());
        if (entry.queryParams() != null && !entry.queryParams().isEmpty()) {
            fields.put("queryParams", masker.maskMap(entry.queryParams()));
        }
        ACCESS_LOG.info(toJson(fields));
    }

    /** 异常日志：仅在请求以失败结束时输出，错误类别可区分。 */
    public void error(ErrorLogEntry entry) {
        Map<String, Object> fields = baseFields(entry.traceId());
        fields.put("logType", "error");
        fields.put("method", entry.method());
        fields.put("path", entry.path());
        fields.put("status", entry.status());
        fields.put("durationMs", entry.durationMs());
        fields.put("errorCategory", entry.errorCategory());
        fields.put("errorCode", entry.errorCode());
        fields.put("message", masker.mask(entry.message()));
        fields.put("exceptionClass", entry.exceptionClass());
        ERROR_LOG.atLevel(org.slf4j.event.Level.ERROR).log(toJson(fields));
    }

    /** 业务阶段日志（含异步阶段）：统一携带 traceId，消息内容同样脱敏。 */
    public void stage(String stage, String message) {
        String traceId = MDC.get(MDC_TRACE_ID);
        Map<String, Object> fields = baseFields(traceId);
        fields.put("logType", "stage");
        fields.put("stage", stage);
        fields.put("message", masker.mask(message));
        ACCESS_LOG.info(toJson(fields));
    }

    private Map<String, Object> baseFields(String traceId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", java.time.Instant.now().toString());
        fields.put("traceId", traceId);
        return fields;
    }

    private String toJson(Map<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            return "{\"logType\":\"unknown\",\"error\":\"log-serialization-failed\"}";
        }
    }

    public record AccessLogEntry(String traceId, String traceIdSource, String method, String path,
                                 int status, long durationMs, String errorCategory, String errorCode,
                                 Map<String, String> queryParams) {
        public AccessLogEntry {
            errorCategory = errorCategory == null ? "NONE" : errorCategory;
        }
    }

    public record ErrorLogEntry(String traceId, String method, String path, int status, long durationMs,
                                String errorCategory, String errorCode, String message,
                                String exceptionClass) {
    }
}
