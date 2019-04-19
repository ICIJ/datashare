package org.icij.datashare.db;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

public class Bench {
    static Logger logger = LoggerFactory.getLogger(Bench.class);
    @Rule
    public DbSetupRule dbRule = new DbSetupRule(DbSetupRule.createPostgresql());
    private JooqRepository repository = new JooqRepository(new DataSourceConnectionProvider(dbRule.dataSource), SQLDialect.POSTGRES_10);

    @Test
    public void testReadsAndWrites() throws SQLException {
        int nbDocs = 100;
        int nbNes = 100;
        LinkedList<String> neIds = new LinkedList<>();
        logger.info("writing {} documents with {} named entities", nbDocs, nbNes);
        long beginTime = System.currentTimeMillis();
        for (int docIdx = 0; docIdx < nbDocs; docIdx++) {
            Document document = new Document(Paths.get("/foo/bar_" + docIdx + ".txt"),
                    "This is a content with Gael Giraud " + docIdx,
                    Language.FRENCH,
                    Charset.defaultCharset(),
                    "text/plain",
                    new HashMap<String, String>() {{
                        put("key1", "value1");
                        put("key2", "value2");
                        put("key3", "value3");
                        put("key4", "value4");
                        put("key5", "value5");
                        put("key6", "value6");
                        put("key7", "value7");
                        put("key8", "value8");
                        put("key9", "value9");
                        put("key10", "value10");
                    }},
                    Document.Status.INDEXED, 345L);
            repository.create(document);

            List<NamedEntity> neList = new ArrayList<>();
            for (int neIdx = 0; neIdx < nbNes; neIdx++) {
                NamedEntity ne = NamedEntity.create(
                        NamedEntity.Category.PERSON, "Gael Giraud" + neIdx, 23, document.getId(),
                        Pipeline.Type.CORENLP, Language.FRENCH);
                neIds.add(ne.getId());
                neList.add(ne);
            }
            repository.create(neList);
            if (docIdx % 10 == 0) {
                logger.info("wrote {} docs", docIdx);
            }
        }
        long endTime = System.currentTimeMillis();
        logger.info("done in {}ms", endTime - beginTime);

        logger.info("reading " + neIds.size() + " NamedEntities");
        beginTime = System.currentTimeMillis();
        for (String neId : neIds) {
            repository.getNamedEntity(neId);
        }
        endTime = System.currentTimeMillis();
        logger.info("done in {}ms", endTime - beginTime);
    }

    private String generate(String seed, int nbBytes) {
        return String.join("", Collections.nCopies(nbBytes / seed.length(), seed));
    }
}
