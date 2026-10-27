package org.icij.datashare.asynctasks.pipeline;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;
import java.util.Map;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageResult;

/** Runs the batch of any stage through its registered processor: one activity, whatever the stage. */
@ActivityInterface
public interface StageActivity {
    /**
     * @param options the properties of the run, as the queue executor gives them to a stage
     * @param batch items as the stage's processor declares them, received as JSON
     */
    @ActivityMethod(name = "stage")
    StageResult run(Stage stage, Map<String, Object> options, List<?> batch);
}
