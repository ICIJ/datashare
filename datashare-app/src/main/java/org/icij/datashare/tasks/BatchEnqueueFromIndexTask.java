package org.icij.datashare.tasks;

import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static org.icij.datashare.asynctasks.Task.GROUP_KEY;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_NLP_BATCH_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_NLP_MAX_TEXT_LENGTH;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_BATCH_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_MAX_TEXT_LENGTH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;
import static org.icij.datashare.nlp.NlpHelper.pipelineExtras;
import static org.icij.datashare.tasks.GroupHelper.JAVA_GROUP;
import static org.icij.datashare.tasks.GroupHelper.nlpGroup;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskManager;
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

@TaskGroup(JAVA_GROUP)
public class BatchEnqueueFromIndexTask extends DefaultTask<Long> implements UserTask, CancellableTask {
    Logger logger = LoggerFactory.getLogger(getClass());

    private final User user;
    private volatile Thread taskThread;
    private final TaskManager taskManager;
    private final String searchQuery;
    private final Map<String, Object> batchTaskArgs;
    private final Pipeline.Type nlpPipeline;
    private final int batchSize;
    private final int maxTextLength;
    private final String projectName;
    private final Indexer indexer;
    private final String scrollDuration;
    private final int scrollSize;

    public record BatchDocument(String id, String rootDocument, String project, Language language) {
        public static BatchDocument fromDocument(Document document) {
            return new BatchDocument(document.getId(), document.getRootDocument(), document.getProjectId(), document.getLanguage());
        }
    }

    @Inject
    public BatchEnqueueFromIndexTask(
        final TaskManager taskManager, final Indexer indexer, @Assisted Task<Long> taskView,
        @Assisted final Function<Double, Void> ignored
    ) {
        this.user = taskView.getUser();
        this.taskManager = taskManager;
        this.indexer = indexer;
        this.nlpPipeline = Pipeline.Type.parse((String) taskView.args.getOrDefault(NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name()));
        this.batchTaskArgs = batchTaskArgs();
        this.batchSize = (int) taskView.args.getOrDefault(NLP_BATCH_SIZE_OPT, DEFAULT_NLP_BATCH_SIZE);
        this.maxTextLength = (int) taskView.args.getOrDefault(NLP_MAX_TEXT_LENGTH_OPT, DEFAULT_NLP_MAX_TEXT_LENGTH);
        this.projectName = (String) taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.scrollDuration = (String) taskView.args.getOrDefault(SCROLL_DURATION_OPT, DEFAULT_SCROLL_DURATION);
        this.scrollSize = (int) taskView.args.getOrDefault(SCROLL_SIZE_OPT, DEFAULT_SCROLL_SIZE);
        this.searchQuery = (String) taskView.args.get(SEARCH_QUERY_OPT);
    }

    @Override
    public Long call() throws Exception {
        taskThread = Thread.currentThread();
        Indexer.Searcher searcher;
        if (searchQuery == null) {
            searcher = indexer.search(singletonList(projectName), Document.class).without(nlpPipeline);
        } else {
            searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery));
        }
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
        Language currentLanguage = null;
        do {
            // For each scrolled page, we fill the batch...
            // TODO: remove me
            logger.info("new page grouped by language {}", scrolledDocsByLanguage);
            currentLanguage = this.enqueueScrollBatches(scrolledDocsByLanguage, currentLanguage, batch);
            // and keep scrolling...
            scrolledDocsByLanguage = searcher
                .scroll(scrollDuration)
                .collect(groupingBy(d -> ((Document) d).getLanguage()));
            // until we reach a page smaller than the scroll size aka the last page of the scrol
        } while (scrolledDocsByLanguage.values().stream().map(List::size).mapToInt(Integer::intValue).sum() >= scrollSize);
        // Let's fill the batches for that last page
        this.enqueueScrollBatches(scrolledDocsByLanguage, currentLanguage, batch);
        // ... and enqueue that last batch if not done yet
        if (!batch.isEmpty()) {
            this.enqueueBatch(batch);
        }
        logger.info("queued batches for {} docs", totalHits);
        searcher.clearScroll();
        return totalHits;
    }

    private Language enqueueScrollBatches(
        Map<Language, ? extends List<? extends Entity>> docsByLanguage,
        Language currentLanguage,
        ArrayList<Document> batch
    ) {
        // Make sure we consume the languages in order
        Iterator<? extends Map.Entry<Language, ? extends List<? extends Entity>>> docsIt = docsByLanguage.entrySet()
            .stream().sorted(Comparator.comparing(e -> e.getKey().name())).iterator();
        while (docsIt.hasNext()) {
            Map.Entry<Language, ? extends List<? extends Entity>> entry = docsIt.next();
            Language language = entry.getKey();
            // If we switch language, we need to queue the batch
            if (!language.equals(currentLanguage)) {
                if (!batch.isEmpty()) {
                    this.enqueueBatch(batch);
                }
                // TODO: remove me
                logger.info("switching to {}", language);
                currentLanguage = language;
            }
            // and then we fill the current batch which can already be partially filled
            List<Document> languageDocs = (List<Document>) entry.getValue();
            int start = 0;
            int end = 0;
            while (end < languageDocs.size()) {
                end = start + Integer.min(batchSize - batch.size(), languageDocs.size() - start);
                batch.addAll(languageDocs.subList(start, end));
                if (batch.size() >= batchSize) {
                    this.enqueueBatch(batch);
                }
                start = end;
            }
        }
        return currentLanguage;
    }

    void enqueueBatch(List<Document> batch) {
        HashMap<String, Object> args = new HashMap<>(this.batchTaskArgs);
        args.put("docs", batch.stream().map(BatchDocument::fromDocument).toList());
        try {
            // TODO: here we bind the task name to the Java class name which is not ideal since it leaks Java inners
            //  bolts to Python, it could be nice to decouple task names from class names since they can change and
            //  are bound to languages
            logger.info("{} - {}", DatashareTime.getNow().getTime(), ((List<BatchDocument>)args.get("docs")).get(0).language());
            this.taskManager.startTask(BatchNlpTask.class, this.user, args);
        } catch (IOException e) {
            throw new RuntimeException("failed to queue task " + args, e);
        }
        batch.clear();
    }

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return user;
    }

    private Map<String, Object> batchTaskArgs() {
        return Map.of(
            "pipeline", this.nlpPipeline.name(),
            "maxLength", this.maxTextLength,
            GROUP_KEY, nlpGroup(this.nlpPipeline),
            "extras", pipelineExtras(this.nlpPipeline)
        );
    }

}