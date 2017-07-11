package org.icij.datashare;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.hazelcast.core.*;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.SourcePath;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.io.FileSystemScanning;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.extraction.FileParsing;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.Indexing;
import org.icij.datashare.text.nlp.Annotation;
import org.icij.datashare.text.nlp.NlpPipeline;
import org.icij.datashare.text.nlp.NamedEntityRecognition;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.concurrent.DataGrid;
import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.BooleanLatch;
import org.icij.datashare.concurrent.LatchForwarding;
import org.icij.datashare.concurrent.queue.QueueForwarding;
import org.icij.datashare.concurrent.task.*;
import org.icij.datashare.function.ThrowingFunction;


/**
 * DataShare Facade class
 *
 * Created by julien on 11/21/16.
 */
public final class DataShare {

    public enum Stage {
        SCANNING,
        PARSING,
        NLP;

        public static final Comparator<Stage> comparator = Comparator.comparing(Stage::ordinal);

        public static Optional<Stage> parse(final String stage) {
            if (stage== null || stage.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(stage.toUpperCase(Locale.ROOT)));
            }  catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        public static ThrowingFunction<List<String>, List<Stage>> parseAll =
                list ->
                        list.stream()
                                .map(Stage::parse)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .collect(Collectors.toList());
    }

    private static final Logger LOGGER = LogManager.getLogger(DataShare.class);

    public static final List<DataShare.Stage>      DEFAULT_STAGES             = asList(DataShare.Stage.values());

    public static final FileParser.Type            DEFAULT_PARSER_TYPE        = FileParser.DEFAULT_TYPE;
    public static final int                        DEFAULT_PARSER_PARALLELISM = FileParser.DEFAULT_PARALLELISM;
    public static final boolean                    DEFAULT_PARSER_OCR         = FileParser.DEFAULT_ENABLE_OCR;

    public static final List<NlpPipeline.Type>     DEFAULT_NLP_PIPELINES      = asList(NlpPipeline.Type.values());
    public static final int                        DEFAULT_NLP_PARALLELISM    = NlpPipeline.DEFAULT_PARALLELISM;
    public static final List<NlpStage>             DEFAULT_NLP_STAGES         = NlpPipeline.DEFAULT_TARGET_STAGES;
    public static final List<NamedEntity.Category> DEFAULT_NLP_ENTITIES       = NlpPipeline.DEFAULT_ENTITIES;
    public static final boolean                    DEFAULT_NLP_CACHING        = NlpPipeline.DEFAULT_CACHING;

    public static final Indexer.Type               DEFAULT_INDEXER_TYPE       = Indexer.DEFAULT_TYPE;
    public static final Indexer.NodeType           DEFAULT_INDEXER_NODE_TYPE  = Indexer.DEFAULT_NODETYPE;
    public static final List<String>               DEFAULT_INDEXER_NODE_HOSTS = emptyList();
    public static final List<Integer>              DEFAULT_INDEXER_NODE_PORTS = emptyList();
    public static final String                     DEFAULT_INDEX              = "datashare-local";

    private static ReentrantLock datashareLock = new ReentrantLock();

    private static TaskExecutor asyncTaskExecutor;

    private static void executeAsync(List<Task> tasks) {
        LOGGER.info("Starting execution...");
        asyncTaskExecutor = new AsyncTaskExecutor(tasks);
        asyncTaskExecutor.start();
        asyncTaskExecutor.shutdown();
        asyncTaskExecutor.awaitTermination();
        asyncTaskExecutor.stop();
    }

    private static void executeAsync(Task... tasks) {
        executeAsync(asList(tasks));
    }

    public static boolean isProcessing() {
        return datashareLock.isLocked();
    }

    public static void shutdown() {
        if (asyncTaskExecutor != null)
            asyncTaskExecutor.shutdown();
    }

    public static void stop() {
        if (asyncTaskExecutor != null)
            asyncTaskExecutor.stop();
    }

    private static Optional<Indexer> awaitIndexIsUp(Indexer indexer, String index) {
        if ( ! indexer.awaitIndexIsUp(index)) {
            LOGGER.error("Index " + index + " is down. Closing connection");
            indexer.close();
            return Optional.empty();
        }
        return Optional.of(indexer);
    }

//        System.out.println("________________________________");
//        indexer.getIndices().forEach(System.out::println);
//        System.out.println("________________________________");
//        indexer.searchTypes(Document.class).forEach(System.out::println);
//        System.out.println("________________________________");
//        indexer.searchTypes(NamedEntity.class).forEach(System.out::println);
//        System.out.println("________________________________");
//        indexer.searchHasChild(Document.class, NamedEntity.class)
//                .forEach(System.out::println);
//        System.out.println("________________________________");
//        indexer.searchHasChild(Document.class, NamedEntity.class, "category:PERSON")
//                .forEach(System.out::println);


