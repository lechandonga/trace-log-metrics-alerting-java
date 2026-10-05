package com.github.lechandonga.tracelogmetricsalertingjava.trace;

import com.github.lechandonga.tracelogmetricsalertingjava.common.ApiError;
import com.github.lechandonga.tracelogmetricsalertingjava.common.ErrorCategory;
import com.github.lechandonga.tracelogmetricsalertingjava.log.StructuredLogger;
import com.github.lechandonga.tracelogmetricsalertingjava.metrics.InMemoryMetricsRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路追踪 + 访问日志过滤器（过滤器链最前端）。
 * <ul>
 *   <li>请求头携带合法 {@code X-Trace-Id} 时沿用（source=incoming）；
 *       缺失时生成并回写响应头（source=generated）。</li>
 *   <li>携带了但非法时确定性拒绝：HTTP 400 + VALIDATION + INVALID_TRACE_ID，
 *       响应体 traceId 为 {@code invalid}，绝不静默接受或替换。</li>
 *   <li>traceId 绑定到 {@link TraceContext} 与 MDC，下游所有日志自动携带；
 *       支持 Servlet 异步派发，完成时在 ASYNC_DISPATCHED 上记一次访问日志，
 *       同步请求记一次，保证每条请求恰好一条访问日志。</li>
 * </ul>
 */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String INVALID_TRACE_ID = "invalid";

    private static final String START_TIME_ATTR = TraceIdFilter.class.getName() + ".startTime";
    private static final String SOURCE_ATTR = TraceIdFilter.class.getName() + ".source";
    private static final String TRACE_ID_ATTR = TraceIdFilter.class.getName() + ".traceId";

    private final TraceIdGenerator generator;
    private final StructuredLogger structuredLogger;
    private final ObjectMapper objectMapper;
    private final InMemoryMetricsRegistry metricsRegistry;

    public TraceIdFilter(TraceIdGenerator generator, StructuredLogger structuredLogger,
                         ObjectMapper objectMapper, InMemoryMetricsRegistry metricsRegistry) {
        this.generator = generator;
        this.structuredLogger = structuredLogger;
        this.objectMapper = objectMapper;
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // 让 ERROR 派发也经过本过滤器（此时已在初始 REQUEST 中绑定过 traceId，OncePerFilter 已防重）
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // CompletableFuture 等异步返回会在完成后发起 ASYNC 派发，必须再次进入过滤器：
        // 重新绑定 traceId 并输出这一条请求“唯一”的访问日志
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
            // 异步请求最终回到 ASYNC 派发：OncePerRequestFilter 会再次执行本方法
            handleAsyncCompletion(request, response, filterChain);
            return;
        }

        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && !incoming.isBlank() && !TraceIdValidator.isValid(incoming.trim())) {
            rejectInvalidTraceId(request, response, incoming);
            return;
        }

        String traceId;
        String source;
        if (incoming != null && !incoming.isBlank()) {
            traceId = incoming.trim();
            source = "incoming";
        } else {
            traceId = generator.generate();
            source = "generated";
        }

        bind(traceId);
        request.setAttribute(START_TIME_ATTR, System.nanoTime());
        request.setAttribute(SOURCE_ATTR, source);
        request.setAttribute(TRACE_ID_ATTR, traceId);
        // 立即回写：即便控制器返回已完成的 CompletableFuture（仍会走异步派发），
        // 调用方在初始响应上也能拿到标识
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
            if (request.isAsyncStarted()) {
                // 访问日志推迟到异步完成后的 ASYNC 派发
                return;
            }
            writeAccessLog(request, response, traceId, source);
        } finally {
            // 即便请求进入异步，也立即清理请求线程的 MDC/TraceContext，防止线程复用串号；
            // 异步线程的标识由 TraceAwareTaskDecorator 负责，ASYNC 派发时再重新绑定。
            cleanup();
        }
    }

    private void handleAsyncCompletion(HttpServletRequest request, HttpServletResponse response,
                                       FilterChain filterChain)
            throws ServletException, IOException {
        // ASYNC 派发时 OncePerRequestFilter 视为新的一次过滤；重新绑定后继续走链（直至结束）
        String traceId = (String) request.getAttribute(TRACE_ID_ATTR);
        if (traceId == null) {
            traceId = response.getHeader(TRACE_ID_HEADER);
        }
        if (traceId == null) {
            traceId = INVALID_TRACE_ID;
        }
        bind(traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
            String source = (String) request.getAttribute(SOURCE_ATTR);
            writeAccessLog(request, response, traceId, source == null ? "incoming" : source);
        } finally {
            cleanup();
        }
    }

    private void rejectInvalidTraceId(HttpServletRequest request, HttpServletResponse response,
                                      String rawTraceId) throws IOException {
        long start = System.nanoTime();
        ApiError error = new ApiError(INVALID_TRACE_ID, ErrorCategory.VALIDATION,
                "INVALID_TRACE_ID",
                "header " + TRACE_ID_HEADER + " is malformed; expected 32-64 lowercase "
                        + "hex characters");
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(TRACE_ID_HEADER, INVALID_TRACE_ID);
        objectMapper.writeValue(response.getWriter(), error);

        StructuredLogger.AccessLogEntry entry = new StructuredLogger.AccessLogEntry(
                INVALID_TRACE_ID, "rejected", request.getMethod(), request.getRequestURI(),
                HttpStatus.BAD_REQUEST.value(), elapsedMs(start),
                ErrorCategory.VALIDATION.name(), "INVALID_TRACE_ID", null);
        structuredLogger.access(entry);
        structuredLogger.error(new StructuredLogger.ErrorLogEntry(
                INVALID_TRACE_ID, request.getMethod(), request.getRequestURI(),
                HttpStatus.BAD_REQUEST.value(), elapsedMs(start),
                ErrorCategory.VALIDATION.name(), "INVALID_TRACE_ID",
                "rejected malformed trace id (length=" + (rawTraceId == null ? 0 : rawTraceId.length())
                        + ")", "InvalidTraceIdException"));
        metricsRegistry.record(true, elapsedMs(start));
    }

    private void writeAccessLog(HttpServletRequest request, HttpServletResponse response,
                                String traceId, String source) {
        Object startAttr = request.getAttribute(START_TIME_ATTR);
        long durationMs = startAttr instanceof Long start ? elapsedNanos(start) : -1L;
        int status = response.getStatus();
        ErrorCategory category = ErrorCategory.NONE;
        String code = null;
        Object codeAttr = request.getAttribute(GlobalExceptionHandler.ERROR_CODE_ATTR);
        Object categoryAttr = request.getAttribute(GlobalExceptionHandler.ERROR_CATEGORY_ATTR);
        if (categoryAttr instanceof ErrorCategory c && c != ErrorCategory.NONE) {
            category = c;
            code = codeAttr == null ? null : codeAttr.toString();
        } else if (status >= 500) {
            category = ErrorCategory.SYSTEM;
        } else if (status >= 400) {
            category = ErrorCategory.VALIDATION;
        }
        metricsRegistry.record(category != ErrorCategory.NONE, durationMs);
        structuredLogger.access(new StructuredLogger.AccessLogEntry(
                traceId, source, request.getMethod(), request.getRequestURI(),
                status, durationMs, category.name(), code, extractQueryParams(request)));
    }

    private java.util.Map<String, String> extractQueryParams(HttpServletRequest request) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                params.put(key, values[0]);
            }
        });
        return params;
    }

    private static long elapsedMs(long startNanos) {
        return elapsedNanos(startNanos);
    }

    private static long elapsedNanos(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void bind(String traceId) {
        TraceContext.set(traceId);
        MDC.put(StructuredLogger.MDC_TRACE_ID, traceId);
    }

    private static void cleanup() {
        TraceContext.clear();
        MDC.remove(StructuredLogger.MDC_TRACE_ID);
    }
}
