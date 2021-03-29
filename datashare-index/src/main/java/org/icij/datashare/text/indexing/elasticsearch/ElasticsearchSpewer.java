package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.document.TikaDocument;
import org.icij.spewer.FieldNames;
import org.icij.spewer.Spewer;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static java.lang.System.currentTimeMillis;
import static java.nio.file.Paths.get;
import static java.util.Optional.ofNullable;
import static org.apache.tika.metadata.HttpHeaders.*;
import static org.icij.datashare.com.Channel.NLP;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.text.Hasher.shorten;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.*;

public class ElasticsearchSpewer extends Spewer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewer.class);
    public static final String DEFAULT_VALUE_UNKNOWN = "unknown";

    private final RestHighLevelClient client;
    private final ElasticsearchConfiguration esCfg;
    private final Publisher publisher;
    private final LanguageGuesser languageGuesser;
    private String indexName;

    @Inject
    public ElasticsearchSpewer(final RestHighLevelClient client, LanguageGuesser languageGuesser, final FieldNames fields,
                               Publisher publisher, final PropertiesProvider propertiesProvider) {
        super(fields);
        this.client = client;
        this.languageGuesser = languageGuesser;
        this.publisher = publisher;
        this.esCfg = new ElasticsearchConfiguration(propertiesProvider);
        logger.info("spewer defined with {}", esCfg);
    }

    @Override
    protected void writeDocument(TikaDocument doc, TikaDocument parent, TikaDocument root, int level) throws IOException {
        final IndexRequest req = prepareRequest(doc, parent, root, level);
        long before = currentTimeMillis();
        IndexResponse indexResponse = client.index(req, RequestOptions.DEFAULT);
        logger.info("{} {} added to elasticsearch in {}ms: {}", parent == null ? "Document" : "Child",
                shorten(indexResponse.getId(), 4), currentTimeMillis() - before, doc);
        synchronized (publisher) { // jedis instance is not thread safe and Spewer is shared in DocumentConsumer threads
            publisher.publish(NLP, new Message(EXTRACT_NLP)
                    .add(Message.Field.INDEX_NAME, indexName)
                    .add(Message.Field.DOC_ID, indexResponse.getId())
                    .add(Message.Field.R_ID, parent == null ? doc.getId() : root.getId()));
        }
    }

    public ElasticsearchSpewer withIndex(final String indexName) {
        this.indexName = indexName;
        return this;
    }

    public void createIndex() {
        ElasticsearchConfiguration.createIndex(client, indexName);
    }

    private IndexRequest prepareRequest(final TikaDocument document, final TikaDocument parent, TikaDocument root, final int level) throws IOException {
        IndexRequest req = new IndexRequest(indexName).id(document.getId());
        Map<String, Object> jsonDocument = getDocumentMap(document);

        if (parent == null && isDuplicate(document.getId())) {
            IndexRequest indexRequest = new IndexRequest(indexName).id(Entity.HASHER.hash(document.getPath()));
            indexRequest.source(getDuplicateMap(document));
            indexRequest.setRefreshPolicy(esCfg.refreshPolicy);
            return indexRequest;
        }

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

    private boolean isDuplicate(String docId) throws IOException {
        GetRequest getRequest = new GetRequest(indexName, docId);
        getRequest.fetchSourceContext(new FetchSourceContext(false));
        getRequest.storedFields("_none_");
        return client.exists(getRequest, RequestOptions.DEFAULT);
    }

    Map<String, Object> getDocumentMap(TikaDocument document) throws IOException {
        Map<String, Object> jsonDocument = new HashMap<>();

        jsonDocument.put(esCfg.docTypeField, ES_DOCUMENT_TYPE);
        jsonDocument.put(esCfg.indexJoinField, new HashMap<String, String>() {{
            put("name", "Document");
        }});
        jsonDocument.put("path", document.getPath().toString());
        jsonDocument.put("dirname", ofNullable(document.getPath().getParent()).orElse(get("")).toString());
        jsonDocument.put("status", "INDEXED");
        jsonDocument.put("nerTags", new HashSet<>());
        jsonDocument.put("tags", new HashSet<>());
        jsonDocument.put("extractionDate", ISODateTimeFormat.dateTime().print(new Date().getTime()));
        jsonDocument.put("metadata", getMetadata(document));
        jsonDocument.put("contentType", ofNullable(document.getMetadata().get(CONTENT_TYPE)).orElse(DEFAULT_VALUE_UNKNOWN).split(";")[0]);
        jsonDocument.put("contentLength", Long.valueOf(ofNullable(document.getMetadata().get(CONTENT_LENGTH)).orElse("-1")));
        jsonDocument.put("contentEncoding", ofNullable(document.getMetadata().get(CONTENT_ENCODING)).orElse(DEFAULT_VALUE_UNKNOWN));

        String content = toString(document.getReader()).trim();
        jsonDocument.put("language", languageGuesser.guess(content));
        jsonDocument.put(ES_CONTENT_FIELD, content);
        return jsonDocument;
    }

    Map<String, Object> getDuplicateMap(TikaDocument document) {
        Map<String, Object> jsonDocument = new HashMap<>();

        jsonDocument.put(esCfg.docTypeField, ES_DUPLICATE_TYPE);
        jsonDocument.put("path", document.getPath().toString());
        jsonDocument.put("documentId", document.getId());

        return jsonDocument;
    }

    public ElasticsearchSpewer withRefresh(WriteRequest.RefreshPolicy refreshPolicy) {
        this.esCfg.withRefresh(refreshPolicy);
        return this;
    }
}
