package org.icij.datashare.asynctasks.temporal;

import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.PROGRESS_CUSTOM_ATTRIBUTE;

public abstract class TemporalWorkflowImpl implements TemporalWorkflow {
    public static final Logger logger = LoggerFactory.getLogger(TemporalWorkflowImpl.class);

    private double currentProgress = 0d;

    @Override
    public void progress(ProgressSignal progressSignal) {
        logger.info("Received progressSignal for activity {}, currentProgress : {}",progressSignal.activityId(), progressSignal.progress());
        currentProgress = progressSignal.progress();
        Workflow.upsertTypedSearchAttributes(
            PROGRESS_CUSTOM_ATTRIBUTE.valueSet(currentProgress)
        );
    }

    @Override
    public double getProgress() {
        return this.currentProgress;
    }
}