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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
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
                put("defaultProject", es.getIndexName());
    }}));

    @Test
    public void test_simple_write() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("test-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(documentFields.found()).isTrue();
        assertThat(documentFields.id()).isEqualTo(document.getId());
        assertEquals(new HashMap<String, String>() {{
            put("name", "Document");
        }}, nodeToMap(documentFields.source()).get("join"));
        assertThat(documentQueueFactory.createQueue("extract:queue:nlp", String.class).size()).isEqualTo(1);
    }

    @Test
    public void test_write_root_stub_marks_partial_with_child_count() throws Exception {
        // A streaming parse aborted after some children were indexed but before the root was written.
        // The stub must index a contentless root marked PARTIAL, recording how many children got in.
        final TikaDocument root = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("aborted-container.ost"));

        spewer.writeRootStub(root, 55363L);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(root.getId()), ObjectNode.class);
        assertThat(documentFields.found()).isTrue();
        Map<String, Object> source = nodeToMap(documentFields.source());
        assertThat(source.get("recoveryStatus")).isEqualTo("PARTIAL");
        assertThat(((Number) source.get("nbChildrenEmitted")).intValue()).isEqualTo(55363);
        assertThat(((Map<?, ?>) source.get("join")).get("name")).isEqualTo("Document");
        // Contentless: the stub must not carry the document hash (createDoc's default) as its body text.
        assertThat(source.get("content")).isEqualTo("");
    }

    @Test
    public void test_write_root_stub_does_not_overwrite_an_already_indexed_root() throws Exception {
        // A prior run indexed this container fully (content + real status).
        final TikaDocument existing = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("prior-complete.ost"));
        existing.setReader(new ParsingReader(new ByteArrayInputStream("real container body text".getBytes())));
        spewer.write(existing);

        // A later re-run's streaming parse aborts before the root is written, for the same id.
        final TikaDocument root = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("prior-complete.ost"));
        spewer.writeRootStub(root, 99L);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(root.getId()), ObjectNode.class);
        Map<String, Object> source = nodeToMap(documentFields.source());
        // The complete root is preserved: not clobbered by a contentless PARTIAL stub.
        assertThat((String) source.get("content")).contains("real container body text");
        assertThat(source.get("recoveryStatus")).isNull();
        assertThat(source.get("nbChildrenEmitted")).isNull();
    }

    @Test
    public void test_write_root_stub_tolerates_non_numeric_content_length() throws Exception {
        final TikaDocument root = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("weird-length.ost"));
        root.getMetadata().set("Content-Length", "not-a-number");

        spewer.writeRootStub(root, 3L); // must not throw and orphan the children

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(root.getId()), ObjectNode.class);
        assertThat(documentFields.found()).isTrue();
        assertThat(nodeToMap(documentFields.source()).get("recoveryStatus")).isEqualTo("PARTIAL");
    }

    @Test
    public void test_write_with_correct_iso1_language() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/zho.txt")).getPath());
        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "zho");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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
            new PropertiesProvider(Map.of("defaultProject", es.getIndexName())))) {
            Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/zho.txt")).getPath());
            Options<String> options = Options.from(Map.of("ocrLanguage", "eng+zho"));
            Extractor extractor = new Extractor(options);
            // When
            TikaDocument document = extractor.extract(path);
            zhoSpewer.write(document);
            // Then
            GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("language", "MALTESE")
        );
    }

    @Test
    public void test_write_with_incorrect_language() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());

        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "foo");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);
        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("language", "UNKNOWN")
        );
    }

    @Test
    public void test_write_without_language() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());
        TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(
                entry("language", "ENGLISH")
        );
    }


    @Test
    public void test_metadata() throws Exception {
        Path path = get(requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());
        TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("contentLength", 7862117376L));
    }

    @Test
    public void test_title_and_title_norm() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("T-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertTrue(documentFields.found());

        SearchRequest.Builder searchReq = new SearchRequest.Builder().index(es.getIndexName());
        searchReq.query(Query.of(q -> q.multiMatch(MultiMatchQuery.of(mmq -> mmq.query("Heavy Metal").fields("content")))));
        SearchResponse<ObjectNode> response = es.client.search(searchReq.build(), ObjectNode.class);
        assertThat(response.hits().total().value()).isGreaterThan(0);
    }

    @Test
    public void test_embedded_document_is_enqueued_with_its_root_id() throws Exception {
        Options<String> digestAlgorithm = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
            put("digestProjectName", "project");
        }});
        Extractor extractor = new Extractor(new DocumentFactory().configure(digestAlgorithm), digestAlgorithm);
        Path path = get(requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        final TikaDocument document = extractor.extract(path);

        spewer.write(document);

        List<String> queueEntries = new ArrayList<>();
        documentQueueFactory.createQueue("extract:queue:nlp", String.class).drainTo(queueEntries);
        assertThat(queueEntries).contains(document.getId());
        assertThat(queueEntries.stream().anyMatch(entry -> entry.endsWith("|" + document.getId()))).isTrue();
    }

    @Test
    public void test_extract_id_should_be_equal_to_datashare_id() throws IOException {
        Options<String> digestAlgorithm = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
            put("digestProjectName", "project");
        }});
        DocumentFactory tikaFactory = new DocumentFactory().configure(digestAlgorithm);
        Extractor extractor = new Extractor(tikaFactory, digestAlgorithm);

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
            put("defaultProject", es.getIndexName());
        }};
        ElasticsearchSpewer spewer256 = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()),
                documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(properties));
        Options<String> from = Options.from(properties);
        DocumentFactory tikaFactory = new DocumentFactory().configure(from);
        Extractor extractor = new Extractor(tikaFactory, from);

        final TikaDocument document = extractor.extract(get(requireNonNull(getClass().getResource("/docs/doc.txt")).getPath()));
        final TikaDocument document2 = extractor.extract(get(requireNonNull(getClass().getResource("/docs/doc-duplicate.txt")).getPath()));

        spewer256.write(document);
        spewer256.write(document2);

        GetResponse<Document> actualDocument = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), Document.class);
        GetResponse<Duplicate> actualDocument2 = es.client.get(doc -> doc.index(es.getIndexName()).id(Hasher.SHA_256.hash(document2.getPath().toString())), Duplicate.class);
        assertThat(actualDocument.found()).isTrue();
        assertThat(actualDocument2.found()).isTrue();
        assertThat(actualDocument2.id().length()).isEqualTo(Hasher.SHA_256.digestLength);
    }

    @Test
    public void test_non_duplicate_file_on_same_path() throws Exception {
        HashMap<String, Object> properties = new HashMap<>() {{
            put("digestAlgorithm", "SHA-256");
            put("digestProjectName", "project");
            put("defaultProject", es.getIndexName());
        }};
        ElasticsearchSpewer spewer256 = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()),
                documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(properties));
        Options<String> from = Options.from(properties);
        DocumentFactory tikaFactory = new DocumentFactory().configure(from);
        Extractor extractor = new Extractor(tikaFactory, from);

        final TikaDocument document = extractor.extract(get(requireNonNull(getClass().getResource("/docs/doc.txt")).getPath()));
        final TikaDocument document2 = extractor.extract(get(requireNonNull(getClass().getResource("/docs/doc.txt")).getPath()));

        spewer256.write(document);
        spewer256.write(document2);

        GetResponse<Document> actualDocument = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), Document.class);
        GetResponse<Document> actualDocument2 = es.client.get(doc -> doc.index(es.getIndexName()).id(document2.getId()), Document.class);
        GetResponse<Duplicate> duplicate = es.client.get(doc -> doc.index(es.getIndexName()).id(Hasher.SHA_256.hash(document2.getPath().toString())), Duplicate.class);
        assertThat(actualDocument.found()).isTrue();
        assertThat(actualDocument2.found()).isTrue();
        assertThat(duplicate.found()).isFalse();
    }

    @Test
    public void test_duplicate_embedded_documents() throws Exception {
        Options<String> digestAlgorithm = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
        }});
        Path path1 = get(requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        Path path2 = get(requireNonNull(getClass().getResource("/docs/embedded_doc_duplicate.eml")).getPath());
        Extractor extractor = new Extractor(new DocumentFactory().configure(digestAlgorithm), digestAlgorithm);
        final TikaDocument document1 = extractor.extract(path1);
        final TikaDocument document2 = extractor.extract(path2);

        spewer.write(document1);
        spewer.write(document2);

        String duplicateId = Document.DEFAULT_DIGESTER.hash(path2.toString());
        GetResponse<Duplicate> duplicateDoc = es.client.get(doc -> doc.index(es.getIndexName()).id(duplicateId), Duplicate.class);
        assert duplicateDoc.source() != null;
        assertThat(duplicateDoc.source().path.toString()).endsWith("embedded_doc_duplicate.eml");

        Stream<? extends Entity> searcher = new ElasticsearchIndexer(es.client, new PropertiesProvider()).search(asList(es.getIndexName()), Document.class).
                thatMatchesFieldValue("path", path2).execute();
        assertThat(searcher.count()).isEqualTo(0);
    }

    @Test
    public void test_truncated_content() throws Exception {
        ElasticsearchSpewer limitedContentSpewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()),
                documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(new HashMap<>() {{
            put("maxContentLength", "20");
            put("defaultProject", es.getIndexName());
        }}));
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("fake-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("this content should be truncated".getBytes()));
        document.setReader(reader);

        limitedContentSpewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(nodeToMap(documentFields.source())).includes(entry("content", "this content should"));
    }

    @Test
    public void test_truncated_content_if_document_is_smaller_than_limit() throws Exception {
        ElasticsearchSpewer limitedContentSpewer = new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()),
                documentQueueFactory, text -> Language.ENGLISH, new FieldNames(), new PropertiesProvider(new HashMap<>() {{
            put("maxContentLength", "20");
            put("defaultProject", es.getIndexName());
        }}));
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("ok-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("this content is ok".getBytes()));
        document.setReader(reader);

        limitedContentSpewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
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

    @Test
    public void test_write_fills_content_type_category() throws Exception {
        TikaDocument doc = aTikaDocWithContentType("application/pdf");

        spewer.write(doc);

        GetResponse<ObjectNode> fields = es.client.get(d -> d.index(es.getIndexName()).id(doc.getId()),
                ObjectNode.class);
        assertThat(nodeToMap(fields.source())).includes(entry("contentTypeCategory", "DOCUMENT"));
    }

    @Test
    public void test_write_fills_content_type_category_when_content_type_is_null() throws Exception {
        TikaDocument doc = aTikaDocWithContentType(null);

        spewer.write(doc);

        GetResponse<ObjectNode> fields = es.client.get(d -> d.index(es.getIndexName()).id(doc.getId()),
                ObjectNode.class);
        assertThat(nodeToMap(fields.source())).includes(entry("contentTypeCategory", "OTHER"));
    }

    @After
    public void after() {
        try {
            DeleteByQueryRequest.Builder deleteByQueryRequest = new DeleteByQueryRequest.Builder().index(es.getIndexName());
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

    @Test
    public void test_ocr_parser_exists_when_tesseract_parser_detected() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("ocr-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("dummy".getBytes()));
        document.setReader(reader);
        document.getMetadata().set("Content-Type", "text/plain");
        document.getMetadata().set("ocr:parser", "org.icij.extract.ocr.Tess4JOCRParser");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(documentFields.found()).isTrue();
        Map<String, Object> src = nodeToMap(documentFields.source());
        assertThat(src.get("ocrParser")).isEqualTo("org.icij.extract.ocr.Tess4JOCRParser");
    }

    @Test
    public void test_ocr_parser_is_null_when_no_tesseract_parser() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("no-ocr-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("dummy".getBytes()));
        document.setReader(reader);
        document.getMetadata().set("Content-Type", "text/plain");

        spewer.write(document);

        GetResponse<ObjectNode> documentFields = es.client.get(doc -> doc.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(documentFields.found()).isTrue();
        Map<String, Object> src = nodeToMap(documentFields.source());
        assertThat(src.get("ocrParser")).isNull();
    }

    @Test
    public void recovered_child_metadata_maps_to_recoveryStatus_field() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("att-1.jpg"));
        document.getMetadata().set("tika:pst_attachment_recovery", "RECOVERED");
        document.setReader(new ParsingReader(new ByteArrayInputStream("jpegbytes".getBytes())));
        spewer.write(document);
        GetResponse<ObjectNode> resp = es.client.get(d -> d.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(resp.source().get("recoveryStatus").asText()).isEqualTo("RECOVERED");
    }

    @Test
    public void unrecovered_stub_indexes_as_empty_indexed_document() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("corrupt.pdf"));
        document.getMetadata().set("tika:pst_attachment_recovery", "UNRECOVERED");
        document.setReader(new ParsingReader(new ByteArrayInputStream(new byte[0])));
        spewer.write(document);
        GetResponse<ObjectNode> resp = es.client.get(d -> d.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(resp.source().get("recoveryStatus").asText()).isEqualTo("UNRECOVERED");
        assertThat(resp.source().get("status").asText()).isEqualTo("INDEXED");
        assertThat(resp.source().get("content").asText()).isEmpty();
    }

    @Test
    public void non_pst_document_has_no_recoveryStatus() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("plain.txt"));
        document.setReader(new ParsingReader(new ByteArrayInputStream("hello".getBytes())));
        spewer.write(document);
        GetResponse<ObjectNode> resp = es.client.get(d -> d.index(es.getIndexName()).id(document.getId()), ObjectNode.class);
        assertThat(resp.source().get("recoveryStatus")).isNull();
    }

    @Test
    public void pst_root_with_message_loss_rolls_up_to_LOSSY() throws Exception {
        final TikaDocument doc = pstRoot("mboxLossy.pst", "7", "6", "0");
        spewer.write(doc);
        ObjectNode src = es.client.get(d -> d.index(es.getIndexName()).id(doc.getId()), ObjectNode.class).source();
        assertThat(src.get("recoveryStatus").asText()).isEqualTo("LOSSY");
        assertThat(src.get("pstExpected").asInt()).isEqualTo(7);
        assertThat(src.get("pstEmitted").asInt()).isEqualTo(6);
    }

    @Test
    public void pst_root_with_unrecovered_attachments_rolls_up_to_PARTIAL() throws Exception {
        final TikaDocument doc = pstRoot("mboxPartial.pst", "7", "7", "3");
        spewer.write(doc);
        ObjectNode src = es.client.get(d -> d.index(es.getIndexName()).id(doc.getId()), ObjectNode.class).source();
        assertThat(src.get("recoveryStatus").asText()).isEqualTo("PARTIAL");
        assertThat(src.get("pstUnrecovered").asInt()).isEqualTo(3);
    }

    @Test
    public void pst_root_fully_clean_rolls_up_to_COMPLETE() throws Exception {
        final TikaDocument doc = pstRoot("mboxComplete.pst", "7", "7", "0");
        spewer.write(doc);
        ObjectNode src = es.client.get(d -> d.index(es.getIndexName()).id(doc.getId()), ObjectNode.class).source();
        assertThat(src.get("recoveryStatus").asText()).isEqualTo("COMPLETE");
    }

    @Test
    public void recoveryStatus_is_aggregatable_keyword() throws Exception {
        final TikaDocument doc = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("agg.jpg"));
        doc.getMetadata().set("tika:pst_attachment_recovery", "RECOVERED");
        doc.setReader(new ParsingReader(new ByteArrayInputStream("x".getBytes())));
        spewer.write(doc);
        SearchResponse<ObjectNode> resp = es.client.search(s -> s
                .index(es.getIndexName()).size(0)
                .aggregations("byStatus", a -> a.terms(t -> t.field("recoveryStatus"))), ObjectNode.class);
        boolean hasRecovered = resp.aggregations().get("byStatus").sterms().buckets().array().stream()
                .anyMatch(b -> b.key().stringValue().equals("RECOVERED"));
        assertThat(hasRecovered).isTrue();
    }

    private TikaDocument pstRoot(String name, String expected, String emitted, String unrecovered) throws Exception {
        TikaDocument doc = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get(name));
        doc.getMetadata().set("tika:pst_expected", expected);
        doc.getMetadata().set("tika:pst_emitted", emitted);
        doc.getMetadata().set("tika:pst_attachments_unrecovered", unrecovered);
        doc.setReader(new ParsingReader(new ByteArrayInputStream("mailbox".getBytes())));
        return doc;
    }

    public static TikaDocument aTikaDocWithContentType(String contentType) {
        Metadata metadata = new Metadata();
        metadata.add(TikaDocument.CONTENT_TYPE, contentType);
        final ParsingReader reader;
        try {
            reader = new ParsingReader(new ByteArrayInputStream("Fake content in the doc".getBytes()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        TikaDocument res = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("fake-file.txt"), metadata);
        res.setReader(reader);
        return res;
    }
}

