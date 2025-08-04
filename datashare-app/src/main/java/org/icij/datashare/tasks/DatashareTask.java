package org.icij.datashare.tasks;

import java.io.Serializable;
import org.icij.task.DefaultTask;

public abstract class DatashareTask<V extends Serializable> extends DefaultTask<DatashareTaskResult<V>> {
    public abstract V runTask() throws Exception;

    @Override
    public DatashareTaskResult<V> call() throws Exception {
        return new DatashareTaskResult<>(runTask());
    }
}
