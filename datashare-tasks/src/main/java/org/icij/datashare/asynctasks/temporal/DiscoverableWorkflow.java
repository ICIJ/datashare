package org.icij.datashare.asynctasks.temporal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the workflow interfaces that {@link WorkflowRegistry#discoverWorkflows} registers.
 * The annotated Workflow must have a single activity and follow the naming convention to be discoverable :
 * Name XyzWorkflow, Implementation XyzWorkflowImpl, Single Activity Implementation : XyzActivityImpl
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DiscoverableWorkflow {}
