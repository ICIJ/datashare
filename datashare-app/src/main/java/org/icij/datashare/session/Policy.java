package org.icij.datashare.session;

import org.icij.datashare.user.Role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Policy {
    String idParam() default "index";

    Role[] roles() default {};

    ResourceType resourceType() default ResourceType.PROJECT;

    /**
     * Enum representing the types of resources that can be protected by policies.
     */
    enum ResourceType {
        PROJECT,
        TASK
    }
}