package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.ConductorTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;

@ConductorTask(name = "BatchIndexTask")
@TaskGroup(TaskGroupType.Java)
public class BatchIndexTask extends AbstractBatchIndexTask {
    @Inject
    public BatchIndexTask(
        final ElasticsearchSpewer spewer,
        final PropertiesProvider propertiesProvider,
        @Assisted Task<Long> task,
        @Assisted final Function<Double, Void> ignored
    ) throws IOException {
        super(spewer, propertiesProvider, task);
    }

    @Override
    List<Path> getFilePaths() {
        return (
            (List<String>) Optional.ofNullable(task.args.get("paths"))
                .orElseThrow(() -> new NullPointerException("missing batchId task args")))
            .stream()
            .map(Path::of)
            .toList();
    }
}
