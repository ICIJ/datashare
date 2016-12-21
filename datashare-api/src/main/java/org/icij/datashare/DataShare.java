package org.icij.datashare;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.icij.datashare.concurrent.queue.QueueForwardingTask;
import org.icij.datashare.concurrent.task.*;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.text.NamedEntity.Category.LOCATION;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.extraction.FileParsingTask;
import org.icij.datashare.text.extraction.FileSystemScanningTask;
import static org.icij.datashare.text.extraction.FileParser.Type.TIKA;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.IndexingTask;
import static org.icij.datashare.text.indexing.Indexer.NodeType.LOCAL;
import static org.icij.datashare.text.indexing.Indexer.Type.ELASTICSEARCH;
import org.icij.datashare.text.nlp.NlpPipeline;
import org.icij.datashare.text.nlp.NerTask;
import org.icij.datashare.text.nlp.NlpStage;
import static org.icij.datashare.text.nlp.NlpStage.NER;


/**
 * DataShare Facade class
 *
 * Created by julien on 11/21/16.
 */
public final class DataShare {

    private static final Logger LOGGER = LogManager.getLogger(DataShare.class);

    public static final FileParser.Type            DEFAULT_FILEPARSER_TYPE    = TIKA;
    public static final boolean                    DEFAULT_FILEPARSER_OCR     = false;

    public static final List<NlpPipeline.Type>     DEFAULT_NLP_PIPELINES      = asList(NlpPipeline.Type.values());
    public static final int                        DEFAULT_NLP_PARALLELISM    = 1;
    public static final List<NlpStage>             DEFAULT_NLP_STAGES         = singletonList(NER);
    public static final List<NamedEntity.Category> DEFAULT_NLP_ENTITIES       = asList(PERSON, ORGANIZATION, LOCATION);
    public static final boolean                    DEFAULT_NLP_CACHING        = true;

    public static final Indexer.Type               DEFAULT_INDEXER_TYPE       = ELASTICSEARCH;
    public static final Indexer.NodeType           DEFAULT_INDEXER_NODE_TYPE  = LOCAL;
    public static final List<String>               DEFAULT_INDEXER_NODE_HOSTS = emptyList();
    public static final List<Integer>              DEFAULT_INDEXER_NODE_PORTS = emptyList();
    public static final List<String>               DATASHARE_INDICES          = asList("datashare-local", "datashare-global");
    public static final String                     DEFAULT_INDEX              = DATASHARE_INDICES.get(0);

    private static ReentrantLock processLock = new ReentrantLock();


    public static boolean processDirectory(Path inputDir, Indexer indexer) {
        return processDirectory(
                inputDir,
                DEFAULT_FILEPARSER_TYPE,
                DEFAULT_FILEPARSER_OCR,
                DEFAULT_NLP_STAGES,
                DEFAULT_NLP_ENTITIES,
                DEFAULT_NLP_PIPELINES,
                DEFAULT_NLP_PARALLELISM,
                DEFAULT_NLP_CACHING,
                indexer,
                DEFAULT_INDEX
        );
    }

    public static boolean processDirectory(Path inputDir,
                                           List<NlpPipeline.Type> nlpPipelineTypes,
                                           int nlpPipelineParallelism,
                                           Indexer indexer) {
        return processDirectory(
                inputDir,
                DEFAULT_FILEPARSER_TYPE,
                DEFAULT_FILEPARSER_OCR,
                DEFAULT_NLP_STAGES,
                DEFAULT_NLP_ENTITIES,
                nlpPipelineTypes,
                nlpPipelineParallelism,
                DEFAULT_NLP_CACHING,
                indexer,
                DEFAULT_INDEX
        );
    }

    public static boolean processDirectory(Path inputDir,
                                           List<NlpStage> nlpStages,
                                           List<NamedEntity.Category> nlpTargetEntities,
                                           List<NlpPipeline.Type> nlpPipelineTypes,
                                           int nlpPipelineParallelism,
                                           Indexer indexer) {
        return processDirectory(
                inputDir,
                DEFAULT_FILEPARSER_TYPE,
                DEFAULT_FILEPARSER_OCR,
                nlpStages,
                nlpTargetEntities,
                nlpPipelineTypes,
                nlpPipelineParallelism,
                DEFAULT_NLP_CACHING,
                indexer,
                DEFAULT_INDEX
        );
    }

