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
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.text.structure.StructureMarkdownExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
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
        StructureMarkdownExtractor structureExtractor = new StructureMarkdownExtractor();
        Path projectArtifactDir = artifactDir.resolve(project.name);
        List<String> sourceExcludes = List.of("content", "content_translated");
        String docId;
        long nbDocs = 0;
        while ((docId = inputQueue.poll(pollingInterval, TimeUnit.SECONDS)) != null) {
            try {
                Document doc = indexer.get(project.name, docId, sourceExcludes);
                extractor.extractEmbeddedSources(project, doc);
                writeStructure(extractor, structureExtractor, projectArtifactDir, doc);
                nbDocs++;
            } catch (Throwable e) {
                logger.error("error in ArtifactTask loop", e);
            }
        }
        logger.info("exiting ArtifactTask loop after processing {} document(s).", nbDocs);
        return nbDocs;
    }

    private void writeStructure(SourceExtractor sourceExtractor, StructureMarkdownExtractor structureExtractor,
                                Path projectArtifactDir, Document doc) {
        // Skip only when a finished set is cached AND it includes the XHTML artifact. Corpora cached by an
        // earlier (markdown-only) build carry the completion marker but no structure.xhtml; gating on both
        // back-fills them on the next run instead of leaving the XHTML endpoint to 404 forever. The marker
        // is written last, so its presence (with the file) guarantees the XHTML and the whole page set are
        // on disk.
        Path completeMarker = ArtifactPath.structureComplete(projectArtifactDir, doc.getId());
        Path xhtmlFile = ArtifactPath.structureXhtml(projectArtifactDir, doc.getId());
        if (completeMarker.toFile().exists() && xhtmlFile.toFile().exists()) {
            return;
        }
        // A conversion or write failure must not abort the loop or the raw extraction above, so it is
        // caught and logged here rather than propagated.
        try (InputStream source = sourceExtractor.getSource(project, doc)) {
            StructureMarkdownExtractor.StructureResult result =
                    structureExtractor.extract(source, doc.getContentType());
            Files.createDirectories(ArtifactPath.structureDir(projectArtifactDir, doc.getId()));
            Files.writeString(xhtmlFile, result.xhtml(), StandardCharsets.UTF_8);
            writePages(projectArtifactDir, doc.getId(), result.pages());
            markStructureComplete(completeMarker);
        } catch (Exception e) {
            logger.error("could not write structure artifacts for document {}", doc.getId(), e);
        }
    }

    // Writes one Markdown file per page (page-0001.md, page-0002.md, ...) into the document's structure dir.
    // The caller (writeStructure) creates the directory before the XHTML write, so it already exists here.
    private void writePages(Path projectArtifactDir, String digest, List<String> pages) throws IOException {
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            int pageNumber = pageIndex + 1;
            Files.writeString(ArtifactPath.structurePage(projectArtifactDir, digest, pageNumber),
                    pages.get(pageIndex), StandardCharsets.UTF_8);
        }
    }

    // Marks the page set complete. Written last so a crash mid-write leaves no marker and the next run
    // regenerates the full set instead of skipping a truncated one.
    private void markStructureComplete(Path completeMarker) throws IOException {
        Files.writeString(completeMarker, "", StandardCharsets.UTF_8);
    }
}
