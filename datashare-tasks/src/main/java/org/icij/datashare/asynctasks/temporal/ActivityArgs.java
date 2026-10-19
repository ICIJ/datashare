package org.icij.datashare.asynctasks.temporal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares, on an activity implementation, the types of its constructor parameters that follow the four
 * conventional ones (see {@link WorkflowRegistry#activityFactoryForSingleActivitiesWorkflow}), in order. Each type is
 * resolved by the registry's caller, typically from the injector.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ActivityArgs {
    Class<?>[] value();
}
