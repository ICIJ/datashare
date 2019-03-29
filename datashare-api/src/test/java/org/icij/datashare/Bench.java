package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedList;

public class Bench {
    static Logger logger = LoggerFactory.getLogger(Bench.class);

    @Test
    public void testReadsAndWrites() throws SQLException {
        NamedEntityRepository neo4jRepository= new JooqNamedEntityRepository();
        int nbDocs = 100;
        int nbNes = 100;
        LinkedList<String> neIds = new LinkedList<>();
        logger.info("writing {} documents with {} named entities", nbDocs, nbNes);
        long beginTime = System.currentTimeMillis();
        for (int docIdx = 0 ; docIdx < nbDocs ; docIdx++) {
            Document document = new Document(Paths.get("/foo/bar_" + docIdx + ".txt"),
                    "This is a content with Gael Giraud " + docIdx,
                    Language.FRENCH,
                    Charset.defaultCharset(),
                    "text/plain",
                    new HashMap<String, String>() {{
                        put("key1", "value1");
                        put("key2", "value2");
                    }},
                    Document.Status.INDEXED);
            neo4jRepository.create(document);

            for (int neIdx = 0; neIdx < nbNes; neIdx++) {
                NamedEntity ne = NamedEntity.create(
                        NamedEntity.Category.PERSON, "Gael Giraud" + neIdx, 23, document.getId(),
                        Pipeline.Type.CORENLP, Language.FRENCH);
                neo4jRepository.create(ne);
                neIds.add(ne.getId());
            }
            if (docIdx % 10 == 0) {
                logger.info("wrote {} docs", docIdx);
            }
        }
        long endTime = System.currentTimeMillis();
        logger.info("done in {}ms", endTime - beginTime);

        logger.info("reading " + neIds.size() + " NamedEntities");
        beginTime = System.currentTimeMillis();
        for (String neId: neIds) {
            neo4jRepository.get(neId);
        }
        endTime = System.currentTimeMillis();
        logger.info("done in {}ms", endTime -beginTime);
    }
}
