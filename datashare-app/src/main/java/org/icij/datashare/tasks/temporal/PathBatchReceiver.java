package org.icij.datashare.tasks.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import java.nio.file.Path;
import java.util.List;

@WorkflowInterface
public interface PathBatchReceiver {
    @SignalMethod
    void onPaths(List<Path> paths);
}
