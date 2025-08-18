package org.icij.datashare.tasks;

import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_NLP_BATCH_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_BATCH_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractCreateNlpBatchesFromIndexTask<R> extends DefaultTask<List<R>>
    implements UserTask, CancellableTask {

    protected final Task<?> task;
    Logger logger = LoggerFactory.getLogger(getClass());
    private final ThrowingFunction<List<BatchDocument>, R> createNlpBatchFn;

    private final User user;
    private volatile Thread taskThread;
    private final String searchQuery;
    private final Pipeline.Type nlpPipeline;
    private final int batchSize;
    private final String projectName;
    private final Indexer indexer;
    private final String scrollDuration;
    private final int scrollSize;
    private Language currentLanguage = null;

    // TODO: avoid duplicating project for each record, it could be passed as a task arg
    public record BatchDocument(String id, String rootDocument, Language language, String project) {
        public static BatchDocument fromDocument(Document document) {
            return new BatchDocument(
                document.getId(),
                document.getRootDocument(),
                document.getLanguage(),
                document.getProjectId()
            );
        }
    }

    public AbstractCreateNlpBatchesFromIndexTask(final Indexer indexer, Task<ArrayList<R>> task) {
        this.user = task.getUser();
        this.indexer = indexer;
        this.task = task;
        this.nlpPipeline = Pipeline.Type.parse(
            (String) task.args.getOrDefault(NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name()));
        this.batchSize = Integer.parseInt(
            (String) task.args.getOrDefault(NLP_BATCH_SIZE_OPT, String.valueOf(DEFAULT_NLP_BATCH_SIZE)));
        this.projectName = (String) task.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.scrollDuration = (String) task.args.getOrDefault(SCROLL_DURATION_OPT, DEFAULT_SCROLL_DURATION);
        this.scrollSize = Integer.parseInt((String) task.args.getOrDefault(
            SCROLL_SIZE_OPT, String.valueOf(DEFAULT_SCROLL_SIZE)));
        this.searchQuery = (String) task.args.get(SEARCH_QUERY_OPT);
        this.createNlpBatchFn = getCreateNlpBatchFunction();
    }

    protected abstract ThrowingFunction<List<BatchDocument>, R> getCreateNlpBatchFunction();

    @Override
    public List<R> call() throws Exception {
        ArrayList<R> batches = new ArrayList<>();
        taskThread = Thread.currentThread();
        Indexer.Searcher searcher;
        if (searchQuery == null) {
            searcher = indexer.search(singletonList(projectName), Document.class).without(nlpPipeline);
        } else {
            searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery));
        }
        // TODO: do we really need the content ?
        searcher = searcher.limit(scrollSize)
            .withoutSource("language", "rootDocument")
            .withoutSource("content", "contentTranslated")
            .sort("language", Indexer.Searcher.SortOrder.ASC);
        Map<Language, ? extends List<? extends Entity>> scrolledDocsByLanguage = searcher
            .scroll(scrollDuration)
            .collect(groupingBy(d -> ((Document) d).getLanguage()));
        ArrayList<Document> batch = new ArrayList<>(this.batchSize);
        long totalHits = searcher.totalHits();
        logger.info(
            "pushing batches of {} docs ids for index {}, pipeline {} with {} scroll and size of {}",
            totalHits, projectName, nlpPipeline, scrollDuration, scrollSize
        );
        do {
            // For each scrolled page, we fill the batch...
            batches.addAll(this.consumeScrollBatches(scrolledDocsByLanguage, batch));
            // and keep scrolling...
            scrolledDocsByLanguage = searcher
                .scroll(scrollDuration)
                .collect(groupingBy(d -> ((Document) d).getLanguage()));
            // until we reach a page smaller than the scroll size aka the last page of the scroll
        } while (scrolledDocsByLanguage.values().stream().map(List::size).mapToInt(Integer::intValue).sum()
            >= scrollSize);
        // Let's fill the batches for that last page
        batches.addAll(this.consumeScrollBatches(scrolledDocsByLanguage, batch));
        // ... and enqueue that last batch if not done yet
        if (!batch.isEmpty()) {
            batches.add(this.consumeBatch(batch));
        }
        logger.info("queued batches for {} docs", totalHits);
        searcher.clearScroll();
        return batches;
    }

    private List<R> consumeScrollBatches(Map<Language, ? extends List<? extends Entity>> docsByLanguage,
                                         ArrayList<Document> docBatch)
        throws Exception {
        ArrayList<R> resultBatches = new ArrayList<>();
        // Make sure we consume the languages in order
        Iterator<? extends Map.Entry<Language, ? extends List<? extends Entity>>> docsIt = docsByLanguage.entrySet()
            .stream().sorted(Comparator.comparing(e -> e.getKey().name())).iterator();
        while (docsIt.hasNext()) {
            Map.Entry<Language, ? extends List<? extends Entity>> entry = docsIt.next();
            Language language = entry.getKey();
            // If we switch language, we need to queue the batch
            if (!language.equals(currentLanguage)) {
                if (!docBatch.isEmpty()) {
                    resultBatches.add(this.consumeBatch(docBatch));
                }
                currentLanguage = language;
            }
            // and then we fill the current batch which can already be partially filled
            List<Document> languageDocs = (List<Document>) entry.getValue();
            int start = 0;
            int end = 0;
            while (end < languageDocs.size()) {
                end = start + Integer.min(batchSize - docBatch.size(), languageDocs.size() - start);
                docBatch.addAll(languageDocs.subList(start, end));
                if (docBatch.size() >= batchSize) {
                    resultBatches.add(this.consumeBatch(docBatch));
                }
                start = end;
            }
        }
        return resultBatches;
    }

    private R consumeBatch(List<Document> docBatch) throws Exception {
        R resultBatch = createNlpBatchFn.applyThrows(docBatch.stream().map(BatchDocument::fromDocument).toList());
        logger.info("{} - {}", DatashareTime.getNow().getTime(), docBatch.get(0).getLanguage());
        docBatch.clear();
        return resultBatch;
    }

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return user;
    }
}
