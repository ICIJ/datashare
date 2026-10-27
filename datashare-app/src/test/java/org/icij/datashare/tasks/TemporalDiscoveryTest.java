package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.junit.Test;

public class TemporalDiscoveryTest {
    /**
     * CommonMode.runTemporalWorkers discovers every workflow of this package and demands an activity named after
     * each: a workflow that is not a single-activity one must live outside of it, or the worker fails at startup.
     */
    @Test
    public void test_discovery_of_the_package_the_workers_scan_does_not_fail() {
        new WorkflowRegistry().discoverWorkflows("org.icij.datashare.tasks", activityClass -> new Object(),
                                                 RoutingStrategy.UNIQUE, new Group(TaskGroupType.Java));
    }
}
