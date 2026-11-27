package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.apache.tika.exception.TikaException;
import org.icij.datashare.Entity;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.parser.QRCodeParserDecorator;
import org.icij.extract.qrcode.QRCodeDecoder;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;

/**
 * Decodes the QR codes of image documents already in a project's index and appends one
 * {@code QR code: <value>} line per value to their content, for projects indexed without --qrCodes.
 */
@TemporalSingleActivityWorkflow(name = "qr-codes", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class QRCodeTask extends DefaultTask<Long> implements UserTask, CancellableTask {
    private static final List<String> SOURCE_EXCLUDES = List.of("content_translated");
    private static final String QR_KEY = new FieldNames().forMetadata(QRCodeParserDecorator.QR_CODES.getName());
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final User user;
    private final PropertiesProvider propertiesProvider;
    private final DocumentQueue<String> inputQueue;
    private final Indexer indexer;
    private final SourceExtractor sourceExtractor;
    private final Function<Double, Void> updateCallback;
    private final String project;
    private final Project projectRef;
    private final String scrollDuration;
    private final int scrollSize;
    private volatile Thread taskThread;

    record Counters(AtomicLong updated, AtomicLong noCode, AtomicLong alreadyDecoded, AtomicLong failed) {
        Counters() {
            this(new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong());
        }
    }

    @Inject
    public QRCodeTask(DocumentCollectionFactory<String> factory, Indexer indexer, @Assisted Task<Long> taskView,
                      @Assisted Function<Double, Void> updateCallback) {
        this(factory, indexer, new SourceExtractor(new PropertiesProvider(taskView.args)), taskView, updateCallback);
    }

    QRCodeTask(DocumentCollectionFactory<String> factory, Indexer indexer, SourceExtractor sourceExtractor,
               Task<Long> taskView, Function<Double, Void> updateCallback) {
        this.user = taskView.getUser();
        this.propertiesProvider = new PropertiesProvider(taskView.args);
        String queueName = new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.QRCODES);
        this.inputQueue = factory.createQueue(queueName, String.class);
        this.indexer = indexer;
        this.sourceExtractor = sourceExtractor;
        this.updateCallback = updateCallback;
        this.project = (String) taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.projectRef = Project.project(project);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        String scrollSizeValue = propertiesProvider.get(SCROLL_SIZE_OPT).orElse(String.valueOf(DEFAULT_SCROLL_SIZE));
        this.scrollSize = parseInt(scrollSizeValue);
    }

    @Override
    public Long call() throws Exception {
        taskThread = Thread.currentThread();
        if (!indexer.exists(project)) {
            throw new IllegalArgumentException("project %s has no index".formatted(project));
        }
        indexer.createIndex(project);
        long total = spoolImageReferences();
        logger.info("decoding QR codes in {} image document(s) of project {}", total, project);
        Counters counters = new Counters();
        List<Document> pendingGroup = new ArrayList<>();
        long seen = 0;
        String entry = inputQueue.poll();
        while (entry != null) {
            processReference(DocReference.parse(entry), pendingGroup, counters);
            seen++;
            if (seen % scrollSize == 0 || seen == total) {
                updateCallback.apply((double) seen / total);
            }
            entry = inputQueue.poll();
        }
        flush(pendingGroup, counters);
        logSummary(counters);
        return counters.updated().get() + counters.noCode().get();
    }

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return user;
    }

    // References first, documents later: a root walk can outlast any scroll keep-alive, so no scroll
    // context stays open while images are decoded. The queue keeps the root order of the scroll.
    private long spoolImageReferences() throws IOException {
        inputQueue.clear();
        Indexer.Searcher searcher =
                indexer.search(List.of(project), Document.class).withPrefixQuery("contentType", "image/");
        searcher.withDocValues("rootDocument").limit(scrollSize).sort("rootDocument", Indexer.Searcher.SortOrder.ASC);
        List<? extends Entity> page = searcher.scroll(scrollDuration).toList();
        while (!page.isEmpty()) {
            for (Entity entity : page) {
                DocReference reference = DocReference.fromDocument((Document) entity);
                inputQueue.add(reference.toQueueEntry());
            }
            page = searcher.scroll(scrollDuration).toList();
        }
        searcher.clearScroll();
        return searcher.totalHits();
    }

    private void processReference(DocReference reference, List<Document> pendingGroup, Counters counters) {
        Document document;
        try {
            document = indexer.get(project, reference, SOURCE_EXCLUDES);
        } catch (RuntimeException e) {
            logger.error("cannot read document {} in project {}", reference.id(), project, e);
            counters.failed().incrementAndGet();
            return;
        }
        if (document == null) {
            counters.failed().incrementAndGet();
            return;
        }
        process(document, pendingGroup, counters);
    }

    static String appendLines(String content, List<String> values) {
        StringBuilder builder = new StringBuilder(content);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            builder.append('\n');
        }
        for (String value : values) {
            builder.append("QR code: ").append(value).append('\n');
        }
        return builder.toString();
    }

    private void process(Document document, List<Document> pendingGroup, Counters counters) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata != null && metadata.containsKey(QR_KEY)) {
            counters.alreadyDecoded().incrementAndGet();
            return;
        }
        if (!document.isRootDocument()) {
            processEmbedded(document, pendingGroup, counters);
            return;
        }
        Path path = document.getPath();
        // the decoder returns no value for a file it cannot read, which would pass for an image without a code
        if (!Files.isReadable(path)) {
            logger.error("cannot read {} for document {} in project {}", path, document.getId(), project);
            counters.failed().incrementAndGet();
            return;
        }
        save(document, QRCodeDecoder.decode(path), counters);
    }

    private void processEmbedded(Document document, List<Document> pendingGroup, Counters counters) {
        Path cached = sourceExtractor.cachedEmbeddedSourcePath(projectRef, document);
        if (cached != null) {
            save(document, QRCodeDecoder.decode(cached), counters);
            return;
        }
        // the scroll is sorted by root, so a new root means the pending one has all its images
        String pendingRoot = pendingGroup.isEmpty() ? null : pendingGroup.get(0).getRootDocument();
        if (pendingRoot != null && !pendingRoot.equals(document.getRootDocument())) {
            flush(pendingGroup, counters);
        }
        pendingGroup.add(document);
    }

    private void flush(List<Document> group, Counters counters) {
        if (group.isEmpty()) {
            return;
        }
        Map<String, Document> wanted = new HashMap<>();
        group.forEach(document -> wanted.put(document.getId(), document));
        Document first = group.get(0);
        group.clear();
        try {
            // the spooled bytes are deleted once the visitor returns, so each image is decoded and saved inside it
            Set<String> ids = Set.copyOf(wanted.keySet());
            sourceExtractor.forEachEmbed(projectRef, first, ids, (id, bytes) -> {
                Document document = wanted.remove(id);
                List<String> values = QRCodeDecoder.decode(bytes);
                save(document, values, counters);
            });
        } catch (TikaException | IOException | SAXException | RuntimeException e) {
            logger.error("cannot parse root {} of {} image(s) in project {}", first.getRootDocument(), wanted.size(),
                         project, e);
            counters.failed().addAndGet(wanted.size());
            return;
        }
        if (!wanted.isEmpty()) {
            logger.error("{} image(s) not found when parsing root {} in project {}: {}", wanted.size(),
                         first.getRootDocument(), project, wanted.keySet());
            counters.failed().addAndGet(wanted.size());
        }
    }

    private void save(Document document, List<String> values, Counters counters) {
        if (values.isEmpty()) {
            counters.noCode().incrementAndGet();
            return;
        }
        String content = appendLines(document.getContent(), values);
        Map<String, Object> fields =
                Map.of("content", content, "contentTextLength", content.length(), "metadata", Map.of(QR_KEY, values));
        try {
            indexer.update(project, document.getId(), fields, document.getRootDocument());
            counters.updated().incrementAndGet();
        } catch (IOException | RuntimeException e) {
            logger.error("cannot save QR codes of document {} in project {}", document.getId(), project, e);
            counters.failed().incrementAndGet();
        }
    }

    private void logSummary(Counters counters) {
        logger.info("QR code decoding done: {} updated, {} without code, {} already decoded, {} failed",
                    counters.updated().get(), counters.noCode().get(), counters.alreadyDecoded().get(),
                    counters.failed().get());
        if (counters.failed().get() > 0) {
            logger.error("{} document(s) failed QR code decoding in project {}, re-run the QRCODES stage for them",
                         counters.failed().get(), project);
        }
    }
}
