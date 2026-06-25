package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.ArtifactContext;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.ManifestStore;
import org.icij.datashare.text.artifact.RawArtifact;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_POLLING_INTERVAL_SEC;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;

@TemporalSingleActivityWorkflow(name = "artifact", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class ArtifactTask extends PipelineTask<String> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Project project;
    private final Path artifactDir;
    private final int pollingInterval;

    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer, PropertiesProvider propertiesProvider, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, propertiesProvider, String.class);
        this.indexer = indexer;
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        pollingInterval = Integer.parseInt(propertiesProvider.get(POLLING_INTERVAL_SECONDS_OPT).orElse(DEFAULT_POLLING_INTERVAL_SEC));
        artifactDir = Path.of(propertiesProvider.get(ARTIFACT_DIR_OPT).orElseThrow(() -> new IllegalArgumentException(String.format("cannot create artifact task with empty %s", ARTIFACT_DIR_OPT))));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("creating artifact cache in {} for project {} from queue {} with polling interval {}s", artifactDir, project, inputQueue.getName(), pollingInterval);
        SourceExtractor extractor = new SourceExtractor(propertiesProvider);
        // Decide once which artifact types to produce for this run: an absent --artifacts
        // flag means all registered types (raw is the only one wired in this foundation).
        ArtifactRegistry registry = new ArtifactRegistry(List.of(new RawArtifact()), new ManifestStore());
        Set<String> selected = registry.select(propertiesProvider.get(ARTIFACTS_OPT).orElse(null));
        Path projectRoot = artifactDir.resolve(project.name);
        List<String> sourceExcludes = List.of("content", "content_translated");
        String docId;
        long nbDocs = 0;
        while ((docId = inputQueue.poll(pollingInterval, TimeUnit.SECONDS)) != null) {
            try {
                // Each polled node is produced into its own content-addressed directory.
                Document doc = indexer.get(project.name, docId, sourceExcludes);
                Path nodeDir = ArtifactPath.dir(projectRoot, doc.getId());
                registry.run(selected, new ArtifactContext(project, doc, nodeDir, extractor));
                nbDocs++;
            } catch (Throwable e) {
                logger.error("error in ArtifactTask loop", e);
            }
        }
        logger.info("exiting ArtifactTask loop after processing {} document(s).", nbDocs);
        return nbDocs;
    }
}
