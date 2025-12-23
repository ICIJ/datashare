package org.icij.datashare.tasks.temporal;

import io.temporal.client.WorkflowClient;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.tasks.ExtractNlpTask;

public class NERActivityImpl extends TemporalActivityImpl<Long, ExtractNlpTask, NERActivity.NERPayload>
    implements NERActivity {

    public NERActivityImpl(TaskFactory factory, WorkflowClient client, Double progressWeight) {
        super(factory, client, progressWeight);
    }

    @Override
    public long ner(NERPayload payload) throws Exception {
        return run(payload);
    }

    @Override
    protected Class<ExtractNlpTask> getTaskClass() {
        return ExtractNlpTask.class;
    }
}
