package com.github.lechandonga.tracelogmetricsalertingjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 测试业务覆盖日志扩展：在测试运行日志中输出“业务域 / 用例 / 结果”，
 * 使单测日志可直接看出覆盖了哪些业务场景及其通过情况。
 * 业务域取自测试类上的 {@link BusinessDomain} 注解（缺省用类名）。
 */
public class BusinessCoverageExtension implements BeforeAllCallback, AfterAllCallback,
        BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger LOG = LoggerFactory.getLogger("BUSINESS_COVERAGE");
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(BusinessCoverageExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        LOG.info("========== 业务域开始: {} ==========", domain(context));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        LOG.info("========== 业务域结束: {} ==========", domain(context));
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        LOG.info("[业务:{}] >>> 用例开始: {}", domain(context), caseName(context));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Optional<Throwable> exception = context.getExecutionException();
        String result = exception.isPresent() ? "失败(" + exception.get().getClass().getSimpleName() + ")" : "通过";
        LOG.info("[业务:{}] <<< 用例{}: {}", domain(context), result, caseName(context));
    }

    private String domain(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        BusinessDomain annotation = testClass.getAnnotation(BusinessDomain.class);
        return annotation != null ? annotation.value() : testClass.getSimpleName();
    }

    private String caseName(ExtensionContext context) {
        return context.getDisplayName();
    }
}
