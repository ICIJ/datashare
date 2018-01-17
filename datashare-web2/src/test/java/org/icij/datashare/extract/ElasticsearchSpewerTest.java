package org.icij.datashare.extract;

import org.apache.tika.metadata.Metadata;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.icij.extract.document.Document;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.parser.ParsingReader;
import org.icij.spewer.FieldNames;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElasticsearchSpewerTest {
    private static final String TEST_INDEX = "datashare-test";
	private static Client client;
	private ElasticsearchSpewer spewer = new ElasticsearchSpewer(client, new FieldNames(), TEST_INDEX);
	private final DocumentFactory factory = new DocumentFactory().withIdentifier(new PathIdentifier());

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
	public void testSimpleWrite() throws Exception {
		final Document document = factory.create(Paths.get("test-file.txt"));
		final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));

		spewer.write(document, reader);

    	GetResponse documentFields = client.get(new GetRequest(TEST_INDEX, "doc", document.getId())).get();
		assertTrue(documentFields.isExists());
		assertEquals(document.getId(), documentFields.getId());
	}

	@Test
	public void testEmbeddedDocumentsWrite() throws Exception {
		final Document document = factory.create(Paths.get("test-file.txt"));
		document.addEmbed("embedded-key", new PathIdentifier(), Paths.get("embedded_file.txt"), new Metadata());
		final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test embedded document".getBytes()));

		spewer.write(document, reader);

        GetRequest getRequest = new GetRequest(TEST_INDEX, "doc", "embedded_file.txt");
        getRequest.routing("test-file.txt");
        GetResponse documentFields = client.get(getRequest).get();
        assertTrue(documentFields.isExists());
		assertEquals("test-file.txt", ((Map)documentFields.getSourceAsMap().get("join")).get("parent"));
		assertEquals("document", ((Map)documentFields.getSourceAsMap().get("join")).get("name"));
	}
}
