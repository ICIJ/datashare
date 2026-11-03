package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static java.util.Optional.ofNullable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static java.util.stream.Stream.of;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PARSE_TIMEOUT;
import static org.icij.datashare.cli.DatashareCliOptions.PARSE_TIMEOUT_OPT;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactConfigurationException;
import org.icij.datashare.text.artifact.ArtifactContext;
import org.icij.datashare.text.artifact.ArtifactProducer;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.extract.extractor.ExtractorOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TemporalSingleActivityWorkflow(name = "artifact", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class ArtifactTask extends StageTask<String, Void> {
    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, StageRegistry registry,
                        final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                        @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, new PropertiesProvider(
                      of(taskView.args, Map.of("taskId", taskView.id)).flatMap(m -> m.entrySet().stream()).collect(
                              Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))), String.class,
              gateFactory.forTask(taskView), registry);
    }

    @Override
    public Long call() throws Exception {
        return super.call();
    }
}