    public static boolean processDirectory(Path inputDir,
                                           List<NlpStage> nlpStages,
                                           List<NamedEntity.Category> nlpTargetEntities,
                                           List<NlpPipeline.Type> nlpPipelineTypes,
                                           int nlpPipelineParallelism,
                                           Indexer indexer,
                                           String index) {
        return processDirectory(
                inputDir,
                DEFAULT_FILEPARSER_TYPE,
                DEFAULT_FILEPARSER_OCR,
                nlpStages,
                nlpTargetEntities,
                nlpPipelineTypes,
                nlpPipelineParallelism,
                DEFAULT_NLP_CACHING,
                indexer,
                index
        );
    }

    public static boolean processDirectory(Path inputDir,
                                           boolean enableOcr,
                                           List<NlpStage> nlpStages,
                                           List<NamedEntity.Category> nlpTargetEntities,
                                           List<NlpPipeline.Type> nlpPipelineTypes,
                                           int nlpPipelineParallelism,
                                           Indexer indexer,
                                           String index) {
        return processDirectory(
                inputDir,
                DEFAULT_FILEPARSER_TYPE,
                enableOcr,
                nlpStages,
                nlpTargetEntities,
                nlpPipelineTypes,
                nlpPipelineParallelism,
                DEFAULT_NLP_CACHING,
                indexer,
                index
        );
    }


