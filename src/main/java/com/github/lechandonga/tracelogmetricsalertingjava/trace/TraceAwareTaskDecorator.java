package com.github.lechandonga.tracelogmetricsalertingjava.trace;

import com.github.lechandonga.tracelogmetricsalertingjava.log.StructuredLogger;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * 异步任务的关联标识装饰器：提交任务时在“请求线程”捕获 traceId，
 * 执行前绑定到工作线程的 {@link TraceContext} 与 MDC，执行后清理，
 * 保证线程池复用不串号、异步日志不丢标识。
 */
@Component
public class TraceAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        String capturedTraceId = TraceContext.get().orElse(null);
        return () -> {
            String previous = MDC.get(StructuredLogger.MDC_TRACE_ID);
            try {
                if (capturedTraceId != null) {
                    TraceContext.set(capturedTraceId);
                    MDC.put(StructuredLogger.MDC_TRACE_ID, capturedTraceId);
                }
                runnable.run();
            } finally {
                TraceContext.clear();
                if (previous == null) {
                    MDC.remove(StructuredLogger.MDC_TRACE_ID);
                } else {
                    MDC.put(StructuredLogger.MDC_TRACE_ID, previous);
                }
            }
        };
    }
}
