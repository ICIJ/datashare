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

@ConductorTask(name = "BatchIndexWithSerializerTask")
@TaskGroup(TaskGroupType.Java)
public class BatchIndexWithSerializerTask extends AbstractBatchIndexTask {
    private final BatchSerializer<String> batchSerializer;
    private final String batchId;

    @Inject
    public BatchIndexWithSerializerTask(
        final ElasticsearchSpewer spewer,
        BatchSerializer<String> batchSerializer,
        final PropertiesProvider propertiesProvider,
        @Assisted Task<Long> task,
        @Assisted final Function<Double, Void> ignored
    ) throws IOException {
        super(spewer, propertiesProvider, task);
        this.batchSerializer = batchSerializer;
        this.batchId = (String) Optional.ofNullable(this.task.args.get("batchId"))
            .orElseThrow(() -> new NullPointerException("missing batchId task args"));
    }

    @Override
    List<Path> getFilePaths() throws IOException {
        return batchSerializer.getBatch(batchId).stream().map(s -> Path.of((String)s)).toList();
    }
}
