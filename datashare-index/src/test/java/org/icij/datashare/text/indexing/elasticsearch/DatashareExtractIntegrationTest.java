package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.spewer.FieldNames;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Objects;

import static java.nio.file.Paths.get;
import static org.junit.Assume.assumeTrue;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Project.project;

public class DatashareExtractIntegrationTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    private final ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer, new MemoryDocumentCollectionFactory<>(), l -> ENGLISH,
            new FieldNames(), new PropertiesProvider(new HashMap<>(){{
                put("defaultProject", es.getIndexName());
    }}));

    public DatashareExtractIntegrationTest() throws IOException {}

    @Test
    public void test_spew_and_read_index() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/doc.txt")).getPath());
        TikaDocument tikaDocument = createExtractor().extract(path);

        spewer.write(tikaDocument);
        Document doc = indexer.get(es.getIndexName(), tikaDocument.getId());

        assertThat(doc.getProject()).isEqualTo(project(es.getIndexName()));
        assertThat(doc.getId()).isEqualTo(tikaDocument.getId());
        assertThat(doc.getContent()).isEqualTo("This is a document to be parsed by datashare.");
        assertThat(doc.getLanguage()).isEqualTo(ENGLISH);
        assertThat(doc.getContentLength()).isEqualTo(45);
        assertThat(doc.getDirname()).contains(get("docs"));
        assertThat(doc.getStatus()).isEqualTo(Document.Status.INDEXED);
        assertThat(doc.getPath()).contains(get("doc.txt"));
        assertThat(doc.getContentEncoding()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(doc.getContentType()).isEqualTo("text/plain");
        assertThat(doc.getExtractionLevel()).isEqualTo((short) 0);
        assertThat(doc.getMetadata()).hasSize(11);
        assertThat(doc.getOcrParser()).isNull();
        assertThat(doc.getParentDocument()).isNull();
        assertThat(doc.getRootDocument()).isEqualTo(doc.getId());
        assertThat(doc.getCreationDate()).isNull();
    }

    @Test
    public void test_spew_and_read_csv() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/sample.csv")).getPath());
        TikaDocument tikaDocument = createExtractor().extract(path);

        spewer.write(tikaDocument);
        Document doc = indexer.get(es.getIndexName(), tikaDocument.getId());

        assertThat(doc).isNotNull();
        assertThat(doc.getContent()).contains("Alice").contains("Bob").contains("Paris").contains("London");
    }

    @Test
    public void test_spew_and_read_embedded_doc() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        TikaDocument tikaDocument = createExtractor().extract(path);

        spewer.write(tikaDocument);
        Document doc = indexer.get(es.getIndexName(), tikaDocument.getEmbeds().get(0).getId(), tikaDocument.getId());

        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isNotEqualTo(doc.getRootDocument());
        assertThat(doc.getRootDocument()).isEqualTo(tikaDocument.getId());
        assertThat(doc.getOcrParser()).isEqualTo("org.apache.tika.parser.ocr.TesseractOCRParser");
        assertThat(doc.getCreationDate()).isNotNull();
        assertThat(new SimpleDateFormat("HH:mm:ss").format(doc.getCreationDate())).isEqualTo("23:22:36");
    }

    // End-to-end proof that a real OST-2013, extracted through the real Extractor (extract 9.10.1)
    // and spewed to Elasticsearch, lands the typed recoveryStatus field on documents. Opt-in: runs
    // only against a real OST supplied via -Dost.test.file. OCR is disabled because the recovery
    // markers are set during the PST walk, independent of OCR, and OCR would make the run very slow.
    @Test
    public void test_ost_recovery_status_indexed_end_to_end() throws Exception {
        String ostPath = System.getProperty("ost.test.file");
        assumeTrue(ostPath != null);

        Extractor extractor = createExtractor();
        // Use the streaming extract(path, spewer) overload — the exact path DocumentConsumer/IndexTask
        // use in production, which digests and spews each embed inline.
        extractor.extract(get(ostPath), spewer);

        // recoveryStatus is an aggregatable keyword (a terms agg would error on a text field). The
        // bucket set must contain a per-attachment child value (RECOVERED) and a PST-root rollup
        // value (COMPLETE/PARTIAL/LOSSY), proving both halves reach the index end-to-end.
        SearchResponse<ObjectNode> resp = es.client.search(s -> s
                .index(es.getIndexName()).size(0)
                .aggregations("rs", a -> a.terms(t -> t.field("recoveryStatus"))), ObjectNode.class);
        java.util.Set<String> statuses = resp.aggregations().get("rs").sterms().buckets().array().stream()
                .map(b -> b.key().stringValue()).collect(java.util.stream.Collectors.toSet());
        assertThat(statuses).contains("RECOVERED");
        assertThat(statuses.stream().anyMatch(s -> s.equals("COMPLETE") || s.equals("PARTIAL") || s.equals("LOSSY"))).isTrue();
    }

    Extractor createExtractor() {
        Extractor extractor = new Extractor(new DocumentFactory().withIdentifier(new DigestIdentifier("SHA-384", Charset.defaultCharset())));
        extractor.setDigester(new UpdatableDigester("test", Entity.DEFAULT_DIGESTER.toString()));
        return extractor;
    }
}
