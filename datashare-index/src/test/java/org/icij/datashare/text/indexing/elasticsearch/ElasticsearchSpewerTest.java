package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParsingReader;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Message.Field;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class ElasticsearchSpewerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private Publisher publisher = Mockito.mock(Publisher.class);

    private ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
            new OptimaizeLanguageGuesser(), new FieldNames(), publisher, new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex("test-datashare");

    public ElasticsearchSpewerTest() throws IOException {}

    @Test
    public void test_simple_write() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("test-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, "doc", document.getId()));
        assertThat(documentFields.isExists()).isTrue();
        assertThat(documentFields.getId()).isEqualTo(document.getId());
        assertEquals(new HashMap<String, String>() {{
            put("name", "Document");
        }}, documentFields.getSourceAsMap().get("join"));

        ArgumentCaptor<Message> argument = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(eq(Channel.NLP), argument.capture());
        assertThat(argument.getValue().content).includes(entry(Field.DOC_ID, document.getId()));
    }

    @Test
    public void test_metadata() throws Exception {
        Path path = get(getClass().getResource("/docs/a/b/c/doc.txt").getPath());
        TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, "doc", document.getId()));
        assertThat(documentFields.getSourceAsMap()).includes(
                entry("contentEncoding", "ISO-8859-1"),
                entry("contentType", "text/plain"),
                entry("nerTags", new ArrayList<>()),
                entry("contentLength", 45),
                entry("status", "INDEXED"),
                entry("path", path.toString()),
                entry("dirname", path.getParent().toString())
        );
    }

    @Test
    public void test_embedded_document() throws Exception {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        final TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, "doc", document.getId()));
        assertTrue(documentFields.isExists());

        SearchRequest searchRequest = new SearchRequest();
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(QueryBuilders.multiMatchQuery("simple.tiff", "content"));
        searchRequest.source(searchSourceBuilder);
        SearchResponse response = es.client.search(searchRequest);
        assertThat(response.getHits().totalHits).isGreaterThan(0);
        //assertThat(response.getHits().getAt(0).getId()).endsWith("embedded.pdf");

        verify(publisher, times(2)).publish(eq(Channel.NLP), any(Message.class));
    }

    @Test
    public void test_extract_id_should_be_equal_to_datashare_id() throws IOException {
        DocumentFactory tikaFactory = new DocumentFactory().configure(Options.from(new HashMap<String, String>() {{
            put("idDigestMethod", Document.HASHER.toString());
        }}));
        Extractor extractor = new Extractor(tikaFactory);
        extractor.setDigester(new UpdatableDigester("project", Document.HASHER.toString()));

        final TikaDocument extractDocument = extractor.extract(get(getClass().getResource("/docs/embedded_doc.eml").getPath()));

        Document document = new Document(Project.project("project"), get(getClass().getResource("/docs/embedded_doc.eml").getPath()),
                "This is a document to be parsed by datashare.",
                Language.FRENCH, Charset.defaultCharset(), "text/plain", convert(extractDocument.getMetadata()),
                Document.Status.INDEXED, 45L);

        assertThat(document.getId()).isEqualTo(extractDocument.getId());
    }

    @Test
    public void test_language() throws Exception {
        Extractor extractor = new Extractor();
        final TikaDocument document = extractor.extract(get(getClass().getResource("/docs/doc.txt").getPath()));
        final TikaDocument document_fr = extractor.extract(get(getClass().getResource("/docs/doc-fr.txt").getPath()));

        spewer.write(document);
        spewer.write(document_fr);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, "doc", document.getId()));
        GetResponse documentFields_fr = es.client.get(new GetRequest(TEST_INDEX, "doc", document_fr.getId()));
        assertThat(documentFields.getSourceAsMap()).includes(entry("language", "ENGLISH"));
        assertThat(documentFields_fr.getSourceAsMap()).includes(entry("language", "FRENCH"));
    }

    private Map<String, Object> convert(Metadata metadata) {
        Map<String, Object> map = new HashMap<>();
        for (String name: metadata.names()) {
            map.put(name, metadata.get(name));
        }
        return map;
    }
}
