package org.icij.datashare.asynctasks.temporal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TemporalSingleActivityWorkflow {
    String name();

    ActivityOpts activityOptions();
}