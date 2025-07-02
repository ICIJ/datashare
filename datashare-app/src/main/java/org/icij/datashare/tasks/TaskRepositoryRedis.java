package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.redisson.api.RedissonClient;

@Singleton
public class TaskRepositoryRedis extends org.icij.datashare.asynctasks.TaskRepositoryRedis{
    @Inject
    public TaskRepositoryRedis(RedissonClient redisson) {
        super(redisson);
        this.registerTaskResultTypes(TaskResultSubtypes.getClasses());
    }
}
