package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.temporal.client.WorkflowClient;
import org.icij.datashare.PropertiesProvider;


@Singleton
public class TaskManagerTemporal extends org.icij.datashare.asynctasks.TaskManagerTemporal
    implements DatashareTaskManager {

    @Inject
    public TaskManagerTemporal(WorkflowClient client, PropertiesProvider propertiesProvider) {
        super(client, Utils.getRoutingStrategy(propertiesProvider));
    }
}
