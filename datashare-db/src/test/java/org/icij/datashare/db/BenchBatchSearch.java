package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;
import org.jooq.SQLDialect;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;

public class BenchBatchSearch {
    private static Logger logger = LoggerFactory.getLogger(BenchBatchSearch.class);
    @Rule
    public DbSetupRule dbRule = new DbSetupRule("jdbc:postgresql://postgresql/test?user=test&password=test");
    private BatchSearchRepository repository = new JooqBatchSearchRepository(dbRule.dataSource, SQLDialect.POSTGRES);

    @Test
    public void testReadsAndWrites() {
        int nbBatchSearches = 100;
        int nbQueries = 1000;
        int nbResultsPerQuery = 10;
        logger.info("writing {} batch searches with {} queries and {} results per query", nbBatchSearches, nbQueries, nbResultsPerQuery);
        long beginTime = System.currentTimeMillis();
        for (int bsIdx = 0; bsIdx < nbBatchSearches; bsIdx++) {
            List<String> queries = IntStream.range(0, nbQueries).mapToObj(i -> "query " + i).collect(Collectors.toList());
            BatchSearch batch = new BatchSearch(project("test"), "name" + bsIdx, "desc" + bsIdx, queries, User.local());
            repository.save(batch);

            for (String q: queries) {
                List<Document> documents = IntStream.range(0, nbResultsPerQuery).mapToObj(i -> createDoc("doc" + i).build()).collect(Collectors.toList());
                repository.saveResults(batch.uuid, q, documents);
            }
            if (bsIdx % 2 == 0) {
                logger.info("wrote {} batches", bsIdx);
            }
        }
        long endTime = System.currentTimeMillis();
        logger.info("done in {}ms", endTime - beginTime);

        logger.info("reading batch searches");
        beginTime = System.currentTimeMillis();
        repository.get(User.local());
        endTime = System.currentTimeMillis();
        logger.info("done in {}ms", endTime - beginTime);
    }
}
