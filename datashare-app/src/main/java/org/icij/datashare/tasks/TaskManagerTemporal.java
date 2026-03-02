package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;


@Singleton
public class TaskManagerTemporal extends org.icij.datashare.asynctasks.TaskManagerTemporal
    implements DatashareTaskManager {

    @Inject
    public TaskManagerTemporal(TemporalInterlocutor temporal, PropertiesProvider propertiesProvider) {
        super(temporal, Utils.getRoutingStrategy(propertiesProvider));
    }
}
