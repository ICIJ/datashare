package org.icij.datashare.asynctasks.temporal;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.temporal.TemporalHelper.discoverWorkflows;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import io.temporal.client.WorkflowClient;
import java.util.List;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.TestFactory;
import org.icij.datashare.tasks.RoutingStrategy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class TemporalHelperTest {
    private AutoCloseable mocks;

    @Mock
    private WorkflowClient client;

    @Mock
    private Group helloWorldGroup;

    @Before
    public void setUp() {
        mocks = openMocks(this);
        when(helloWorldGroup.getId()).thenReturn("HelloWorld");
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void test_should_discover_workflows_with_unique_routing() throws ClassNotFoundException {
        RoutingStrategy routingStrategy = RoutingStrategy.UNIQUE;

        List<TemporalHelper.RegisteredWorkflow> registered = discoverWorkflows(
            "org.icij.datashare.asynctasks.temporal",
            new TestFactory(),
            client,
            routingStrategy,
            helloWorldGroup
        );

        assertThat(registered).hasSize(2);
    }

    @Test
    public void test_should_discover_workflows_with_group_routing() throws ClassNotFoundException {
        RoutingStrategy routingStrategy = RoutingStrategy.GROUP;

        List<TemporalHelper.RegisteredWorkflow> registered = discoverWorkflows(
            "org.icij.datashare.asynctasks.temporal",
            new TestFactory(),
            client,
            routingStrategy,
            helloWorldGroup
        );

        assertThat(registered).hasSize(1);
        TemporalHelper.RegisteredWorkflow  registeredWf = registered.get(0);
        assertThat(registeredWf.workflowCls()).isEqualTo(HelloWorldWorkflowImpl.class);
    }

    @Test
    public void test_should_discover_workflows_with_name_routing() throws ClassNotFoundException {
        RoutingStrategy routingStrategy = RoutingStrategy.NAME;

        List<TemporalHelper.RegisteredWorkflow> registered = discoverWorkflows(
            "org.icij.datashare.asynctasks.temporal",
            new TestFactory(),
            client,
            routingStrategy,
            helloWorldGroup
        );

        assertThat(registered).hasSize(1);
        TemporalHelper.RegisteredWorkflow  registeredWf = registered.get(0);
        assertThat(registeredWf.workflowCls()).isEqualTo(HelloWorldWorkflowImpl.class);
    }
}
