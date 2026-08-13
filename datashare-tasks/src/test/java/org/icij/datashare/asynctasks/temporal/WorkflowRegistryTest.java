package org.icij.datashare.asynctasks.temporal;

import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.tasks.RoutingStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import static org.junit.Assert.assertThrows;

public class WorkflowRegistryTest {
    // The package holding the HelloWorld/Failing/DoNothing workflow fixtures, next to the TemporalWorkflow
    // signal/query-only interface that has no @WorkflowMethod.
    private static final String FIXTURES_PACKAGE = "org.icij.datashare.asynctasks.temporal";
    private static final Group JAVA_GROUP = new Group(TaskGroupType.Java);

    private final WorkflowRegistry registry = new WorkflowRegistry();

    /** Stands in for a real activity instance, and records which implementation it was built from. */
    private record FakeActivity(Class<?> activityClass) { }

    // ------------------------------------------------------------------ manual registration

    @Test
    public void test_registered_queues_is_empty_when_nothing_is_registered() {
        assertThat(registry.registeredQueues()).isEmpty();
        assertThat(registry.registeredWorkflows("unknown-queue")).isEmpty();
        assertThat(registry.registeredActivities("unknown-queue")).isEmpty();
    }

    @Test
    public void test_registered_queues_unions_workflow_and_activity_queues_in_registration_order() {
        registry.registerWorkflow(HelloWorldWorkflowImpl.class, "first");
        registry.registerActivity(new FakeActivity(HelloWorldActivityImpl.class), "second");
        registry.registerWorkflow(FailingWorkflowImpl.class, "first");

        assertThat(new ArrayList<>(registry.registeredQueues())).isEqualTo(List.of("first", "second"));
    }

    @Test
    public void test_registers_the_same_workflow_on_several_queues() {
        registry.registerWorkflow(HelloWorldWorkflowImpl.class, "left");
        registry.registerWorkflow(HelloWorldWorkflowImpl.class, "right");

        assertThat(registry.registeredWorkflows("left")).containsOnly(HelloWorldWorkflowImpl.class);
        assertThat(registry.registeredWorkflows("right")).containsOnly(HelloWorldWorkflowImpl.class);
    }

    @Test
    public void test_registering_a_workflow_twice_on_a_queue_keeps_one() {
        // Temporal throws TypeAlreadyRegisteredException when the same workflow type reaches a worker twice
        registry.registerWorkflow(HelloWorldWorkflowImpl.class, WORKFLOWS_DEFAULT);
        registry.registerWorkflow(HelloWorldWorkflowImpl.class, WORKFLOWS_DEFAULT);

        assertThat(registry.registeredWorkflows(WORKFLOWS_DEFAULT)).hasSize(1);
    }

    @Test
    public void test_registered_collections_cannot_be_mutated_by_callers() {
        registry.registerWorkflow(HelloWorldWorkflowImpl.class, WORKFLOWS_DEFAULT);

        assertThrows(UnsupportedOperationException.class,
            () -> registry.registeredWorkflows(WORKFLOWS_DEFAULT).add(FailingWorkflowImpl.class));
    }

    @Test
    public void test_registering_null_is_rejected() {
        assertThrows(NullPointerException.class, () -> registry.registerWorkflow(null, WORKFLOWS_DEFAULT));
        assertThrows(NullPointerException.class, () -> registry.registerWorkflow(HelloWorldWorkflowImpl.class, null));
        assertThrows(NullPointerException.class, () -> registry.registerActivity(null, WORKFLOWS_DEFAULT));
        assertThrows(NullPointerException.class, () -> registry.registerActivity(new Object(), null));
    }

    // ------------------------------------------------------------------ discovery

    @Test
    public void test_discover_registers_implementation_and_activity_of_each_workflow() {
        registry.discoverWorkflows(FIXTURES_PACKAGE, FakeActivity::new, RoutingStrategy.UNIQUE, JAVA_GROUP);

        assertThat(registry.registeredWorkflows(WORKFLOWS_DEFAULT)).containsOnly(
            HelloWorldWorkflowImpl.class, FailingWorkflowImpl.class, DoNothingWorkflowImpl.class);
        assertThat(registry.registeredActivities(WORKFLOWS_DEFAULT)).containsOnly(
            new FakeActivity(HelloWorldActivityImpl.class),
            new FakeActivity(FailingActivityImpl.class),
            new FakeActivity(DoNothingActivityImpl.class));
    }

    @Test
    public void test_discover_skips_interfaces_without_a_workflow_method() {
        registry.discoverWorkflows(FIXTURES_PACKAGE, FakeActivity::new, RoutingStrategy.UNIQUE, JAVA_GROUP);

        // TemporalWorkflow is annotated with @WorkflowInterface but only carries signal and query methods, so
        // neither its implementation nor the abstract TemporalActivityImpl must be pulled in
        assertThat(registry.registeredWorkflows(WORKFLOWS_DEFAULT)).hasSize(3).excludes(TemporalWorkflowImpl.class);
    }

