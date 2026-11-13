package org.icij.datashare.tasks;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static java.util.Optional.ofNullable;
import org.icij.datashare.PropertiesProvider;
import static org.icij.datashare.PropertiesProvider.mapToRecord;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
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
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.extract.extractor.ExtractorOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactStage implements StageProcessor<String, Void> {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Indexer indexer;
    private final Function<Map<String, Object>, SourceExtractor> extractorFactory;

    public ArtifactStage(Indexer indexer) {
        this(indexer, options -> new SourceExtractor(new PropertiesProvider(options)));
    }

    /** For tests */
    ArtifactStage(Indexer indexer, Function<Map<String, Object>, SourceExtractor> extractorFactory) {
        this.indexer = indexer;
        this.extractorFactory = extractorFactory;
    }

    @Override
    public StageResult process(List<String> docRefs, StageContext<Void> ctx) {
        // everything that depends on the options only is built once per run, not once per queue entry
        RunSetup setup = ctx.resource(RunSetup.class, () -> setup(ctx));
        ArtifactOptions artifactOptions = setup.options();
        ArtifactProducer producer = setup.producer();

        long nbSkipped = 0;
        long nbFailed = 0;
        long nbDocs = 0;
        for (String docRef : docRefs) {
            try {
                Document doc = indexer.get(artifactOptions.projectName(), DocReference.parse(docRef), SOURCE_EXCLUDES);
                if (doc != null) {
                    Path docArtifactDir = ArtifactPath.dir(setup.projectRoot(), doc.getId());
                    if (producer.run(setup.selected(),
                                     new ArtifactContext(setup.project(), doc, docArtifactDir, setup.extractor()),
                                     artifactOptions.force())) {
                        nbDocs++;
                    } else {
                        nbFailed++;
                    }
                } else {
                    nbSkipped++;
                }
            } catch (Error | ArtifactConfigurationException e) {
                // cannot continue (ex: MemoryError or the like)
                throw e;
            } catch (Throwable e) {
                if (producer.isCancellation(e)) {
                    Thread.currentThread().interrupt();
                }
                logger.error("error in ArtifactTask loop", e);
                nbFailed++;
            }
        }
        return new StageResult(nbDocs, 0, nbSkipped, nbFailed);
    }

    private record RunSetup(ArtifactOptions options, Project project, Path projectRoot, List<Artifact> selected, ArtifactProducer producer, SourceExtractor extractor) {}

    private RunSetup setup(StageContext<Void> ctx) {
        ArtifactOptions artifactOptions = mapToRecord(ArtifactOptions.class, ctx.options());
        ofNullable(artifactOptions.artifactDir()).orElseThrow(() -> new IllegalArgumentException(
                String.format("cannot process artifact task with empty %s", ARTIFACT_DIR_OPT)));
        ArtifactRegistry registry = ArtifactRegistry.withDefaults(mapToRecord(ExtractorOptions.class, ctx.options()));
        List<Artifact> selected = artifactOptions.artifacts().isEmpty() ? registry.select((String) null) :
                                  registry.select(artifactOptions.artifacts());
        // isCancelled reads the calling thread, so the one producer shared by the workers stays correct
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), ctx::isCancelled,
                                                         ctx.options().get("taskId").toString());
        return new RunSetup(artifactOptions, Project.project(artifactOptions.projectName()),
                            ArtifactPath.projectRoot(artifactOptions.artifactDir(), artifactOptions.projectName()),
                            selected, producer, extractorFactory.apply(ctx.options()));
    }

    @Override
    public Stage stage() {
        return Stage.ARTIFACT;
    }

    @Override
    public Class<String> itemType() {
        return String.class;
    }
}