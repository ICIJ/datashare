package org.icij.datashare.policies;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Guards a route on the {@code :project} and {@code :mappingId} path params: a project admin passes,
 *  and so does the mapping's author when they are a project member. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MappingPolicy {}
