package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import org.apache.tika.metadata.DublinCore;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParsingReader;
import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.*;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.utils.JsonUtils.nodeToMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElasticsearchSpewerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final MemoryDocumentCollectionFactory<String> documentQueueFactory = new MemoryDocumentCollectionFactory<>();
    private final ElasticsearchSpewer spewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True),
            documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(new HashMap<>() {{
                put("defaultProject", "test-datashare");
    }}));

    @Test
    public void test_simple_write() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("test-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(documentFields.found()).isTrue();
        assertThat(documentFields.id()).isEqualTo(document.getId());
        assertEquals(new HashMap<String, String>() {{
            put("name", "Document");
        }}, nodeToMap(documentFields.source()).get("join"));
        assertThat(documentQueueFactory.createQueue("extract:queue:nlp", String.class).size()).isEqualTo(1);
    }

    @Test
    public void test_write_with_correct_iso1_language() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/zho.txt")).getPath());
        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "zho");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("language", "CHINESE")
        );
    }

    @Test
    public void test_write_with_correct_iso2_language() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/jpn.txt")).getPath());
        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "jpn");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("language", "JAPANESE")
        );
    }

    @Test
    public void test_write_should_support_multiple_ocr_languages() throws Exception {
        // Given
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
        try (ElasticsearchSpewer zhoSpewer = new ElasticsearchSpewer(indexer,
            documentQueueFactory, text -> Language.CHINESE, new FieldNames(),
            new PropertiesProvider(Map.of("defaultProject", "test-datashare")))) {
            Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/zho.txt")).getPath());
            Extractor extractor = new Extractor().configure(Options.from(Map.of("ocrLanguage", "eng+zho")));
            // When
            TikaDocument document = extractor.extract(path);
            zhoSpewer.write(document);
            // Then
            GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
            assertThat(nodeToMap(documentFields.source()).get("language")).isEqualTo("CHINESE");
        }
    }

    @Test
    public void test_write_with_correct_language_name() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/maltese.txt")).getPath());
        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "Maltese");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("language", "MALTESE")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_write_with_incorrect_language() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());

        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "foo");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);
        spewer.write(document);
    }

    @Test
    public void test_write_without_language() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());
        TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("language", "ENGLISH")
        );
    }


    @Test
    public void test_metadata() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());
        TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("contentEncoding", "ISO-8859-1"),
                entry("contentType", "text/plain"),
                entry("nerTags", new ArrayList<>()),
                entry("contentLength", 45),
                entry("contentTextLength", 45),
                entry("status", "INDEXED"),
                entry("path", path.toString()),
                entry("dirname", path.getParent().toString())
        );
    }

    @Test
    public void test_long_content_length() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("t-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set("Content-Length", "7862117376");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("contentLength", 7862117376L));
    }

    @Test
    public void test_title_and_title_norm() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("T-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("title", "T-File.txt"));
        assertThat(nodeToMap(documentFields.source())).includes(entry("titleNorm", "t-file.txt"));
    }

    @Test
    public void test_title_and_title_norm_for_an_tweet() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Tweet-File.json"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "application/json; twint");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("title", "Tweet-File.json"));
        assertThat(nodeToMap(documentFields.source())).includes(entry("titleNorm", "tweet-file.json"));
    }

    @Test
    public void test_title_and_title_norm_for_an_tweet_with_dc_title() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Tweet-File.json"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "application/json; twint");
        document.getMetadata().set(DublinCore.TITLE, "This is a tweet.");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("title", "This is a tweet."));
        assertThat(nodeToMap(documentFields.source())).includes(entry("titleNorm", "this is a tweet."));
    }

    @Test
    public void test_title_and_title_norm_for_an_email() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Email-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "message/http");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("title", "Email-File.txt"));
        assertThat(nodeToMap(documentFields.source())).includes(entry("titleNorm", "email-file.txt"));
    }

    @Test
    public void test_title_and_title_norm_for_an_email_with_dc_title() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Email-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "message/http");
        document.getMetadata().set(DublinCore.TITLE, "This is an email.");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("title", "This is an email."));
        assertThat(nodeToMap(documentFields.source())).includes(entry("titleNorm", "this is an email."));
    }

    @Test
    public void test_title_and_title_norm_for_an_email_with_dc_subject() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Email-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "message/http");
        document.getMetadata().set(DublinCore.TITLE, "This is an email.");
        document.getMetadata().set(DublinCore.SUBJECT, "This is a more detailed email.");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("title", "This is a more detailed email."));
        assertThat(nodeToMap(documentFields.source())).includes(entry("titleNorm", "this is a more detailed email."));
    }

    @Test
    public void test_tika_metadata_unknown_tag_is_blocked() throws IOException {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("doc.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set("foo", "bar");
        document.getMetadata().set("unknown_tag_0x", "unknown");
        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source()).containsKey("metadata")).isTrue();
        @SuppressWarnings("unchecked")
        HashMap<String, Object> metadata = (HashMap<String, Object>) nodeToMap(documentFields.source()).get("metadata");
        // Those values should be here
        assertThat(metadata).includes(entry("tika_metadata_resourcename", "doc.txt"));
        assertThat(metadata).includes(entry("tika_metadata_foo", "bar"));
        // This value should be blocked
        assertThat(metadata).excludes(entry("tika_metadata_unknown_tag_0x", "unknown"));
    }

    @Test
    public void test_embedded_document() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        final TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertTrue(documentFields.found());

        SearchRequest.Builder searchReq = new SearchRequest.Builder().index(TEST_INDEX);
        searchReq.query(Query.of(q -> q.multiMatch(MultiMatchQuery.of(mmq -> mmq.query("Heavy Metal").fields("content")))));
        SearchResponse<ObjectNode> response = es.client.search(searchReq.build(), ObjectNode.class);
        assertThat(response.hits().total().value()).isGreaterThan(0);
    }

    @Test
    public void test_extract_id_should_be_equal_to_datashare_id() throws IOException {
        Options<String> digestAlgorithm = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
            put("digestProjectName", "project");
        }});
        DocumentFactory tikaFactory = new DocumentFactory().configure(digestAlgorithm);
        Extractor extractor = new Extractor(tikaFactory).configure(digestAlgorithm);

        final TikaDocument extractDocument = extractor.extract(get(requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath()));
        Document document = createDoc(Project.project("project"),get(requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath()))
                .with("This is a document to be parsed by datashare.")
                .with(Language.FRENCH)
                .ofContentType("text/plain")
                .with(convert(extractDocument.getMetadata()))
                .with(Document.Status.INDEXED)
                .withContentLength(45L)
                .build();

        assertThat(document.getId()).isEqualTo(extractDocument.getId());
    }

    @Test
    public void test_duplicate_file() throws Exception {
        HashMap<String, Object> properties = new HashMap<>() {{
            put("digestAlgorithm", "SHA-256");
            put("digestProjectName", "project");
            put("defaultProject", "test-datashare");
        }};
        ElasticsearchSpewer spewer256 = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()),
                documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(properties));
        Options<String> from = Options.from(properties);
        DocumentFactory tikaFactory = new DocumentFactory().configure(from);
        Extractor extractor = new Extractor(tikaFactory).configure(from);

        final TikaDocument document = extractor.extract(get(requireNonNull(getClass().getResource("/docs/doc.txt")).getPath()));
        final TikaDocument document2 = extractor.extract(get(requireNonNull(getClass().getResource("/docs/doc-duplicate.txt")).getPath()));

        spewer256.write(document);
        spewer256.write(document2);

        GetResponse<Document> actualDocument = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), Document.class);
        GetResponse<Duplicate> actualDocument2 = es.client.get(doc -> doc.index(TEST_INDEX).id(Hasher.SHA_256.hash(document2.getPath().toString())), Duplicate.class);
        assertThat(actualDocument.found()).isTrue();
        assertThat(actualDocument2.found()).isTrue();
        assertThat(actualDocument2.id().length()).isEqualTo(Hasher.SHA_256.digestLength);
    }

    @Test
    public void test_duplicate_embedded_documents() throws Exception {
        Options<String> digestAlgorithm = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
        }});
        Path path1 = get(requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        Path path2 = get(requireNonNull(getClass().getResource("/docs/embedded_doc_duplicate.eml")).getPath());
        Extractor extractor = new Extractor(new DocumentFactory().configure(digestAlgorithm)).configure(digestAlgorithm);
        final TikaDocument document1 = extractor.extract(path1);
        final TikaDocument document2 = extractor.extract(path2);

        spewer.write(document1);
        spewer.write(document2);

        String duplicateId = Document.DEFAULT_DIGESTER.hash(path2.toString());
        GetResponse<Duplicate> duplicateDoc = es.client.get(doc -> doc.index(TEST_INDEX).id(duplicateId), Duplicate.class);
        assert duplicateDoc.source() != null;
        assertThat(duplicateDoc.source().path.toString()).endsWith("embedded_doc_duplicate.eml");

        Stream<? extends Entity> searcher = new ElasticsearchIndexer(es.client, new PropertiesProvider()).search(asList(TEST_INDEX), Document.class).
                thatMatchesFieldValue("path", path2).execute();
        assertThat(searcher.count()).isEqualTo(0);
    }

    @Test
    public void test_truncated_content() throws Exception {
        ElasticsearchSpewer limitedContentSpewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()),
                documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(new HashMap<>() {{
            put("maxContentLength", "20");
            put("defaultProject", "test-datashare");
        }}));
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("fake-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("this content should be truncated".getBytes()));
        document.setReader(reader);

        limitedContentSpewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("content", "this content should"));
    }

    @Test
    public void test_truncated_content_if_document_is_smaller_than_limit() throws Exception {
        ElasticsearchSpewer limitedContentSpewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()),
                documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(new HashMap<>() {{
            put("maxContentLength", "20");
            put("defaultProject", "test-datashare");
        }}));
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("ok-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("this content is ok".getBytes()));
        document.setReader(reader);

        limitedContentSpewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(TEST_INDEX).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("content", "this content is ok"));
    }

    @Test
    public void test_get_max_content_length_is_limited_to_2G() {
        assertThat(spewer.getMaxContentLength(new PropertiesProvider(new HashMap<>() {{put("maxContentLength", "20");}})))
                .isEqualTo(20);
        assertThat((long)spewer.getMaxContentLength(new PropertiesProvider(new HashMap<>() {{put("maxContentLength", "2G");}})))
                .isEqualTo(HumanReadableSize.parse("2G")-1); // Integer.MAX_VALUE
    }

    @Test
    public void test_configure_is_creating_index() throws Exception {
        Indexer indexer = Mockito.mock(Indexer.class);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer, Mockito.mock(DocumentCollectionFactory.class), Mockito.mock(LanguageGuesser.class), new FieldNames(), new PropertiesProvider());
        spewer.configure(Options.from(new HashMap<>() {{
            put("defaultProject", "foo");
        }}));
        spewer.createIndexIfNotExists();
        Mockito.verify(indexer).createIndex("foo");
    }

    @Test
    public void test_configure_is_creating_index_with_legacy_property() throws Exception {
        Indexer indexer = Mockito.mock(Indexer.class);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer, Mockito.mock(DocumentCollectionFactory.class), Mockito.mock(LanguageGuesser.class), new FieldNames(), new PropertiesProvider());
        spewer.configure(Options.from(new HashMap<>() {{
            put("defaultProject", "foo");
        }}));
        spewer.createIndexIfNotExists();
        Mockito.verify(indexer).createIndex("foo");
    }

    @Test
    public void test_configure_is_creating_index_with_legacy_and_new_property() throws Exception {
        Indexer indexer = Mockito.mock(Indexer.class);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer, Mockito.mock(DocumentCollectionFactory.class), Mockito.mock(LanguageGuesser.class), new FieldNames(), new PropertiesProvider());
        spewer.configure(Options.from(new HashMap<>() {{
            put("projectName", "bar");
            put("defaultProject", "foo");
        }}));
        spewer.createIndexIfNotExists();
        Mockito.verify(indexer).createIndex("bar");
    }

    @After
    public void after() {
        try {
            DeleteByQueryRequest.Builder deleteByQueryRequest = new DeleteByQueryRequest.Builder().index("test-datashare");
            deleteByQueryRequest.query(Query.of(q -> q.matchAll(ma -> ma)));
            es.client.deleteByQuery(deleteByQueryRequest.build()).deleted();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, Object> convert(Metadata metadata) {
        Map<String, Object> map = new HashMap<>();
        for (String name: metadata.names()) {
            map.put(name, metadata.get(name));
        }
        return map;
    }
}
