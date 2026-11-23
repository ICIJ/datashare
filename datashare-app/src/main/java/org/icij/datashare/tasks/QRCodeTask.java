package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.parser.QRCodeParserDecorator;
import org.icij.extract.qrcode.QRCodeDecoder;
import org.icij.spewer.FieldNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static java.lang.Integer.parseInt;
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
public class QRCodeTask extends PipelineTask<String> {
    private static final String QR_KEY = new FieldNames().forMetadata(QRCodeParserDecorator.QR_CODES.getName());
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Function<Double, Void> updateCallback;
    private final String project;
    private final String scrollDuration;
    private final int scrollSize;

    record Counters(AtomicLong updated, AtomicLong noCode, AtomicLong alreadyDecoded, AtomicLong failed) {
        Counters() {
            this(new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong());
        }
    }

    @Inject
    public QRCodeTask(DocumentCollectionFactory<String> factory, Indexer indexer, @Assisted Task<Long> taskView,
                      @Assisted Function<Double, Void> updateCallback) {
        super(Stage.QRCODES, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class);
        this.indexer = indexer;
        this.updateCallback = updateCallback;
        this.project = (String) taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        String scrollSizeValue = propertiesProvider.get(SCROLL_SIZE_OPT).orElse(String.valueOf(DEFAULT_SCROLL_SIZE));
        this.scrollSize = parseInt(scrollSizeValue);
    }

    @Override
    public Long call() throws Exception {
        super.call();
        if (!indexer.exists(project)) {
            throw new IllegalArgumentException("project %s has no index".formatted(project));
        }
        indexer.createIndex(project);
        Indexer.Searcher searcher =
                indexer.search(List.of(project), Document.class).withPrefixQuery("contentType", "image/");
        searcher.withoutSource("content_translated").limit(scrollSize)
                .sort("rootDocument", Indexer.Searcher.SortOrder.ASC);
        Counters counters = new Counters();
        List<? extends Entity> page = searcher.scroll(scrollDuration).toList();
        long totalHits = searcher.totalHits();
        logger.info("decoding QR codes in {} image document(s) of project {}", totalHits, project);
        long seen = 0;
        while (!page.isEmpty()) {
            for (Entity entity : page) {
                process((Document) entity, counters);
            }
            seen += page.size();
            updateCallback.apply((double) seen / totalHits);
            page = searcher.scroll(scrollDuration).toList();
        }
        searcher.clearScroll();
        logSummary(counters);
        return counters.updated().get() + counters.noCode().get();
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

    private void process(Document document, Counters counters) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata != null && metadata.containsKey(QR_KEY)) {
            counters.alreadyDecoded().incrementAndGet();
            return;
        }
        if (!document.isRootDocument()) {
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
        } catch (IOException e) {
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
