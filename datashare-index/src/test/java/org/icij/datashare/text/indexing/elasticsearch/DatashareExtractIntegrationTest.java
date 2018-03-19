package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.mockito.Mockito.mock;

public class DatashareExtractIntegrationTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

	private ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
            new OptimaizeLanguageGuesser(), new FieldNames(), mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE);
	private ElasticsearchIndexer indexer = new ElasticsearchIndexer(new PropertiesProvider());

    public DatashareExtractIntegrationTest() throws IOException {}

    @Test
    public void test_spew_and_read_index() throws Exception {
        String path = getClass().getResource("/docs/doc.txt").getPath();
        final TikaDocument tikaDocument = new DocumentFactory().withIdentifier(new PathIdentifier()).create(path);

        spewer.write(tikaDocument, new Extractor().extract(tikaDocument));
        Document doc = indexer.get(tikaDocument.getId());

        assertThat(doc.getId()).isEqualTo(tikaDocument.getId());
        assertThat(doc.getContent()).isEqualTo("This is a document to be parsed by datashare.");
        assertThat(doc.getLanguage()).isEqualTo(ENGLISH);
        assertThat(doc.getContentLength()).isEqualTo(45);
        assertThat(doc.getPath()).contains(get("doc.txt"));
        assertThat(doc.getContentEncoding()).isEqualTo(Charset.forName("iso-8859-1"));
        assertThat(doc.getContentType()).isEqualTo("text/plain");
        assertThat(doc.getExtractionLevel()).isEqualTo(0);
        assertThat(doc.getMetadata()).hasSize(6);
    }
}
