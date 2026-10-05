package com.github.lechandonga.tracelogmetricsalertingjava.config;

import com.github.lechandonga.tracelogmetricsalertingjava.trace.TraceAwareTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 异步执行器：注入 trace 装饰器，业务中注入名为 {@code traceTaskExecutor} 的 Executor 使用。 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "traceTaskExecutor")
    public Executor traceTaskExecutor(TraceAwareTaskDecorator decorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(decorator);
        executor.initialize();
        return executor;
    }
}
