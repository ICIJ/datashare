package org.icij.datashare;

import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.INDEX_ADDRESS_PROP;

public class DocumentIngestor {
    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestor.class);
    static ExecutorService executorService;
    static Queue<List<Document>> documentQueue = new LinkedList<>();

    public static void main(String[] args) {
        OptionSet optionSet = parseArgs(args);
        Integer nbThreads = (Integer) optionSet.valueOf("t");
        Integer bulkSize = (Integer) optionSet.valueOf("bulkSize");
        String elasticsearchUrl = (String) optionSet.valueOf("elasticsearchAddress");
        String indexName = (String) optionSet.valueOf("indexName");
        Integer nbDocuments = (Integer) optionSet.valueOf("nbDocuments");
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put(INDEX_ADDRESS_PROP, elasticsearchUrl);
        }});
        Indexer indexer = new ElasticsearchIndexer(ElasticsearchConfiguration.createESClient(propertiesProvider), propertiesProvider);

        logger.info("ingest {} documents in elasticsearch {} with bulk of {} and {} threads", nbDocuments, elasticsearchUrl, bulkSize, nbThreads);
        executorService = Executors.newFixedThreadPool(nbThreads);
        new DocumentProducer(nbDocuments, bulkSize).run();
        IntStream.range(0, nbThreads).forEach(n -> executorService.submit(new DocumentConsumer(indexer, indexName)));
        executorService.shutdown();
    }

    private static OptionSet parseArgs(String[] args) {
        OptionParser parser = new OptionParser();
        AbstractOptionSpec<Void> optionSpec = parser.acceptsAll(asList("h", "help"), "this help").forHelp();
        parser.acceptsAll(
                asList("u", "elasticsearchAddress"), "Elasticsearch url")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(EnvUtils.resolveUri("elasticsearch", "http://elasticsearch:9200"));
        parser.acceptsAll(
                asList("i", "indexName"), "Name of the index")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("local-datashare");
        parser.acceptsAll(
                asList("n", "nbDocuments"), "Number of documents")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(1000);
        parser.acceptsAll(
                asList("b", "bulkSize"), "Bulk size")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(10);
        parser.acceptsAll(
                asList("t", "nbThread"), "number of threads")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(Runtime.getRuntime().availableProcessors());
        try {
            OptionSet optionSet = parser.parse(args);
            if (optionSet.has(optionSpec)) {
                printHelp(parser);
                System.exit(0);
            }
            return optionSet;
        } catch (Exception e) {
            logger.error("failed to parse args", e);
            printHelp(parser);
            System.exit(1);
        }
        return null;
    }

    private static void printHelp(OptionParser parser) {
        try {
            System.out.println("Usage: ");
            parser.printHelpOn(System.out);
        } catch (IOException e) {
            logger.error("Failed to print help message", e);
        }
    }

    static class DocumentConsumer implements Callable<Integer> {
        private final Indexer indexer;
        private final String indexName;
        public DocumentConsumer(Indexer indexer, String indexName) {
            this.indexer = indexer;
            this.indexName = indexName;
        }
        @Override
        public Integer call() throws Exception {
            List<Document> bulkList = documentQueue.poll();
            int addedDocs = 0;
            while (bulkList != null) {
                try {
                    indexer.bulkAdd(indexName, bulkList);
                    addedDocs += bulkList.size();
                } catch (IOException ex) {
                    logger.warn("error while inserting " + ex);
                }
                bulkList = documentQueue.poll();
            }
            logger.info("exit consumer, {} added documents", addedDocs);
            return addedDocs;
        }
    }

    static class DocumentProducer implements Runnable {
        private final long nbDocs;
        private final int bulkSize;

        public DocumentProducer(long nbDocs, int bulkSize) {
            this.nbDocs = nbDocs;
            this.bulkSize = bulkSize;
        }
        @Override
        public void run() {
            for (int b = 0; b < nbDocs/bulkSize ; b++) {
                List<Document> bulkList = new LinkedList<>();
                for (int i = 0; i<bulkSize; i++) {
                    String name = String.format("document-%d-%d", i, b);
                    bulkList.add(DocumentBuilder.createDoc(Hasher.SHA_384.hash(name)).with(name + CONTENT).build());
                }
                documentQueue.add(bulkList);
            }
            logger.info("exit producer queue size is {} bulks of {}", documentQueue.size(), bulkSize);
        }
    }
    static String CONTENT = " Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium, totam rem aperiam, eaque ipsa quae ab illo inventore veritatis et quasi architecto beatae vitae dicta sunt explicabo. Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores eos qui ratione voluptatem sequi nesciunt. Neque porro quisquam est, qui dolorem ipsum quia dolor sit amet, consectetur, adipisci velit, sed quia non numquam eius modi tempora incidunt ut labore et dolore magnam aliquam quaerat voluptatem. Ut enim ad minima veniam, quis nostrum exercitationem ullam corporis suscipit laboriosam, nisi ut aliquid ex ea commodi consequatur? Quis autem vel eum iure reprehenderit qui in ea voluptate velit esse quam nihil molestiae consequatur, vel illum qui dolorem eum fugiat quo voluptas nulla pariatur?";
}
