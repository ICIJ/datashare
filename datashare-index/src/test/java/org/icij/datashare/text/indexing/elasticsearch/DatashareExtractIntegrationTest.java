package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.mockito.Mockito.mock;

public class DatashareExtractIntegrationTest {
    private static final String TEST_INDEX = "datashare-test";
	private static Client client;

	private ElasticsearchSpewer spewer = new ElasticsearchSpewer(client,
            new OptimaizeLanguageGuesser(), new FieldNames(), mock(Publisher.class), TEST_INDEX).withRefresh(IMMEDIATE);

	private ElasticsearchIndexer indexer = new ElasticsearchIndexer(new PropertiesProvider());
    public DatashareExtractIntegrationTest() throws IOException {}

    @BeforeClass
	public static void setUpClass() throws Exception {
		System.setProperty("es.set.netty.runtime.available.processors", "false");
		Settings settings = Settings.builder().put("cluster.name", "docker-cluster").build();
		client = new PreBuiltTransportClient(settings).addTransportAddress(
				new TransportAddress(InetAddress.getByName("elasticsearch"), 9300));
		client.admin().indices().create(new CreateIndexRequest(TEST_INDEX));
	}

    @Test
    public void test_spew_and_read_index() throws Exception {
        String path = getClass().getResource("/docs/doc.txt").getPath();
        final TikaDocument tikaDocument = new DocumentFactory().withIdentifier(new PathIdentifier()).create(path);

        spewer.write(tikaDocument, new Extractor().extract(tikaDocument));
        Document doc = indexer.get(tikaDocument.getId());

        assertThat(doc.getId()).isEqualTo(tikaDocument.getId());
        assertThat(doc.getContent()).isEqualTo("This is a document to be parsed by datashare.\n");
        assertThat(doc.getLanguage()).isEqualTo(ENGLISH);
        assertThat(doc.getContentLength()).isEqualTo(45);
        assertThat(doc.getPath()).contains(get("doc.txt"));
        assertThat(doc.getContentEncoding()).isEqualTo(Charset.forName("iso-8859-1"));
        assertThat(doc.getContentType()).isEqualTo("text/plain; charset=ISO-8859-1");
        assertThat(doc.getExtractionLevel()).isEqualTo(0);
        assertThat(doc.getMetadata()).hasSize(6);
    }
}
