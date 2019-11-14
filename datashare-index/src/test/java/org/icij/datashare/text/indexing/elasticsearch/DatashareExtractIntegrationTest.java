package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.mockito.Mockito.mock;

public class DatashareExtractIntegrationTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

	private ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client, new OptimaizeLanguageGuesser(),
            new FieldNames(), mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex("test-datashare");
	private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider());

    public DatashareExtractIntegrationTest() throws IOException {}

    @Test
    public void test_spew_and_read_index() throws Exception {
        String path = getClass().getResource("/docs/doc.txt").getPath();
        final TikaDocument tikaDocument = new DocumentFactory().configure(new Options<>()).create(path);

        spewer.write(tikaDocument, new Extractor().extract(tikaDocument));
        Document doc = indexer.get(TEST_INDEX, tikaDocument.getId());

        assertThat(doc.getId()).isEqualTo(tikaDocument.getId());
        assertThat(doc.getContent()).isEqualTo("This is a document to be parsed by datashare.");
        assertThat(doc.getLanguage()).isEqualTo(ENGLISH);
        assertThat(doc.getContentLength()).isEqualTo(45);
        assertThat(doc.getDirname()).contains(get("docs"));
        assertThat(doc.getPath()).contains(get("doc.txt"));
        assertThat(doc.getContentEncoding()).isEqualTo(Charset.forName("iso-8859-1"));
        assertThat(doc.getContentType()).isEqualTo("text/plain");
        assertThat(doc.getExtractionLevel()).isEqualTo((short) 0);
        assertThat(doc.getMetadata()).hasSize(6);
        assertThat(doc.getParentDocument()).isNull();
        assertThat(doc.getRootDocument()).isEqualTo(doc.getId());
        assertThat(doc.getCreationDate()).isNull();
    }

    @Test
    public void test_spew_and_read_embedded_doc() throws Exception {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        final TikaDocument tikaDocument = new DocumentFactory().configure(new Options<>()).create(path);

        spewer.write(tikaDocument, new Extractor().extract(tikaDocument));
        Document doc = indexer.get(TEST_INDEX, tikaDocument.getEmbeds().get(0).getId(), tikaDocument.getId());

        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isNotEqualTo(doc.getRootDocument());
        assertThat(doc.getRootDocument()).isEqualTo(tikaDocument.getId());
        assertThat(doc.getCreationDate()).isNotNull();
        assertThat(new SimpleDateFormat("HH:mm:ss").format(doc.getCreationDate())).isEqualTo("23:22:36");
    }
}
