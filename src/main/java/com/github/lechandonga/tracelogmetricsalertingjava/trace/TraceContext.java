package com.github.lechandonga.tracelogmetricsalertingjava.trace;

import java.util.Optional;

/**
 * 当前线程的关联标识上下文。底层使用 {@link InheritableThreadLocal}，
 * 以便“请求线程内新建线程”的场景可继承；线程池复用时必须显式
 * {@link #clear()}，由 trace-aware 的任务包装器负责透传与清理。
 */
public final class TraceContext {

    private static final ThreadLocal<String> HOLDER = new InheritableThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static Optional<String> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static String require() {
        String traceId = HOLDER.get();
        if (traceId == null) {
            throw new IllegalStateException("no trace id bound to current thread");
        }
        return traceId;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
