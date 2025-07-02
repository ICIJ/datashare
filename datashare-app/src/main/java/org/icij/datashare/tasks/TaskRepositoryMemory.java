package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class TaskRepositoryMemory extends org.icij.datashare.asynctasks.TaskRepositoryMemory{
    @Inject
    public TaskRepositoryMemory() {
        super();
        this.registerTaskResultTypes(TaskResultSubtypes.getClasses());
    }
}
