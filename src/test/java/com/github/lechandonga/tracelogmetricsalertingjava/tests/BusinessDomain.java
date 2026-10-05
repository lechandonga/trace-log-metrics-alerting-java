package com.github.lechandonga.tracelogmetricsalertingjava.tests;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标注测试类覆盖的业务域名称，用于业务覆盖日志。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BusinessDomain {
    String value();
}
