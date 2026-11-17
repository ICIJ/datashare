package org.icij.datashare.asynctasks;

import java.util.List;
import org.icij.datashare.Stage;

public interface StageProcessor<I, O> {
    List<String> SOURCE_EXCLUDES = List.of("content", "content_translated");

    Stage stage();

    /** What a batch holds, so an executor that receives it as JSON can convert it back. */
    Class<I> itemType();

    StageResult process(List<I> batch, StageContext<O> ctx);

    default StageActivityOptions activityOptions() {
        return StageActivityOptions.DEFAULT;
    }
}
