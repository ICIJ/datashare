package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class ScanIndexNERWorkflowImpl extends TemporalWorkflowImpl implements ScanIndexNERWorkflow {
    private final ScanActivity scanActivity;
    private IndexActivity indexActivity;
    private NERActivity nerActivity;

    public ScanIndexNERWorkflowImpl() {
        this.scanActivity = Workflow.newActivityStub(
            ScanActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.of(24, ChronoUnit.HOURS)).build()
        );
        this.indexActivity = Workflow.newActivityStub(
            IndexActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.of(24, ChronoUnit.HOURS)).build()
        );
        this.nerActivity = Workflow.newActivityStub(
            NERActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.of(24, ChronoUnit.HOURS)).build()
        );
    }

    @Override
    public void scanIndexNER(ScanIndexNERWorkflow.ScanIndexNERPayload payload) throws Exception {
        this.scanActivity.scan(payload.asScanPayload());
        this.indexActivity.index(payload.asIndexPayload());
        // TODO: here we could route message to a different queue based on the NER type => Space -> Python queue
        this.nerActivity.ner(payload.asNERPayload());
        // TODO: we could return a summary of all 3 tasks
    }
}
