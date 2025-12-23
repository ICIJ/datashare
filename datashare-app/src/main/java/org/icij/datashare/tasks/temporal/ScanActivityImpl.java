package org.icij.datashare.tasks.temporal;

import io.temporal.client.WorkflowClient;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.tasks.ScanTask;

public class ScanActivityImpl extends TemporalActivityImpl<Long, ScanTask, ScanActivity.ScanPayload>
    implements ScanActivity {

    public ScanActivityImpl(TaskFactory factory, WorkflowClient client, Double progressWeight) {
        super(factory, client, progressWeight);
    }

    @Override
    public long scan(ScanPayload payload) throws Exception {
        return run(payload);
    }

    @Override
    protected Class<ScanTask> getTaskClass() {
        return ScanTask.class;
    }
}
