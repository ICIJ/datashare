package org.icij.datashare.extract;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.icij.datashare.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.Document;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.parser.ParsingReader;
import org.icij.spewer.FieldNames;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.nio.file.Paths;
import java.util.Map;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.index.query.QueryBuilders.multiMatchQuery;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElasticsearchSpewerTest {
    private static final String TEST_INDEX = "datashare-test";
	private static Client client;
	private ElasticsearchSpewer spewer = new ElasticsearchSpewer(client,
            new OptimaizeLanguageGuesser(), new FieldNames(), TEST_INDEX).withRefresh(IMMEDIATE);
	private final DocumentFactory factory = new DocumentFactory().withIdentifier(new PathIdentifier());

    public ElasticsearchSpewerTest() throws IOException {}

    @BeforeClass
	public static void setUpClass() throws Exception {
		System.setProperty("es.set.netty.runtime.available.processors", "false");
		Settings settings = Settings.builder().put("cluster.name", "docker-cluster").build();
		client = new PreBuiltTransportClient(settings).addTransportAddress(
				new TransportAddress(InetAddress.getByName("elasticsearch"), 9300));
		client.admin().indices().create(new CreateIndexRequest(TEST_INDEX));
	}

    @AfterClass
    public static void tearDown() throws Exception {
        client.admin().indices().delete(new DeleteIndexRequest(TEST_INDEX));
        client.close();
    }

    @Test
	public void test_simple_write() throws Exception {
		final Document document = factory.create(Paths.get("test-file.txt"));
		final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));

		spewer.write(document, reader);

    	GetResponse documentFields = client.get(new GetRequest(TEST_INDEX, "doc", document.getId())).get();
		assertTrue(documentFields.isExists());
		assertEquals(document.getId(), documentFields.getId());
	}

    @Test
    public void test_metadata() throws Exception {
        String path = getClass().getResource("/docs/doc.txt").getPath();
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        final Document document = factory.create(path);
        new Extractor().extract(document);

        Map<String, Object> map = spewer.getMap(document, reader);
        assertThat(map).includes(
                entry("content_encoding", "ISO-8859-1"),
                entry("content_type", "text/plain; charset=ISO-8859-1"),
                entry("content_length", "45"),
                entry("path", path)
        );
    }

    @Test
    public void test_embedded_document() throws Exception {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        final Document document = factory.create(path);
        Reader reader = new Extractor().extract(document);

        spewer.write(document, reader);

        GetResponse documentFields = client.get(new GetRequest(TEST_INDEX, "doc", document.getId())).get();
        assertTrue(documentFields.isExists());
        SearchResponse response = client.prepareSearch(TEST_INDEX).setQuery(
                multiMatchQuery("heavy metal", "content")) .get();
        assertThat(response.getHits().totalHits).isGreaterThan(0);
        //assertThat(response.getHits().getAt(0).getId()).endsWith("embedded.pdf");
    }

    @Test
    public void test_language() throws Exception {
        final Document document = factory.create(getClass().getResource("/docs/doc.txt").getPath());
        final Document document_fr = factory.create(getClass().getResource("/docs/doc-fr.txt").getPath());
        Reader reader = new Extractor().extract(document);
        Reader reader_fr = new Extractor().extract(document_fr);

        Map<String, Object> map = spewer.getMap(document, reader);
        Map<String, Object> map_fr = spewer.getMap(document_fr, reader_fr);

        assertThat(map).includes(entry("language", "en"));
        assertThat(map_fr).includes(entry("language", "fr"));
    }
}
