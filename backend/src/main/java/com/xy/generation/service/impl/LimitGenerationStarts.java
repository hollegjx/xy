package com.xy.generation.service.impl;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface LimitGenerationStarts {
    enum Scope {
        ACCOUNT("每个账号"), IP("同一来源 IP"), ENDPOINT("该接口");
        final String label;
        Scope(String label) { this.label = label; }
    }

    Scope scope() default Scope.ACCOUNT;
    int permits();
    int seconds();
}
