package org.icij.datashare.session;

import org.icij.datashare.policies.Role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Policy {
    String idParam() default "index";

    Role role() default Role.PROJECT_MEMBER;
}