    /**
     *     /**
     * Extract {@link Document}s and {@link NamedEntity}s from files under {@code inputDir}
     * Store them into {@code index}
     *
     * Coordinating {@link Task}s with local in-memory {@link BlockingQueue}s,
     *  - Scan files from {@code inputDir}
     *  - Parse each file {@link Path} into a {@link Document}
     *  - Index each {@link Document} to {@code index} using {@code indexerType} {@link Indexer}
     *  - Extract all {@link NamedEntity}s from each {@link Document} using {@code nlpPipelineTypes} {@link NlpPipeline}s
     *  - Index each {@link NamedEntity} to {@code index} using {@code indexerType} {@link Indexer}
     *
     * @param inputDir                 the directory source files are scanned from
     * @param fileParserType           the {@link FileParser.Type} to instantiate
     * @param enableOcr                the flag for activating OCR at file parsing time
     * @param nlpStages                the targeted NLP processing stages
     * @param nlpTargetEntities        the targeted named entity categories
     * @param nlpPipelineTypes         the {@link NlpPipeline.Type} to instantiate
     * @param nlpPipelineParallelism   the number of threads per {@code nlpPipelineType}
     * @param nlpPipelineCaching       the flag for caching models while running {@code NlpPipeline}s
     * @param indexer                  the indexer instance
     * @param index                    the destination index
     * @return true if processings terminated successfully; false otherwise
     */
    public static boolean processDirectory(Path inputDir,
                                           FileParser.Type fileParserType,
                                           boolean enableOcr,
                                           List<NlpStage> nlpStages,
                                           List<NamedEntity.Category> nlpTargetEntities,
                                           List<NlpPipeline.Type> nlpPipelineTypes,
                                           int nlpPipelineParallelism,
                                           boolean nlpPipelineCaching,
                                           Indexer indexer,
                                           String index) {
        try {
            if (processLock.tryLock(1, TimeUnit.SECONDS)) {
                LOGGER.info(fileParserType);
                LOGGER.info(nlpPipelineTypes);
                LOGGER.info(nlpPipelineParallelism);
                LOGGER.info(nlpStages.toString());
                LOGGER.info(nlpTargetEntities.toString());

                awaitIndexIsUp(indexer, index);

                Properties fileParserProperties = FileParser.Property.build
                        .apply(enableOcr)
                        .apply(Language.UNKNOWN);

                Properties nlpPipelineProperties = NlpPipeline.Property.build
                        .apply(nlpStages)
                        .apply(nlpTargetEntities)
                        .apply(nlpPipelineCaching);

                Map<NlpPipeline.Type, BlockingQueue<Document>> documentsQueuesForNlpMap =
                        new HashMap<NlpPipeline.Type, BlockingQueue<Document>>() {{
                            nlpPipelineTypes.forEach( type -> put(type, new LinkedBlockingQueue<>()) );
                        }};

                BlockingQueue<Path>           pathsQueue               = new LinkedBlockingQueue<>();
                BlockingQueue<Document>       documentsQueue           = new LinkedBlockingQueue<>();
                BlockingQueue<NamedEntity>    namedEntitiesQueue       = new LinkedBlockingQueue<>();
                BlockingQueue<Task.Result>    indexingResultsQueue     = new LinkedBlockingQueue<>();
                BlockingQueue<Document>       documentsQueueForIndexer = new LinkedBlockingQueue<>();
                List<BlockingQueue<Document>> documentsQueuesForNlp    = documentsQueuesForNlpMap.entrySet().stream()
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toList());
                List<BlockingQueue<Document>>   documentsQueuesForwarded = new ArrayList<>();
                documentsQueuesForwarded.add(documentsQueueForIndexer);
                documentsQueuesForwarded.addAll(documentsQueuesForNlp);

                List<Task> tasks = new ArrayList<>();
                FileSystemScanningTask fileSystemScanningTask =
                        new FileSystemScanningTask(
                                inputDir,
                                pathsQueue
                        );
                tasks.add(fileSystemScanningTask);

                FileParsingTask fileParsingTask =
                        new FileParsingTask(
                                fileParserType,
                                fileParserProperties,
                                pathsQueue,
                                documentsQueue,
                                fileSystemScanningTask.noMoreOutput()
                        );
                tasks.add(fileParsingTask);

                QueueForwardingTask documentQueueForwardingTask =
                        new QueueForwardingTask<>(
                                documentsQueue,
                                documentsQueuesForwarded,
                                fileParsingTask.noMoreOutput()
                        );
                tasks.add(documentQueueForwardingTask);

                IndexingTask<Document> documentIndexingTask =
                        new IndexingTask<>(
                                indexer,
                                index,
                                documentsQueueForIndexer,
                                indexingResultsQueue,
                                fileParsingTask.noMoreOutput()
                        );
                tasks.add(documentIndexingTask);

                List<NerTask> nerTasks =
                        NerTask.createAll(
                                nlpPipelineTypes,
                                nlpPipelineParallelism,
                                nlpPipelineProperties,
                                documentsQueuesForNlpMap,
                                namedEntitiesQueue,
                                documentQueueForwardingTask.noMoreOutput()
                        );
                tasks.addAll(nerTasks);

                IndexingTask<NamedEntity> namedEntityIndexingTask =
                        new IndexingTask<>(
                                indexer,
                                index,
                                namedEntitiesQueue,
                                indexingResultsQueue,
                                nerTasks.stream().map(QueueInQueueOutTask::noMoreOutput).collect(Collectors.toList())
                        );
                tasks.add(namedEntityIndexingTask);

                TaskExecutor asyncTaskExecutor = new AsyncTaskExecutor(tasks);
                asyncTaskExecutor.start();
                asyncTaskExecutor.shutdown();
                asyncTaskExecutor.awaitTermination();
                LOGGER.info(" Number of Recognized Named Entities: " + namedEntitiesQueue.size());
                asyncTaskExecutor.stop();
                return true;

            } else {
                LOGGER.error("Already processing");
                return false;
            }
        } catch (InterruptedException e) {
            LOGGER.error("Processing lock interrupted", e);
            Thread.currentThread().interrupt();
            return false;
        } finally {
            processLock.unlock();
        }
    }


    public static Optional<Indexer> awaitIndexIsUp(Indexer indexer, String index) {
        if ( ! indexer.awaitIndexIsUp(index)) {
            LOGGER.error("Index " + index + " is down. Closing");
            indexer.close();
            return Optional.empty();
        }
        return Optional.of(indexer);
    }

    public static boolean isProcessing() { return processLock.isLocked(); }


//        System.out.println("________________________________");
//        indexer.getIndices().forEach(System.out::println);
//
//        System.out.println("________________________________");
//        indexer.searchTypes(Document.class).forEach(System.out::println);
//
//        System.out.println("________________________________");
//        indexer.searchTypes(NamedEntity.class).forEach(System.out::println);
//
//        System.out.println("________________________________");
//        indexer.searchHasChild(Document.class, NamedEntity.class)
//                .forEach(System.out::println);
//
//        System.out.println("________________________________");
//        indexer.searchHasChild(Document.class, NamedEntity.class, "category:PERSON")
//                .forEach(System.out::println);

}