    @Test
    public void test_discover_puts_every_workflow_on_the_default_queue_for_the_unique_strategy() {
        registry.discoverWorkflows(FIXTURES_PACKAGE, FakeActivity::new, RoutingStrategy.UNIQUE, JAVA_GROUP);

        assertThat(registry.registeredQueues()).containsOnly(WORKFLOWS_DEFAULT);
    }

    @Test
    public void test_discover_puts_every_workflow_on_the_group_queue_for_the_group_strategy() {
        registry.discoverWorkflows(FIXTURES_PACKAGE, FakeActivity::new, RoutingStrategy.GROUP, JAVA_GROUP);

        assertThat(registry.registeredQueues()).containsOnly("java");
        assertThat(registry.registeredWorkflows("java")).hasSize(3);
    }

    @Test
    public void test_discover_gives_each_workflow_its_own_queue_for_the_name_strategy() {
        registry.discoverWorkflows(FIXTURES_PACKAGE, FakeActivity::new, RoutingStrategy.NAME, JAVA_GROUP);

        // the queue is the lower cased @WorkflowMethod name of each interface
        assertThat(registry.registeredQueues()).containsOnly(
            "hello-world", "failing", "org.icij.datashare.asynctasks.temporal.donothingtask");
        assertThat(registry.registeredWorkflows("hello-world")).containsOnly(HelloWorldWorkflowImpl.class);
        assertThat(registry.registeredActivities("failing")).containsOnly(new FakeActivity(FailingActivityImpl.class));
    }

    @Test
    public void test_discover_finds_nothing_in_a_package_without_workflows() {
        registry.discoverWorkflows("org.icij.datashare.asynctasks.bus", FakeActivity::new, RoutingStrategy.UNIQUE, JAVA_GROUP);

        assertThat(registry.registeredQueues()).isEmpty();
    }

    // ------------------------------------------------------------------ discovery failures

    @Test
    public void test_discover_fails_when_the_workflow_implementation_is_missing() {
        WorkflowRegistry.WorkflowRegistrationException thrown =
            assertThrows(WorkflowRegistry.WorkflowRegistrationException.class,
                () -> registry.discoverWorkflows("org.icij.datashare.workflowfixtures.missingimpl",
                    FakeActivity::new, RoutingStrategy.UNIQUE, JAVA_GROUP));

        assertThat(thrown.getMessage()).contains("MissingImplWorkflowImpl");
        assertThat(thrown.getCause()).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    public void test_discover_fails_when_the_activity_implementation_is_missing() {
        WorkflowRegistry.WorkflowRegistrationException thrown =
            assertThrows(WorkflowRegistry.WorkflowRegistrationException.class,
                () -> registry.discoverWorkflows("org.icij.datashare.workflowfixtures.missingactivity",
                    FakeActivity::new, RoutingStrategy.UNIQUE, JAVA_GROUP));

        assertThat(thrown.getMessage()).contains("LonelyActivityImpl");
        assertThat(thrown.getCause()).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    public void test_discover_fails_when_the_workflow_interface_is_not_suffixed() {
        WorkflowRegistry.WorkflowRegistrationException thrown =
            assertThrows(WorkflowRegistry.WorkflowRegistrationException.class,
                () -> registry.discoverWorkflows("org.icij.datashare.workflowfixtures.badname",
                    FakeActivity::new, RoutingStrategy.UNIQUE, JAVA_GROUP));

        assertThat(thrown.getMessage()).contains("NotSuffixed");
        assertThat(thrown.getMessage()).contains("Workflow");
    }

    @Test
    public void test_discover_fails_when_the_activity_cannot_be_instantiated() {
        IllegalStateException cause = new IllegalStateException("no such constructor");

        WorkflowRegistry.WorkflowRegistrationException thrown =
            assertThrows(WorkflowRegistry.WorkflowRegistrationException.class,
                () -> registry.discoverWorkflows(FIXTURES_PACKAGE, activityClass -> {
                    throw cause;
                }, RoutingStrategy.UNIQUE, JAVA_GROUP));

        assertThat(thrown.getCause()).isSameAs(cause);
    }

    // ------------------------------------------------------------------ workflow key parsing

    @Test
    public void test_parse_workflow_key_reads_the_workflow_method_name() {
        assertThat(WorkflowRegistry.buildWorkflowKeyFrom(HelloWorldWorkflow.class)).isEqualTo("hello-world");
    }

    @Test
    public void test_parse_workflow_key_rejects_an_interface_without_a_workflow_method() {
        assertThrows(WorkflowRegistry.WorkflowRegistrationException.class,
            () -> WorkflowRegistry.buildWorkflowKeyFrom(TemporalWorkflow.class));
    }
}
