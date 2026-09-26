package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.model.EntitiesIndexRebuilder;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.Rows;
import org.icij.datashare.tabular.StatementBuilder;
import org.icij.datashare.tabular.TabularRowReader;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.datashare.utils.DocumentVerifier;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

@TemporalSingleActivityWorkflow(name = "structured-entity-extraction", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class StructuredEntityExtractionTask extends DefaultTask<StructuredEntityExtractionResult>
        implements UserTask, CancellableTask {
    public static final String MAPPING_ID_OPT = "mappingId";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final StatementRepository statements;
    private final ExtractionMappingRepository mappings;
    private final TabularRowReader reader;
    private final DocumentVerifier verifier;
    private final Task<StructuredEntityExtractionResult> taskView;
    private final Function<Double, Void> updateCallback;
    private final String projectId;
    private final String mappingId;
    private volatile Thread taskThread;
    private volatile boolean requeue;
    private volatile boolean cancelAsked;

    @Inject
    public StructuredEntityExtractionTask(Indexer indexer, StatementRepository statements,
                                          ExtractionMappingRepository mappings, PropertiesProvider propertiesProvider,
                                          @Assisted Task<StructuredEntityExtractionResult> taskView,
                                          @Assisted Function<Double, Void> updateCallback) {
        this.indexer = indexer;
        this.statements = statements;
        this.mappings = mappings;
        this.reader = new TabularRowReader(new SourceExtractor(propertiesProvider));
        this.verifier = new DocumentVerifier(indexer, propertiesProvider);
        this.taskView = taskView;
        this.updateCallback = updateCallback;
        PropertiesProvider args = new PropertiesProvider(taskView.args);
        this.projectId = args.get(DEFAULT_PROJECT_OPT).filter(id -> !id.isBlank()).orElse(null);
        this.mappingId = args.get(MAPPING_ID_OPT).filter(id -> !id.isBlank()).orElse(null);
    }

    @Override
    public StructuredEntityExtractionResult call() throws Exception {
        taskThread = Thread.currentThread();
        throwIfCancelled();
        require(projectId != null, "no '" + DEFAULT_PROJECT_OPT + "' in the task arguments");
        require(mappingId != null, "no '" + MAPPING_ID_OPT + "' in the task arguments");
        // Ahead of the write rather than at rebuild time: EntitiesIndexRebuilder refuses a bad name
        // because it reaches a _delete_by_query path, but by then the statements are committed.
        require(Project.NAME_PATTERN.matcher(projectId).matches(), "bad format for project id: '" + projectId + "'");
        User user = taskView.getUser();
        // A CLI run carries nullUser(), which is granted nothing; the HTTP triggers carry a real one.
        require(user.isNull() || user.isGranted(projectId), "user '" + user.id + "' is not granted " + projectId);
        ExtractionMapping mapping = mappings.get(projectId, mappingId).orElseThrow(
                () -> new IllegalArgumentException("no mapping '" + mappingId + "' in " + projectId));
        // Ahead of the read rather than in the StatementBuilder: a mapping stored before the
        // ontology moved is valid on the way in and stale on the way out, and the eager readers
        // parse the whole document before the builder is ever constructed.
        mapping.requireValid();
        Project project = Project.project(projectId);
        Document document = indexer.get(projectId, mapping.documentId(),
                                        mapping.rootId() == null ? mapping.documentId() : mapping.rootId(),
                                        TabularRowReader.CONTENT_FIELDS);
        require(document != null, "no such document in " + projectId + ": " + mapping.documentId());
        require(verifier.isRootDocumentSizeAllowed(document, project),
                "the file or its parent is too large: " + mapping.documentId());

        AtomicLong read = new AtomicLong();
        StatementRepository.Replaced replaced;
        StatementBuilder builder;
        try (Rows rows = reader.rows(project, document, mapping.options())) {
            builder = new StatementBuilder(mapping, rows.sheet());
            // The builder's sheet, not the reader's: the builder cleans it and every statement's
            // provenance carries the cleaned one, so a retraction keyed on the raw name would name a
            // sheet nothing was written under.
            replaced = statements.replace(projectId, taskView.getId(), mapping.documentId(), builder.sheet(),
                                          rows.rows().flatMap(row -> {
                                              throwIfCancelled();
                                              read.incrementAndGet();
                                              return builder.statements(row).stream();
                                          }));
        }
        updateCallback.apply(0.5);
        if (replaced.retracted() > replaced.written()) {
            logger.warn("mapping '{}' retracted {} statements and wrote {} for document {}: statements stored for " +
                        "this document and sheet were removed and not put back, which is what happens when another " +
                        "mapping targets the same sheet", mappingId, replaced.retracted(), replaced.written(),
                        mapping.documentId());
        }
        // Checked again here because the row lambda is the only other cancellation point, and a
        // source with no data row never runs it: without this a cancelled run would drop and refill
        // the project's whole entities index and then report itself done.
        throwIfCancelled();
        int indexed = new EntitiesIndexRebuilder(indexer, statements).rebuild(projectId);
        updateCallback.apply(1.0);
        StructuredEntityExtractionResult result =
                new StructuredEntityExtractionResult(read.get(), replaced.retracted(), replaced.written(), indexed,
                                                     builder.skipped());
        logger.info("mapping '{}' read {} rows, retracted {}, wrote {}, left {} entities in the project index, " +
                    "skipped {}", mappingId, result.rows(), result.retracted(), result.written(), result.indexed(),
                    result.skipped());
        return result;
    }

    @Override
    public void cancel(boolean requeue) {
        this.requeue = requeue;
        this.cancelAsked = true;
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return taskView.getUser();
    }

    // Thread.interrupted() tests and clears: TaskWorkerLoop never clears the flag, so leaving it set
    // would start the next task on this thread already cancelled. The latch covers the window before
    // call() published taskThread, where the worker loop's interrupt has nowhere to land.
    private void throwIfCancelled() {
        boolean interrupted = Thread.interrupted();
        if (interrupted || cancelAsked) {
            throw new CancelException(requeue);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
