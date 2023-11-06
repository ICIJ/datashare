package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.search.SourceConfigParam;
import com.google.inject.Inject;
import org.apache.tika.metadata.DublinCore;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.document.TikaDocument;
import org.icij.spewer.FieldNames;
import org.icij.spewer.Spewer;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static co.elastic.clients.elasticsearch.core.ExistsRequest.*;
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

    private final ElasticsearchClient client;
    private final ElasticsearchConfiguration esCfg;
    private final Publisher publisher;
    private final LanguageGuesser languageGuesser;
    private final int maxContentLength;
    private final Hasher digestAlgorithm;
    private String indexName;

    @Inject
    public ElasticsearchSpewer(final ElasticsearchClient client, LanguageGuesser languageGuesser, final FieldNames fields,
                               Publisher publisher, final PropertiesProvider propertiesProvider) {
        super(fields);
        this.client = client;
        this.languageGuesser = languageGuesser;
        this.publisher = publisher;
        this.esCfg = new ElasticsearchConfiguration(propertiesProvider);
        this.maxContentLength = getMaxContentLength(propertiesProvider);
        this.digestAlgorithm = getDigestAlgorithm(propertiesProvider);
        logger.info("spewer defined with {}", esCfg);
    }

    @Override
    protected void writeDocument(TikaDocument doc, TikaDocument parent, TikaDocument root, int level) throws IOException {
        final IndexRequest req = prepareRequest(doc, parent, root, level);
        long before = currentTimeMillis();
        IndexResponse indexResponse = client.index(req);
        logger.info("{} {} added to elasticsearch in {}ms: {}", parent == null ? "Document" : "Child",
                shorten(indexResponse.id(), 4), currentTimeMillis() - before, doc);
        synchronized (publisher) { // jedis instance is not thread safe and Spewer is shared in DocumentConsumer threads
            publisher.publish(NLP, new Message(EXTRACT_NLP)
                    .add(Message.Field.INDEX_NAME, indexName)
                    .add(Message.Field.DOC_ID, indexResponse.id())
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
        IndexRequest.Builder req = new IndexRequest.Builder().index(indexName).id(document.getId());
        Map<String, Object> jsonDocument = getDocumentMap(document);

        if (parent == null && isDuplicate(document.getId())) {
            IndexRequest.Builder indexRequest = new IndexRequest.Builder().index(indexName).id(digestAlgorithm.hash(document.getPath()));
            indexRequest.document(getDuplicateMap(document));
            indexRequest.refresh(esCfg.refreshPolicy);
            return indexRequest.build();
        }

        if (parent != null) {
            jsonDocument.put(DEFAULT_PARENT_DOC_FIELD, parent.getId());
            jsonDocument.put("rootDocument", root.getId());
            req.routing(root.getId());
        }
        jsonDocument.put("extractionLevel", level);
        req = req.document(jsonDocument);
        req.refresh(esCfg.refreshPolicy);
        return req.build();
    }

    private boolean isDuplicate(String docId) throws IOException {
        Builder getRequest = new Builder().index(indexName).id(docId);
        getRequest.source(SourceConfigParam.of(scp -> scp.fetch(false)));
        getRequest.storedFields("_none_");
        return client.exists(getRequest.build()).value();
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
        jsonDocument.put("title", ofNullable(getTitle(document.getMetadata())).orElse(DEFAULT_VALUE_UNKNOWN));
        jsonDocument.put("titleNorm", ofNullable(normalize(getTitle(document.getMetadata()))).orElse(DEFAULT_VALUE_UNKNOWN));

        String content = toString(document.getReader()).trim();
        if (maxContentLength != -1 && content.length() > maxContentLength) {
            logger.warn("document id {} extracted text will be truncated to {} bytes", document.getId(), maxContentLength);
            content = content.substring(0, maxContentLength).trim();
        }
        if (document.getLanguage() == null) {
            jsonDocument.put("language", languageGuesser.guess(content));
        } else  {
            jsonDocument.put("language", Language.parse(document.getLanguage()).toString());
        }
        jsonDocument.put("contentTextLength", content.length());
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

    protected boolean isEmail(Metadata metadata) {
        String contentType = ofNullable(metadata.get(CONTENT_TYPE)).orElse(DEFAULT_VALUE_UNKNOWN);
        return contentType.startsWith("message/") || contentType.equals("application/vnd.ms-outlook");
    }

    protected boolean isTweet(Metadata metadata) {
        return ofNullable(metadata.get(CONTENT_TYPE)).orElse(DEFAULT_VALUE_UNKNOWN).equals("application/json; twint");
    }

    protected String getTitle(Metadata metadata) {
        if (isEmail(metadata)) {
            if (metadata.get(DublinCore.SUBJECT) != null && !metadata.get(DublinCore.SUBJECT).isEmpty()) {
                return metadata.get(DublinCore.SUBJECT);
            } else if (metadata.get(DublinCore.TITLE) != null && !metadata.get(DublinCore.TITLE).isEmpty()) {
                return metadata.get(DublinCore.TITLE);
            }
        }
        if (isTweet(metadata)) {
            if (metadata.get(DublinCore.TITLE) != null && !metadata.get(DublinCore.TITLE).isEmpty()) {
                return metadata.get(DublinCore.TITLE);
            }
        }
        return metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
    }

    public ElasticsearchSpewer withRefresh(Refresh refreshPolicy) {
        this.esCfg.withRefresh(refreshPolicy);
        return this;
    }

    public static String normalize(String input) {
        // Normalize special characters to their ASCII equivalents
        // and convert to lowercase
        return Normalizer.normalize(input, Normalizer.Form.NFD).toLowerCase();
    }

    int getMaxContentLength(PropertiesProvider propertiesProvider) {
        return (int) Math.min(HumanReadableSize.parse(propertiesProvider.get("maxContentLength").orElse("-1")), Integer.MAX_VALUE);
    }

    private Hasher getDigestAlgorithm(PropertiesProvider propertiesProvider) {
        return Hasher.parse(propertiesProvider.get("digestAlgorithm")
                .orElse(Entity.DEFAULT_DIGESTER.name())).orElse(Entity.DEFAULT_DIGESTER);
    }
}
