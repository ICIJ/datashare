package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.tasks.GroupHelper.JAVA_GROUP;

@TaskGroup(JAVA_GROUP)
public class ArtifactTask extends PipelineTask<String> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Project project;
    private final Path artifactDir;

    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer, PropertiesProvider propertiesProvider, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, propertiesProvider, String.class);
        this.indexer = indexer;
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        artifactDir = Path.of(propertiesProvider.get(ARTIFACT_DIR_OPT).orElseThrow(() -> new IllegalArgumentException(String.format("cannot create artifact task with empty %s", ARTIFACT_DIR_OPT))));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("creating artifact cache in {} for project {} from queue {}", artifactDir, project, inputQueue.getName());
        SourceExtractor extractor = new SourceExtractor(propertiesProvider);
        List<String> sourceExcludes = List.of("content", "content_translated");
        String docId;
        long nbDocs = 0;
        while (!(STRING_POISON.equals(docId = inputQueue.poll(60, TimeUnit.SECONDS)))) {
            try {
                if (docId != null) {
                    Document doc = indexer.get(project.name, docId, sourceExcludes);
                    extractor.extractEmbeddedSources(project, doc);
                    nbDocs++;
                }
            } catch (Throwable e) {
                logger.error("error in ArtifactTask loop", e);
            }
        }
        logger.info("exiting ArtifactTask loop after {} document(s).", nbDocs);
        return nbDocs;
    }
}
