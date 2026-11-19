package org.icij.datashare.asynctasks.shutdownfixtures;

import java.util.Map;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.temporal.TemporalActivityImpl;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;

/** Runs the task through TemporalActivityImpl.run(), the way the activities generated for Datashare tasks do. */
public class InterruptibleActivityImpl extends TemporalActivityImpl<String, InterruptibleTask>
        implements InterruptibleActivity {

    public InterruptibleActivityImpl(TaskFactory factory, TemporalInterlocutor temporal,
                                     TaskRepository taskRepository, Double progressWeight) {
        super(factory, temporal, taskRepository, progressWeight);
    }

    @Override
    public String interruptible(Map<String, Object> args) {
        try {
            return run(args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) { // unreachable in practice: taskWrapper only throws runtime exceptions
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected Class<InterruptibleTask> getTaskClass() {
        return InterruptibleTask.class;
    }
}
