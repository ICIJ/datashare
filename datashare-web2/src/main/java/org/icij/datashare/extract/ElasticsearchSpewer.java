package org.icij.datashare.extract;

import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.icij.datashare.PropertiesProvider;
import org.icij.extract.document.Document;
import org.icij.extract.document.EmbeddedDocument;
import org.icij.spewer.FieldNames;
import org.icij.spewer.MetadataTransformer;
import org.icij.spewer.Spewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.lang.System.currentTimeMillis;

public class ElasticsearchSpewer extends Spewer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewer.class);
    private static final String INDEX_ADDRESS_PROP = "indexAddress";
    private static final String CLUSTER_PROP = "clusterName";
    private static final String DEFAULT_ADDRESS = "localhost:9300";

    private final Client client;
    private final String index_name;
    private static final String ES_CLUSTER_NAME = "datashare";
    private static final String ES_INDEX_NAME = "datashare";
    private static final String ES_INDEX_TYPE = "doc";
    private static final String ES_DOC_TYPE_FIELD = "type";
    private static final String ES_JOIN_FIELD = "join";
    private static final String ES_CONTENT_FIELD = "content";

    @Inject
    public ElasticsearchSpewer(final PropertiesProvider propertiesProvider) throws IOException {
        super(new FieldNames());
        this.client = createESClient(propertiesProvider);
        this.index_name = ES_INDEX_NAME;
    }

    public ElasticsearchSpewer(final Client client, final FieldNames fields, final String index_name) {
        super(fields);
        this.client = client;
        this.index_name = index_name;
    }

    public static Client createESClient(final PropertiesProvider propertiesProvider) throws UnknownHostException {
        System.setProperty("es.set.netty.runtime.available.processors", "false");

        String indexAddress = propertiesProvider.getIfPresent(INDEX_ADDRESS_PROP).orElse(DEFAULT_ADDRESS);
        InetAddress esAddress = InetAddress.getByName(indexAddress.split(":")[0]);
        int esPort = Integer.parseInt(indexAddress.split(":")[1]);
        String clusterName = propertiesProvider.getIfPresent(CLUSTER_PROP).orElse(ES_CLUSTER_NAME);

        logger.info("building elasticsearch client on {} and cluster {}", indexAddress, clusterName);

        Settings settings = Settings.builder().put("cluster.name", clusterName).build();
        return new PreBuiltTransportClient(settings).addTransportAddress(
                new TransportAddress(esAddress, esPort));
    }

    @Override
    public void write(final Document document, final Reader reader) throws IOException {
        indexDocument(document, reader, null, 0);
        for (EmbeddedDocument childDocument : document.getEmbeds()) {
            writeTree(childDocument, document, 1);
        }
    }

    private void writeTree(final Document doc, final Document parent, final int level)
            throws IOException {
        doc.clearReader();
        try (final Reader reader = doc.getReader()) {
            indexDocument(doc, reader, parent, level);
        }

        for (EmbeddedDocument child : doc.getEmbeds()) {
            writeTree(child, doc, level + 1);
        }
    }

    private void indexDocument(Document document, Reader reader,
                               final Document parent, final int level) throws IOException {
        final IndexRequest req = prepareRequest(document, reader, parent, level);
        try {
            long before = currentTimeMillis();
            client.index(req).get();
            logger.info("{} added to elasticsearch in {}ms: \"{}\".", parent == null ? "Document" : "Child",
                    currentTimeMillis() - before, document);
        } catch (InterruptedException | ExecutionException e) {
            logger.warn("interrupted execution of request", e);
        }
    }

    private IndexRequest prepareRequest(final Document document, final Reader reader,
                                        final Document parent, final int level) throws IOException {
        IndexRequest req = new IndexRequest(index_name, ES_INDEX_TYPE, document.getId());
        Map<String, Object> jsonDocument = new HashMap<>();
        new MetadataTransformer(document.getMetadata(), fields).transform(
                new MapValueConsumer(jsonDocument), new MapValuesConsumer(jsonDocument));

        jsonDocument.put(ES_DOC_TYPE_FIELD, "document");
        if (parent != null) {
            jsonDocument.put(ES_JOIN_FIELD, new HashMap<String, String>() {{
                put("name", "document");
                put("parent", parent.getId());
            }});
            req.routing(parent.getId());
        }
        jsonDocument.put(fields.forLevel(), level);

        if (reader != null) {
            jsonDocument.put(ES_CONTENT_FIELD, toString(reader));
        }
        req = req.source(jsonDocument);
        return req;
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

    @Override
    public void writeMetadata(Document document) throws IOException { throw new UnsupportedOperationException();}

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
}
