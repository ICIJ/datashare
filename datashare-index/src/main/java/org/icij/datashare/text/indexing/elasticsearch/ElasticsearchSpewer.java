package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Duplicate;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.icij.spewer.Spewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static java.lang.System.currentTimeMillis;
import static java.util.Optional.ofNullable;
import static org.apache.tika.metadata.HttpHeaders.CONTENT_ENCODING;
import static org.apache.tika.metadata.HttpHeaders.CONTENT_LENGTH;
import static org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE;
import static org.icij.datashare.text.Hasher.shorten;

public class ElasticsearchSpewer extends Spewer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewer.class);
    public static final String DEFAULT_VALUE_UNKNOWN = "unknown";

    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;
    private final int maxContentLength;
    private final Hasher digestAlgorithm;
    private final DocumentQueue<String> nlpQueue;
    private String indexName;

    @Inject
    public ElasticsearchSpewer(final Indexer indexer, DocumentCollectionFactory<String> nlpQueueFactory, LanguageGuesser languageGuesser, final FieldNames fields,
                               final PropertiesProvider propertiesProvider) {
        super(fields);
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
        this.maxContentLength = getMaxContentLength(propertiesProvider);
        this.digestAlgorithm = getDigestAlgorithm(propertiesProvider);
        this.nlpQueue = nlpQueueFactory.createQueue(propertiesProvider, new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        logger.info("spewer defined with {}", indexer);
    }

    @Override
    protected void writeDocument(TikaDocument doc, TikaDocument parent, TikaDocument root, int level) throws IOException {
        if (root != null && root.isDuplicate()) {
            logger.debug("root document {} is duplicate, skipping {}", root.getId(), doc.getId());
            return;
        }
        long before = currentTimeMillis();
        if (parent == null && isDuplicate(doc.getId())) {
            doc.setDuplicate(true);
            indexer.add(indexName, new Duplicate(doc.getPath(), doc.getId(), digestAlgorithm));
        } else {
            Document document = getDocument(doc, root, parent, (short) level);
            indexer.add(indexName, document);
            nlpQueue.add(document.getId());
        }
        logger.info("{} {} added to elasticsearch in {}ms: {}", parent == null ? "Document" : "Child",
                shorten(doc.getId(), 4), currentTimeMillis() - before, doc);
    }

    public ElasticsearchSpewer withIndex(final String indexName) {
        this.indexName = indexName;
        return this;
    }

    public void createIndex() throws IOException {
        indexer.createIndex(indexName);
    }

    private boolean isDuplicate(String docId) throws IOException {
        return indexer.exists(indexName, docId);
    }

    Document getDocument(TikaDocument document, TikaDocument root, TikaDocument parent, short level) throws IOException {
        Charset charset = Charset.isSupported(ofNullable(document.getMetadata().get(CONTENT_ENCODING)).orElse(DEFAULT_VALUE_UNKNOWN)) ?
                Charset.forName(document.getMetadata().get(CONTENT_ENCODING)) : StandardCharsets.US_ASCII;
        DocumentBuilder builder = DocumentBuilder.createDoc(document.getId())
                .with(document.getPath())
                .with(Document.Status.INDEXED)
                .with(getMetadata(document))
                .ofContentType(ofNullable(document.getMetadata().get(CONTENT_TYPE)).orElse(DEFAULT_VALUE_UNKNOWN).split(";")[0])
                .withContentLength(Long.valueOf(ofNullable(document.getMetadata().get(CONTENT_LENGTH)).orElse("-1")))
                .with(charset)
                .withExtractionLevel(level);

        String content = toString(document.getReader()).trim();
        if (maxContentLength != -1 && content.length() > maxContentLength) {
            logger.warn("document id {} extracted text will be truncated to {} bytes", document.getId(), maxContentLength);
            content = content.substring(0, maxContentLength).trim();
        }
        if (document.getLanguage() == null) {
            builder.with(languageGuesser.guess(content));
        } else  {
            builder.with(Language.parse(document.getLanguage()));
        }
        builder.with(content);

        if (parent != null) {
            builder.withParentId(parent.getId());
            builder.withRootId(root.getId());
        }
        return builder.build();
    }

    int getMaxContentLength(PropertiesProvider propertiesProvider) {
        return (int) Math.min(HumanReadableSize.parse(propertiesProvider.get("maxContentLength").orElse("-1")), Integer.MAX_VALUE);
    }

    private Hasher getDigestAlgorithm(PropertiesProvider propertiesProvider) {
        return Hasher.parse(propertiesProvider.get("digestAlgorithm")
                .orElse(Entity.DEFAULT_DIGESTER.name())).orElse(Entity.DEFAULT_DIGESTER);
    }

    @Override
    public void close() throws Exception {
        nlpQueue.close();
    }
}