    /**
     * Stand-alone DataShare
     */
    public static final class StandAlone {

        /**
         * Extract {@link Document}s from files in {@code inputDir},
         * Extract {@link NamedEntity}s from {@link Document}s,
         * Store them into {@code index}
         *
         * {@link Task}s coordination with local or shared in-memory {@link BlockingQueue}s,
         *  - Scan files from {@code inputDir}, put on path queue
         *  - Parse each  {@link Path} into a {@link Document}, poll from path queue and put on document queue
         *  - Index each  {@link Document} to {@code index} using {@code indexer}
         *  - Extract all {@link NamedEntity}s from each {@link Document} using {@code nlpPipelineTypes} {@link NlpPipeline}s
         *  - Index each  {@link NamedEntity} to {@code index} using {@code indexer}
         *
         * @param inputDir                the directory {@link Path} from which source files are scanned
         * @param fileParserType          the {@link FileParser.Type} to instantiate
         * @param fileParserDoOcr               the flag for activating OCR at file parsing time
         * @param nlpPipelineTypes        the {@link NlpPipeline.Type}s to be instantiated
         * @param nlpPipelineParallelism  the number of threads per {@code nlpPipelineType}
         * @param nlpPipelineCaching      the flag for caching models while running {@link NlpPipeline}s
         * @param nlpStages               the targeted NLP processing stage(s)
         * @param nlpTargetEntities       the targeted named entity category(ies)
         * @param indexer                 the {@link Indexer} instance
         * @param index                   the destination index
         * @return true if processings terminated successfully; false otherwise
         */
        public static boolean processDirectory(Path inputDir,
                                               FileParser.Type fileParserType,
                                               int fileParserParallelism,
                                               boolean fileParserDoOcr,
                                               List<NlpPipeline.Type> nlpPipelineTypes,
                                               int nlpPipelineParallelism,
                                               boolean nlpPipelineCaching,
                                               List<NlpStage> nlpStages,
                                               List<NamedEntity.Category> nlpTargetEntities,
                                               Indexer indexer,
                                               String index) {
            try {
                if (datashareLock.tryLock(1, TimeUnit.SECONDS)) {
                    LOGGER.info("Running Stand Alone");
                    LOGGER.info("Source Directory:             " + inputDir);
                    LOGGER.info("File Parser Type:             " + fileParserType);
                    LOGGER.info("File Parser with OCR:         " + fileParserDoOcr);
                    LOGGER.info("Nlp Pipelines:                " + nlpPipelineTypes);
                    LOGGER.info("Nlp Pipeline Parallelism:     " + nlpPipelineParallelism);
                    LOGGER.info("Nlp Pipeline Stages:          " + nlpStages);
                    LOGGER.info("Nlp Pipeline Target Entities: " + nlpTargetEntities);

                    awaitIndexIsUp(indexer, index);

                    // Scanning
                    FileSystemScanning fileSystemScanning = FileSystemScanning.create(inputDir);
                    // Forwarding (SourcePath)
                    QueueForwarding<SourcePath> sourcePathForwarding = QueueForwarding.create(fileSystemScanning);

                    // Indexing (SourcePath)
                    Indexing<SourcePath> sourcePathIndexing = Indexing.create(indexer, index, sourcePathForwarding);

                    // Parsing
                    List<FileParsing> fileParsings = FileParsing.create(
                            fileParserType,
                            fileParserParallelism,
                            fileParserDoOcr,
                            sourcePathForwarding
                    );
                    // Forwarding (Document)
                    QueueForwarding<Document> documentForwarding = QueueForwarding.create(fileParsings);

                    // Indexing (Document)
                    Indexing<Document> documentIndexing = Indexing.create(indexer, index, documentForwarding);

                    // Named Entity Recognition
                    List<NamedEntityRecognition> namedEntityRecognitions = NamedEntityRecognition.create(
                            nlpPipelineTypes,
                            nlpPipelineParallelism,
                            nlpStages,
                            nlpTargetEntities,
                            nlpPipelineCaching,
                            documentForwarding
                    );

                    Indexing<NamedEntity> namedEntityIndexing = Indexing.create(indexer, index, namedEntityRecognitions);

                    List<Task> tasks = new ArrayList<>(asList(
                            fileSystemScanning,
                            sourcePathForwarding,
                            sourcePathIndexing,
                            documentForwarding,
                            documentIndexing,
                            namedEntityIndexing
                    ));
                    tasks.addAll(fileParsings);
                    tasks.addAll(namedEntityRecognitions);

                    // Execute!
                    executeAsync(tasks);

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
                datashareLock.unlock();
            }
        }

        public static boolean processDirectory(Path inputDir, Indexer indexer) {
            return processDirectory(
                    inputDir,
                    DEFAULT_PARSER_TYPE,
                    DEFAULT_PARSER_PARALLELISM,
                    DEFAULT_PARSER_OCR,
                    DEFAULT_NLP_PIPELINES,
                    DEFAULT_NLP_PARALLELISM,
                    DEFAULT_NLP_CACHING,
                    DEFAULT_NLP_STAGES,
                    DEFAULT_NLP_ENTITIES,
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
                    DEFAULT_PARSER_TYPE,
                    DEFAULT_PARSER_PARALLELISM,
                    DEFAULT_PARSER_OCR,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    DEFAULT_NLP_CACHING,
                    DEFAULT_NLP_STAGES,
                    DEFAULT_NLP_ENTITIES,
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
                    DEFAULT_PARSER_TYPE,
                    DEFAULT_PARSER_PARALLELISM,
                    DEFAULT_PARSER_OCR,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    DEFAULT_NLP_CACHING,
                    nlpStages,
                    nlpTargetEntities,
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
                    DEFAULT_PARSER_TYPE,
                    DEFAULT_PARSER_PARALLELISM,
                    DEFAULT_PARSER_OCR,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    DEFAULT_NLP_CACHING,
                    nlpStages,
                    nlpTargetEntities,
                    indexer,
                    index
            );
        }

        public static boolean processDirectory(Path inputDir,
                                               int fileParserParallelism,
                                               List<NlpStage> nlpStages,
                                               List<NamedEntity.Category> nlpTargetEntities,
                                               List<NlpPipeline.Type> nlpPipelineTypes,
                                               int nlpPipelineParallelism,
                                               Indexer indexer,
                                               String index) {
            return processDirectory(
                    inputDir,
                    DEFAULT_PARSER_TYPE,
                    fileParserParallelism,
                    DEFAULT_PARSER_OCR,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    DEFAULT_NLP_CACHING,
                    nlpStages,
                    nlpTargetEntities,
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
                    DEFAULT_PARSER_TYPE,
                    DEFAULT_PARSER_PARALLELISM,
                    enableOcr,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    DEFAULT_NLP_CACHING,
                    nlpStages,
                    nlpTargetEntities,
                    indexer,
                    index);
        }

        public static boolean processDirectory(Path inputDir,
                                               int fileParserParallelism,
                                               boolean enableOcr,
                                               List<NlpStage> nlpStages,
                                               List<NamedEntity.Category> nlpTargetEntities,
                                               List<NlpPipeline.Type> nlpPipelineTypes,
                                               int nlpPipelineParallelism,
                                               Indexer indexer,
                                               String index) {
            return processDirectory(
                    inputDir,
                    DEFAULT_PARSER_TYPE,
                    fileParserParallelism,
                    enableOcr,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    DEFAULT_NLP_CACHING,
                    nlpStages,
                    nlpTargetEntities,
                    indexer,
                    index);
        }

    }


