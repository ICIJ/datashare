package org.icij.datashare.asynctasks;

import java.util.List;
import java.util.Map;
import org.icij.datashare.Stage;

public interface PipelineExecutor {
    boolean run(List<Stage> stages, Map<String, Object> args) throws Exception;
}
