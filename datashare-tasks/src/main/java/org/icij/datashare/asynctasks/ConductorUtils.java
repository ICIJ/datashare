package org.icij.datashare.asynctasks;

import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import com.netflix.conductor.client.exception.ConductorClientException;
import com.netflix.conductor.client.http.ConductorClient;
import com.netflix.conductor.client.http.MetadataClient;
import com.netflix.conductor.client.http.TaskClient;
import com.netflix.conductor.client.http.WorkflowClient;
import com.netflix.conductor.sdk.workflow.executor.WorkflowExecutor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConductorUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConductorUtils.class);

    public static WorkflowExecutor buildConductorExecutor(
        TaskFactory factory, ConductorClient client, String routingKey, int taskWorkersNb
    ) {
        TaskClient taskClient = new TaskClient(client);
        WorkflowClient wfClient = new WorkflowClient(client);
        MetadataClient metadataClient = new MetadataClient(client);
        FactoryConductorWorkerExecutor factoryExecutor = new FactoryConductorWorkerExecutor(
            factory, taskClient, routingKey, taskWorkersNb
        );
        return new WorkflowExecutor(taskClient, wfClient, metadataClient, factoryExecutor);
    }

    public static void declareTasksAndWorkflows(WorkflowExecutor executor, List<Path> taskDeclarationPaths, List<Path> workflowDeclarationPaths)
        throws IOException {
        // Declare tasks first
        taskDeclarationPaths.forEach(rethrowConsumer(taskDefPath -> {
                try {
                    executor.loadTaskDefs(taskDefPath.getFileName().toString());
                } catch (ConductorClientException e) {
                    if (e.getStatus() == 500 && e.getMessage().contains("already exists")) {
                        LOGGER.info("skipping task def as task already exist");
                    } else {
                        throw e;
                    }
                }
            }
        ));
        // Then declare workflows
        workflowDeclarationPaths.forEach(rethrowConsumer(wfDefPath -> {
                try {
                    executor.loadWorkflowDefs(wfDefPath.getFileName().toString());
                } catch (ConductorClientException e) {
                    if (e.getStatus() == 500 && e.getMessage().contains("already exists")) {
                        LOGGER.info("skipping workflow def as workflow already exist");
                    }
                }
            }
        ));
    }
}
