package org.icij.datashare.tasks.temporal;

import io.temporal.client.WorkflowClient;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.tasks.IndexTask;

public class IndexActivityImpl extends TemporalActivityImpl<Long, IndexTask, IndexActivity.IndexPayload>
    implements IndexActivity {

    public IndexActivityImpl(TaskFactory factory, WorkflowClient client, Double progressWeight) {
        super(factory, client, progressWeight);
    }

    @Override
    public long index(IndexActivity.IndexPayload payload) throws Exception {
        return run(payload);
    }

    @Override
    protected Class<IndexTask> getTaskClass() {
        return IndexTask.class;
    }
}
