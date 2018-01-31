package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.fest.assertions.Assertions;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Message.Field;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.parser.ParsingReader;
import org.icij.spewer.FieldNames;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.nio.file.Paths;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.index.query.QueryBuilders.multiMatchQuery;
import static org.fest.assertions.MapAssert.entry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElasticsearchSpewerTest {
    private static final String TEST_INDEX = "datashare-test";
	private static Client client;
    private Publisher publisher = Mockito.mock(Publisher.class);

	private ElasticsearchSpewer spewer = new ElasticsearchSpewer(client,
            new OptimaizeLanguageGuesser(), new FieldNames(), publisher, TEST_INDEX).withRefresh(IMMEDIATE);

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
		final TikaDocument document = factory.create(Paths.get("test-file.txt"));
		final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));

		spewer.write(document, reader);

    	GetResponse documentFields = client.get(new GetRequest(TEST_INDEX, "doc", document.getId())).get();
		assertTrue(documentFields.isExists());
		assertEquals(document.getId(), documentFields.getId());

        ArgumentCaptor<Message> argument = ArgumentCaptor.forClass(Message.class);
		Mockito.verify(publisher).publish(Matchers.eq(Channel.NLP), argument.capture());
		Assertions.assertThat(argument.getValue().content).includes(entry(Field.DOC_ID, document.getId()));
	}

    @Test
    public void test_metadata() throws Exception {
        String path = getClass().getResource("/docs/doc.txt").getPath();
        final TikaDocument document = factory.create(path);

        spewer.write(document, new Extractor().extract(document));

        GetResponse documentFields = client.get(new GetRequest(TEST_INDEX, "doc", document.getId())).get();
        Assertions.assertThat(documentFields.getSourceAsMap()).includes(
                entry("contentEncoding", "ISO-8859-1"),
                entry("contentType", "text/plain; charset=ISO-8859-1"),
                entry("contentLength", "45"),
                entry("path", path)
        );
    }

    @Test
    public void test_embedded_document() throws Exception {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        final TikaDocument document = factory.create(path);
        Reader reader = new Extractor().extract(document);

        spewer.write(document, reader);

        GetResponse documentFields = client.get(new GetRequest(TEST_INDEX, "doc", document.getId())).get();
        assertTrue(documentFields.isExists());
        SearchResponse response = client.prepareSearch(TEST_INDEX).setQuery(
                multiMatchQuery("simple.tiff", "content")) .get();
        Assertions.assertThat(response.getHits().totalHits).isGreaterThan(0);
        //assertThat(response.getHits().getAt(0).getId()).endsWith("embedded.pdf");
    }

    @Test
    public void test_language() throws Exception {
        final TikaDocument document = factory.create(getClass().getResource("/docs/doc.txt").getPath());
        final TikaDocument document_fr = factory.create(getClass().getResource("/docs/doc-fr.txt").getPath());

        spewer.write(document, new Extractor().extract(document));
        spewer.write(document_fr, new Extractor().extract(document_fr));

        GetResponse documentFields = client.get(new GetRequest(TEST_INDEX, "doc", document.getId())).get();
        GetResponse documentFields_fr = client.get(new GetRequest(TEST_INDEX, "doc", document_fr.getId())).get();
        Assertions.assertThat(documentFields.getSourceAsMap()).includes(entry("language", "ENGLISH"));
        Assertions.assertThat(documentFields_fr.getSourceAsMap()).includes(entry("language", "FRENCH"));
    }
}
