package org.icij.datashare.asynctasks.temporal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ActivityOpts {
    String taskQueue() default "default-java";

    String timeout() default "7d";

    Class<? extends Exception>[] retriables() default {};
}