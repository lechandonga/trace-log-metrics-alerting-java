package com.github.lechandonga.tracelogmetricsalertingjava.trace;

import com.github.lechandonga.tracelogmetricsalertingjava.tests.BusinessDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessDomain("关联标识生成/校验/异步与并发传播")
class TraceIdPropagationTest {

    @AfterEach
    void clear() {
        TraceContext.clear();
    }

    @Nested
    @DisplayName("生成与校验规则")
    class Generation {

        @Test
        @DisplayName("缺失时生成: tl 前缀 + 40 位长度且自身合法")
        void generatedIdIsValid() {
            String traceId = new TraceIdGenerator().generate();
            assertEquals(40, traceId.length());
            assertTrue(TraceIdValidator.isValid(traceId));
        }

        @Test
        @DisplayName("并发生成不重复")
        void generatedIdsAreUnique() throws Exception {
            TraceIdGenerator generator = new TraceIdGenerator();
            int threads = 16;
            int perThread = 200;
            Map<String, Boolean> seen = new ConcurrentHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            seen.put(generator.generate(), Boolean.TRUE);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            pool.shutdown();
            assertEquals(threads * perThread, seen.size());
        }

        @Test
        @DisplayName("合法外部标识沿用: 32 位小写十六进制")
        void acceptsExternalHex() {
            String external = "a".repeat(32);
            assertTrue(TraceIdValidator.isValid(external));
            assertNotEquals(external, new TraceIdGenerator().generate());
        }

        @Test
        @DisplayName("非法标识确定性拒绝: 大写/越界字符/超长/超短/空")
        void rejectsIllegalIds() {
            assertFalse(TraceIdValidator.isValid(null));
            assertFalse(TraceIdValidator.isValid(""));
            assertFalse(TraceIdValidator.isValid("A".repeat(32)));
            assertFalse(TraceIdValidator.isValid("z".repeat(32)));
            assertFalse(TraceIdValidator.isValid("g".repeat(32)));
            assertFalse(TraceIdValidator.isValid("a".repeat(31)));
            assertFalse(TraceIdValidator.isValid("a".repeat(65)));
            assertFalse(TraceIdValidator.isValid("hello world"));
            assertFalse(TraceIdValidator.isValid("../etc/passwd"));
        }
    }

    @Nested
    @DisplayName("异步与并发传播")
    class Propagation {

        private final TraceAwareTaskDecorator decorator = new TraceAwareTaskDecorator();

        @Test
        @DisplayName("异步任务继承提交线程的 traceId，执行后清理工作线程")
        @SuppressWarnings("unchecked")
        void asyncTaskCarriesTraceId() throws Exception {
            String traceId = new TraceIdGenerator().generate();
            TraceContext.set(traceId);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<String> future = worker.submit(() -> {
                    String[] holder = new String[1];
                    decorator.decorate(
                            () -> holder[0] = TraceContext.get().orElse(null)).run();
                    return holder[0];
                });
                assertEquals(traceId, future.get(5, TimeUnit.SECONDS));

                Future<String> after = worker.submit(() -> TraceContext.get().orElse(null));
                assertEquals(null, after.get(5, TimeUnit.SECONDS));
            } finally {
                worker.shutdown();
            }
        }

        @RepeatedTest(3)
        @DisplayName("并发多请求不串号: 线程池中每个任务只读到自己的 traceId")
        void concurrentRequestsDoNotMix() throws Exception {
            int requestCount = 64;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch ready = new CountDownLatch(requestCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger mismatches = new AtomicInteger();
            try {
                Future<?>[] futures = new Future[requestCount];
                for (int i = 0; i < requestCount; i++) {
                    String expected = "a".repeat(32 - String.valueOf(i).length()) + i;
                    Runnable job = decorator.decorate(() -> {
                        ready.countDown();
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        for (int j = 0; j < 10; j++) {
                            if (!expected.equals(TraceContext.require())) {
                                mismatches.incrementAndGet();
                            }
                            Thread.yield();
                        }
                    });
                    futures[i] = pool.submit(() -> {
                        TraceContext.set(expected);
                        try {
                            job.run();
                        } finally {
                            TraceContext.clear();
                        }
                    });
                }
                ready.await(10, TimeUnit.SECONDS);
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
                assertEquals(0, mismatches.get(), "发生了 traceId 串号");
            } finally {
                pool.shutdown();
            }
        }

        @Test
        @DisplayName("未绑定 traceId 时异步任务不伪造标识")
        void missingTraceIdStaysMissing() throws Exception {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> future = worker.submit(() -> {
                    boolean[] holder = new boolean[1];
                    decorator.decorate(() -> holder[0] = TraceContext.get().isPresent()).run();
                    return holder[0];
                });
                assertFalse(future.get(5, TimeUnit.SECONDS));
            } finally {
                worker.shutdown();
            }
        }
    }
}
