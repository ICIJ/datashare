package org.icij.datashare.policies;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TaskPolicy {
    String idParam() default "taskName:";

    Role role() default Role.PROJECT_MEMBER;
}