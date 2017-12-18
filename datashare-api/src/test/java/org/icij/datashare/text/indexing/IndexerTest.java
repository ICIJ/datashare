package org.icij.datashare.text.indexing;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.command.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;

/**
 * Created by julien on 7/13/16.
 */
public class IndexerTest {


    private static void testIndexer(Indexer indexer) {

        // testIndexObjectMapper(indexer);
        // testIndexerCommands(indexer);

    }

    private static void testIndexObjectMapper(Indexer indexer) {

        // Document    <-> JSON,
        // NamedEntity <-> JSON

        // New Document
        Path p = Paths.get("/home/julien/data/luxleaks/source/1345272-acergy-group-now-subsea-7-2010-tax-ruling.pdf");
        Optional<Document> dOpt = Document.create(p);
        if (! dOpt.isPresent())
            return;
        Document d = dOpt.get();

        // Document --> JSON
        Map<String, Object> s = JsonObjectMapper.getJson(d);
        System.out.println("Document::toJSON " + s);
        // Document --> JSON
        s = JsonObjectMapper.getJson(d);
        String id = JsonObjectMapper.getId(d);
        Class<Document> cls = Document.class;
        Document e = JsonObjectMapper.getObject(id, s, cls);
        System.out.println("Document::toJSON " + e.toString());
        System.out.println("Document::getHash " + e.getHash());
        e.getContentType().ifPresent(c -> System.out.println("Document::getMimeType " + c ) );
        e.getLength().ifPresent( c -> System.out.println("Document::getLength " + c ) );
        e.getLanguage().ifPresent( c -> System.out.println("Document::getLanguage " + c ) );
        e.getMetadata().ifPresent( c -> System.out.println("Document::getMetadata " + c ) );
        // e.getContent().ifPresent( c -> System.out.println("Document::getContent " + c ) );


        // New NamedEntity
        Optional<NamedEntity> neOpt = NamedEntity.create(
                NamedEntity.Category.ORGANIZATION,
                "Magma SARL",
                12345,
                e.getHash(),
                CORENLP,
                e.getLanguage().get(),
                "NPP");
        if ( ! neOpt.isPresent())
            return;
        NamedEntity ne = neOpt.get();
        // NamedEntity --> JSON
        s = JsonObjectMapper.getJson(ne);
        System.out.println("NamedEntity::toJSON " + s);
        // JSON --> NamedEntity
        id = JsonObjectMapper.getId(d);
        Class<NamedEntity> clss = NamedEntity.class;
        NamedEntity nent = JsonObjectMapper.getObject(id, s, clss);
        System.out.println("NamedEntity::toJSON " + nent.toString());


        // Document    <-> Index
        // NamedEntity <-> Index
        Path q  = Paths.get("/home/julien/data/test.txt");
        Optional<Document> doc = Document.create(q);
        if ( ! doc.isPresent()) {
            return;
        }
        Document c = doc.get();
        System.out.println(c.toString());
        System.out.println(c.getContent());
        // Add Java Object to Index
        boolean added = indexer.add("datashare", c);
        System.out.println("Added:" + String.valueOf(added));
        // Read Java Object from Index
        Document cc = indexer.read("datashare", Document.class, c.getHash());
        System.out.println(cc.toString());
        System.out.println(cc.getContent());

    }

    private static void testIndexerCommands(Indexer indexer) {

        // Define index, type, id
        String idx = "datashare";
        String typ = "corpus";
        String id  = String.valueOf(123454321);
        String content = "Bringing INDEX Elasticsearch to Datashare!";

        // Build commands
        Map<String, Object> json = new HashMap<>();
        json.put("asOf", new Date());
        json.put("content", content);
        AddCommand addCmd = new AddCommand(indexer, idx, typ, id, json);

        Map<String, Object> updJson = new HashMap<>();
        updJson.put("asOf", new Date());
        updJson.put("content", "Bringing UPDATED Elasticsearch to Datashare!");
        UpdateCommand updCmd = new UpdateCommand(indexer, idx, typ, id, updJson);

        DeleteCommand     delCmd    = new DeleteCommand(indexer, idx, typ, id);
        ReadCommand       readCmd   = new ReadCommand(indexer, idx, typ, id);
        SearchCommand     srchCmd   = new SearchCommand(indexer, idx, typ, "UPDATED");
        GetIndicesCommand getIdxCmd = new GetIndicesCommand(indexer);
        RefreshCommand    rfshCmd   = new RefreshCommand(indexer, idx);

        // Get indices
        List<String> indices = getIdxCmd.execute();
        indices.forEach(System.out::println);

        // Search
        Stream<Map<String, Object>> s = srchCmd.execute();
        System.out.println("Search result:");
        s.forEach( System.out::println );

        // Add
        addCmd.execute();
        System.out.println("Add result: " );

        // Read
        Map<String, Object> r = readCmd.execute();
        System.out.println("Read result: " + r.toString());

        // Refresh
        rfshCmd.execute();

        // Search
        s = srchCmd.execute();
        System.out.println("Search result:");
        s.forEach( System.out::println );

        // Update
        boolean u = updCmd.execute();
        System.out.println("Upd result: " + String.valueOf(u));

        // Read
        r = readCmd.execute();
        System.out.println("Read result: " + r.toString());

        // Refresh
        rfshCmd.execute();

        // Search
        s = srchCmd.execute();
        System.out.println("Search result:");
        s.forEach( System.out::println );

        // Delete
        delCmd.execute();
        System.out.println("Del result: ");

    }

}