    /**
     * DataShare as a Cluster Node
     */
    public static final class Node {

        /**
         * Extract {@link Document}s from files in {@code inputDir},
         * Put {@link Document}s on networked and shared {@link BlockingQueue}
         * Store {@link Document}s into {@code index}
         *
         * {@link Task}s coordination with local or distributed shared in-memory {@link BlockingQueue}s,
         *  - Scan files from {@code inputDir}
         *  - Parse each file {@link Path} into a {@link Document}, poll from path queue and feed document queue
         *  - Index each {@link Document} to {@code index} using {@code indexerType} {@link Indexer}
         *
         * @param inputDir                 the directory {@link Path} from which source files are scanned
         * @param indexer                  the {@link Indexer} instance
         * @param index                    the index name
         * @return true if processings terminated successfully; false otherwise
         */
        public static boolean scanDirectory(Path inputDir,
                                            Indexer indexer,
                                            String index) {
            try {
                if (datashareLock.tryLock(1, TimeUnit.SECONDS)) {
                    LOGGER.info("Running as " + Stage.SCANNING + " Node");
                    LOGGER.info(DataGrid.INSTANCE.getCluster().getLocalMember());
                    LOGGER.info("Input Directory:      " + inputDir);

                    awaitIndexIsUp(indexer, index);

                    // This is a PARSING node
                    DataGrid.INSTANCE.setLocalMemberRole(Stage.SCANNING);

                    // Scanning
                    FileSystemScanning fileSystemScanning = FileSystemScanning.create(inputDir);
                    // Forwarding (SourcePath)
                    QueueForwarding<SourcePath> sourcePathForwarding = QueueForwarding.create(fileSystemScanning);

                    // Indexing (SourcePath)
                    Indexing<SourcePath> sourcePathIndexing = Indexing.create(indexer, index, sourcePathForwarding);

                    // Distributed Document Queue
                    BlockingQueue<Document> sourcePathQueueGlobal = DataGrid.INSTANCE.getBlockingQueue("SourcePath");
                    // Forward SourcePath Latch Globally
                    ICountDownLatch noMoreSourcePathGlobal = DataGrid.INSTANCE.getCountDownLatch("SourcePath");
                    noMoreSourcePathGlobal.trySetCount(1);
                    LatchForwarding sourcePathLatchPushing = new LatchForwarding(sourcePathForwarding, noMoreSourcePathGlobal);

                    // Await a PROCESSING node to join the cluster
                    DataGrid.INSTANCE.awaitMemberJoins(Stage.PARSING);

                    List<Task> tasks = new ArrayList<>(asList(
                            fileSystemScanning,
                            sourcePathIndexing,
                            sourcePathLatchPushing
                    ));

                    // Execute!
                    executeAsync(tasks);

                    DataGrid.INSTANCE.shutdown();

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
                datashareLock.unlock();
            }
        }

        /**
         * Extract {@link Document}s from files in {@code inputDir},
         * Put {@link Document}s on networked and shared {@link BlockingQueue}
         * Store {@link Document}s into {@code index}
         *
         * {@link Task}s coordination with local or distributed shared in-memory {@link BlockingQueue}s,
         *  - Scan files from {@code inputDir}
         *  - Parse each file {@link Path} into a {@link Document}, poll from path queue and feed document queue
         *  - Index each {@link Document} to {@code index} using {@code indexerType} {@link Indexer}
         *
         * @param inputDir                 the directory {@link Path} from which source files are scanned
         * @param fileParserType           the {@link FileParser.Type} to instantiate
         * @param fileParserDoOcr                the flag for activating OCR at file parsing time
         * @param indexer                  the {@link Indexer} instance
         * @param index                    the index name
         * @return true if processings terminated successfully; false otherwise
         */
        public static boolean parseDirectory(Path inputDir,
                                             FileParser.Type fileParserType,
                                             int fileParserParallelism,
                                             boolean fileParserDoOcr,
                                             Indexer indexer,
                                             String index) {
            try {
                if (datashareLock.tryLock(1, TimeUnit.SECONDS)) {
                    LOGGER.info("Running as " + asList(Stage.SCANNING, Stage.PARSING) + " Node");
                    LOGGER.info(DataGrid.INSTANCE.getCluster().getLocalMember());
                    LOGGER.info("Input Directory:      " + inputDir);
                    LOGGER.info("File Parser Type:     " + fileParserType);
                    LOGGER.info("File Parser with OCR: " + fileParserDoOcr);

                    awaitIndexIsUp(indexer, index);

                    // This is a PARSING node
                    DataGrid.INSTANCE.setLocalMemberRole(Stage.PARSING);

                    // Scanning
                    FileSystemScanning fileSystemScanning = FileSystemScanning.create(inputDir);

                    // Parsing
                    List<FileParsing> fileParsings = FileParsing.create(
                            fileParserType,
                            fileParserParallelism,
                            fileParserDoOcr,
                            fileSystemScanning
                    );

                    // Document Forwarding
                    QueueForwarding<Document> documentQueueForwarding = QueueForwarding.create(fileParsings);

                    // Document Indexing
                    Indexing<Document> documentIndexing = Indexing.create(indexer, index, documentQueueForwarding);

                    // Distributed Document Queue
                    BlockingQueue<Document> documentsQueueGlobal = DataGrid.INSTANCE.getBlockingQueue("Document");
                    // Forward Documents Globally
                    documentQueueForwarding.addOutput(documentsQueueGlobal);
                    // Forward Document Latch Globally
                    ICountDownLatch noMoreDocumentGlobal = DataGrid.INSTANCE.getCountDownLatch("Document");
                    noMoreDocumentGlobal.trySetCount(1);
                    LatchForwarding documentLatchPushing = new LatchForwarding(documentQueueForwarding, noMoreDocumentGlobal);

                    // Await a PROCESSING node to join the cluster
                    DataGrid.INSTANCE.awaitMemberJoins(Stage.NLP);

                    List<Task> tasks = new ArrayList<>(asList(
                            fileSystemScanning,
                            documentQueueForwarding,
                            documentIndexing,
                            documentLatchPushing
                    ));
                    tasks.addAll(fileParsings);

                    // Execute!
                    executeAsync(tasks);

                    DataGrid.INSTANCE.shutdown();

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
                datashareLock.unlock();
            }
        }

        /**
         * Take {@link Document}s from distributed shared {@link BlockingQueue}
         * Extract {@link NamedEntity}s from {@link Document}s
         * Store them into {@code index}
         *
         * {@link Task}s coordination with local or distributed shared in-memory {@link BlockingQueue}s,
         *  - Extract all {@link NamedEntity}s from each {@link Document} using {@code nlpPipelineTypes} {@link NlpPipeline}s
         *  - Index each {@link NamedEntity} to {@code index} using {@code indexerType} {@link Indexer}
         *
         * @param nlpPipelineTypes         the {@link NlpPipeline.Type} to instantiate
         * @param nlpPipelineParallelism   the number of threads per {@code nlpPipelineType}
         * @param nlpPipelineCaching       the flag for caching models while running {@code NlpPipeline}s
         * @param nlpStages                the targeted NLP processing stage(s)
         * @param nlpTargetEntities        the targeted named entity category(ies)
         * @param indexer                  the indexer instance
         * @param index                    the destination index
         * @return true if processings terminated successfully; false otherwise
         */
        public static boolean extractNamedEntities(List<NlpStage> nlpStages,
                                                   List<NamedEntity.Category> nlpTargetEntities,
                                                   List<NlpPipeline.Type> nlpPipelineTypes,
                                                   int nlpPipelineParallelism,
                                                   boolean nlpPipelineCaching,
                                                   Indexer indexer,
                                                   String index) {
            try {
                if (datashareLock.tryLock(1, TimeUnit.SECONDS)) {
                    LOGGER.info("Running as " + Stage.NLP + " Node");
                    LOGGER.info(DataGrid.INSTANCE.getCluster().getLocalMember());
                    LOGGER.info("Nlp Pipeline Types:           " + nlpPipelineTypes);
                    LOGGER.info("Nlp Pipeline Parallelism:     " + nlpPipelineParallelism);
                    LOGGER.info("Nlp Pipeline Stages:          " + nlpStages);
                    LOGGER.info("Nlp Pipeline Target Entities: " + nlpTargetEntities);

                    awaitIndexIsUp(indexer, index);

                    // This is a PROCESSING node
                    DataGrid.INSTANCE.setLocalMemberRole(Stage.NLP);

                    // Distributed Document Queue
                    BlockingQueue<Document> documentsQueue = DataGrid.INSTANCE.getBlockingQueue("Document");
                    // Distributed Document Latch
                    ICountDownLatch noMoreDocumentGlobal = DataGrid.INSTANCE.getCountDownLatch("Document");
                    Latch noMoreDocumentLocal = new BooleanLatch();
                    LatchForwarding documentLatchForwarding = new LatchForwarding(noMoreDocumentGlobal, noMoreDocumentLocal);

                    // Document Forwarding
                    QueueForwarding<Document> documentQueueForwarding = QueueForwarding.create(documentsQueue, noMoreDocumentLocal);

                    // Named Entity Recognition
                    List<NamedEntityRecognition> namedEntityRecognitions = NamedEntityRecognition.create(
                            nlpPipelineTypes,
                            nlpPipelineParallelism,
                            nlpStages,
                            nlpTargetEntities,
                            nlpPipelineCaching,
                            documentQueueForwarding
                    );

                    // NamedEntity Indexing
                    List<Indexing<NamedEntity>> namedEntityIndexings = namedEntityRecognitions.stream()
                            .map( ner -> new Indexing<>(indexer, index, ner) )
                            .collect(Collectors.toList());

                    // Await PARSING node to join the cluster
                    DataGrid.INSTANCE.awaitMemberJoins(Stage.PARSING);

                    List<Task> tasks = new ArrayList<>(asList(
                            documentLatchForwarding,
                            documentQueueForwarding
                    ));
                    tasks.addAll(namedEntityRecognitions);
                    tasks.addAll(namedEntityIndexings);

                    // Execute
                    executeAsync( tasks );

                    DataGrid.INSTANCE.shutdown();

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
                datashareLock.unlock();
            }
        }

    }

}
