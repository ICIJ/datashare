package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.metadata.Metadata;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.document.EmbeddedTikaDocument;
import org.icij.extract.document.TikaDocument;
import org.icij.spewer.FieldNames;
import org.icij.spewer.MetadataTransformer;
import org.icij.spewer.Spewer;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.lang.Integer.valueOf;
import static java.lang.System.currentTimeMillis;
import static org.apache.tika.metadata.HttpHeaders.*;
import static org.icij.datashare.com.Channel.NLP;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.text.Hasher.shorten;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.*;

public class ElasticsearchSpewer extends Spewer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewer.class);
    public static final String DEFAULT_VALUE_UNKNOWN = "unknown";

    private final Client client;
    private final ElasticsearchConfiguration esCfg;
    private final Publisher publisher;
    private final LanguageGuesser languageGuesser;

    @Inject
    public ElasticsearchSpewer(final PropertiesProvider propertiesProvider, LanguageGuesser languageGuesser, Publisher publisher) throws IOException {
        this(createESClient(propertiesProvider), languageGuesser, new FieldNames(), publisher, propertiesProvider);
    }

    ElasticsearchSpewer(final Client client, LanguageGuesser languageGuesser, final FieldNames fields, Publisher publisher, final PropertiesProvider propertiesProvider) throws IOException {
        super(fields);
        this.client = client;
        this.languageGuesser = languageGuesser;
        this.publisher = publisher;
        this.esCfg = new ElasticsearchConfiguration(propertiesProvider);
        logger.info("spewer defined with {}", esCfg);
    }

    @Override
    public void write(final TikaDocument document, final Reader reader) throws IOException {
        indexDocument(document, reader, null, null, 0);
        for (EmbeddedTikaDocument childDocument : document.getEmbeds()) {
            writeTree(childDocument, document, document, 1);
        }
    }

    private IndexRequest prepareRequest(final TikaDocument document, final Reader reader,
                                        final TikaDocument parent, TikaDocument root, final int level) throws IOException {
        IndexRequest req = new IndexRequest(esCfg.indexName, esCfg.indexType, document.getId());
        Map<String, Object> jsonDocument = getMap(document, reader);

        if (parent != null) {
            jsonDocument.put(DEFAULT_PARENT_DOC_FIELD, parent.getId());
            jsonDocument.put("rootDocument", root.getId());
            req.routing(root.getId());
        }
        jsonDocument.put("extractionLevel", level);
        req = req.source(jsonDocument);
        req.setRefreshPolicy(esCfg.refreshPolicy);
        return req;
    }

    Map<String, Object> getMap(TikaDocument document, Reader reader) throws IOException {
        Map<String, Object> jsonDocument = new HashMap<>();

        Map<String, Object> metadata = new HashMap<>();
        new MetadataTransformer(document.getMetadata(), fields).transform(
                new MapValueConsumer(metadata), new MapValuesConsumer(metadata));

        jsonDocument.put(esCfg.docTypeField, ES_DOCUMENT_TYPE);
        jsonDocument.put(esCfg.indexJoinField, new HashMap<String, String>() {{
            put("name", "Document");
        }});
        jsonDocument.put("path", document.getPath().toString());
        jsonDocument.put("status", Document.Status.INDEXED);
        jsonDocument.put("nerTags", new HashSet<>());
        jsonDocument.put("extractionDate", ISODateTimeFormat.dateTime().print(new Date().getTime()));
        jsonDocument.put("metadata", metadata);
        jsonDocument.put("contentType", getField(document.getMetadata(), CONTENT_TYPE, DEFAULT_VALUE_UNKNOWN).split(";")[0]);
        jsonDocument.put("contentLength", valueOf(getField(document.getMetadata(), CONTENT_LENGTH, "-1")));
        jsonDocument.put("contentEncoding", getField(document.getMetadata(), CONTENT_ENCODING, DEFAULT_VALUE_UNKNOWN));

        String content = toString(reader).trim();
        jsonDocument.put("language", Language.parse(languageGuesser.guess(content)));
        jsonDocument.put(ES_CONTENT_FIELD, content);
        return jsonDocument;
    }

    private void writeTree(final TikaDocument doc, final TikaDocument parent, TikaDocument root, final int level)
            throws IOException {
        try (final Reader reader = doc.getReader()) {
            indexDocument(doc, reader, parent, root, level);
        }

        for (EmbeddedTikaDocument child : doc.getEmbeds()) {
            writeTree(child, doc, root, level + 1);
        }
    }

    private void indexDocument(TikaDocument document, Reader reader,
                               final TikaDocument parent, TikaDocument root, final int level) throws IOException {
        final IndexRequest req = prepareRequest(document, reader, parent, root, level);
        try {
            long before = currentTimeMillis();
            IndexResponse indexResponse = client.index(req).get();
            logger.info("{} {} added to elasticsearch in {}ms: {}", parent == null ? "Document" : "Child",
                    shorten(indexResponse.getId(), 4), currentTimeMillis() - before, document);
            synchronized (publisher) { // jedis instance is not thread safe and Spewer is shared in DocumentConsumer threads
                publisher.publish(NLP, new Message(EXTRACT_NLP)
                        .add(Message.Field.DOC_ID, indexResponse.getId())
                        .add(Message.Field.R_ID, parent == null ? document.getId() : root.getId()));
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.warn("interrupted execution of request", e);
        }
    }

    private String getField(Metadata metadata, String fieldname, String defaultValue) {
        String s = metadata.get(fieldname);
        return s == null ? defaultValue: s;
    }

    @Override
    public void writeMetadata(TikaDocument document) throws IOException { throw new UnsupportedOperationException();}

    public ElasticsearchSpewer withRefresh(WriteRequest.RefreshPolicy refreshPolicy) {
        this.esCfg.withRefresh(refreshPolicy);
        return this;
    }

    static class MapValueConsumer implements MetadataTransformer.ValueConsumer {

        private final Map<String, Object> map;
        MapValueConsumer(final Map<String, Object> map) { this.map = map;}

        @Override
        public void accept(String name, String value) throws IOException {
            map.put(name, value);
        }

    }
    static class MapValuesConsumer implements MetadataTransformer.ValueArrayConsumer {

        private final Map<String, Object> map;
        MapValuesConsumer(Map<String, Object> jsonDocument) { map = jsonDocument;}

        @Override
        public void accept(String name, String[] values) throws IOException {
            map.put(name, String.join(",", values));
        }

    }
    @Override
    public void close() throws Exception { client.close();}
}
