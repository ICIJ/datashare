package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.MockIndexer;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.ExtractedText;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchedText;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.stream.Stream.of;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class DocumentResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();
    @Mock JooqRepository jooqRepository;
    @Mock Indexer indexer;
    MockIndexer mockIndexer;
    @Mock PropertiesProvider propertiesProvider;

    @Before
    public void setUp() {
        initMocks(this);
        mockIndexer = new MockIndexer(indexer);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));
        configure(routes -> {
            routes.add(new DocumentResource(jooqRepository, indexer, propertiesProvider))
                    .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository));
        });
    }

    @Test
    public void test_get_source_file() throws Exception {
        File txtFile = new File(temp.getRoot(), "file.txt");
        MockIndexer.write(txtFile, "text content");
        mockIndexer.indexFile("local-datashare", "id_txt", txtFile.toPath(), null, null);

        get("/api/local-datashare/documents/src/id_txt").should().contain("text content").haveType("text/plain;charset=UTF-8");
    }

    @Test
    public void test_get_source_file_with_content_type() throws Exception {
        File txtFile = new File(temp.getRoot(), "/my/path/to/file.ods");
        MockIndexer.write(txtFile, "content");
        mockIndexer.indexFile("local-datashare", "id_ods", txtFile.toPath(), "application/vnd.oasis.opendocument.spreadsheet", null);

        get("/api/local-datashare/documents/src/id_ods").should().
                haveType("application/vnd.oasis.opendocument.spreadsheet").
                haveHeader("Content-Disposition", "attachment;filename=\"file.ods\"");
    }

    @Test
    public void test_get_source_file_without_metadata() throws Exception {
        File txtFile = new File(temp.getRoot(), "/my/path/to/file.ods");
        MockIndexer.write(txtFile, "content");
        mockIndexer.indexFile("local-datashare", "id_ods", txtFile.toPath(), "application/vnd.oasis.opendocument.spreadsheet", null);

        get("/api/local-datashare/documents/src/id_ods?filter_metadata=true").should().succeed();
    }

    @Test
    public void test_get_source_file_with_content_type_inline() throws Exception {
        File img = new File(temp.getRoot(), "/my/path/to/image.jpg");
        MockIndexer.write(img, "content");
        mockIndexer.indexFile("local-datashare", "id_jpg", img.toPath(), "image/jpg", null);

        get("/api/local-datashare/documents/src/id_jpg?inline=true").should().
                haveType("image/jpg").contain("content").
                should().not().haveHeader("Content-Disposition", "attachment;filename=\"image.jpg\"");
    }

    @Test
    public void test_get_embedded_source_file_with_routing() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        mockIndexer.indexFile("local-datashare", "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623", Paths.get(path), "application/pdf", "id_eml");

        get("/api/local-datashare/documents/src/d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623?routing=id_eml").
                should().haveType("application/pdf").contain("PDF-1.3").haveHeader("Content-Disposition", "attachment;filename=\"d365f488df.pdf\"");;
    }

    @Test
    public void test_get_embedded_source_file_with_routing_sha256_for_backward_compatibility() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        mockIndexer.indexFile("local-datashare", "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e", Paths.get(path), "application/pdf", "id_eml");

        get("/api/local-datashare/documents/src/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e?routing=id_eml").
                should().haveType("application/pdf").contain("PDF-1.3").haveHeader("Content-Disposition", "attachment;filename=\"6abb969509.pdf\"");;
    }

    @Test
    public void test_content_not_found_should_return_404() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        mockIndexer.indexFile("local-datashare", "embedded_id_sha256_of_sixty_four_character_not_in_actual_content", Paths.get(path), "application/pdf", "id_eml");

        get("/api/local-datashare/documents/src/embedded_id_sha256_of_sixty_four_character_not_in_actual_content?routing=id_eml").
                should().respond(404);
        assertThat(logback.logs(Level.ERROR)).contains("unable to read document source file");
    }

    @Test
    public void test_source_file_not_found_should_return_404() {
        mockIndexer.indexFile("local-datashare", "missing_file", Paths.get("missing/file"), null, null);
        get("/api/local-datashare/documents/src/missing_file").should().respond(404);
        assertThat(logback.logs(Level.ERROR)).contains("unable to read document source file");
    }

    @Test
    public void test_get_source_file_forbidden_index() {
        get("/api/foo_index/documents/src/id").should().respond(403);
    }

    @Test
    public void test_get_source_root_too_big() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        Project index = new Project("local-datashare");
        Document documentBar = DocumentBuilder.createDoc("bar").with(index).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document documentFoo = DocumentBuilder.createDoc("foo").with(index).with(path).withParentId("bar").withRootId("bar").build();
        mockIndexer.indexFile("local-datashare", documentBar, documentFoo);
        get("/api/local-datashare/documents/src/foo?routing=bar").should().respond(413);
    }

    @Test
    public void test_group_star_document_with_project() {
        when(jooqRepository.star(project("prj1"), User.local(), asList("id1", "id2"))).thenReturn(2);
        post("/api/prj1/documents/batchUpdate/star", "[\"id1\", \"id2\"]").should().respond(200);
    }

    @Test
    public void test_group_unstar_document_with_project() {
        when(jooqRepository.unstar(project("prj1"), User.local(), asList("id1", "id2"))).thenReturn(2);
        post("/api/prj1/documents/batchUpdate/unstar", "[\"id1\", \"id2\"]").should().respond(200);
    }

    @Test
    public void test_group_recommend_document_with_project() {
        when(jooqRepository.recommend(project("prj1"), User.local(), asList("id1", "id2"))).thenReturn(2);
        post("/api/prj1/documents/batchUpdate/recommend", "[\"id1\", \"id2\"]").should().respond(200);
    }

    @Test
    public void test_group_unrecommend_document_with_project() {
        when(jooqRepository.unrecommend(project("prj1"), User.local(), asList("id1", "id2"))).thenReturn(2);
        post("/api/prj1/documents/batchUpdate/unrecommend", "[\"id1\", \"id2\"]").should().respond(200);
    }

    @Test
    public void test_get_recommendation_users_of_documents() {
        when(jooqRepository.getRecommendations(eq(project("prj")), eq(asList("docId1","docId2")))).thenReturn(new Repository.AggregateList<>(of(new Repository.Aggregate<>(new User("user1"), 2), new Repository.Aggregate<>(new User("user2"), 3)).collect(Collectors.toList()), 10));
        get("/api/users/recommendationsby?project=prj&docIds=docId1,docId2").should().respond(200).contain("user1").contain("user2").contain("\"totalCount\":10");
    }

    @Test
    public void test_get_recommendations_users() {
        when(jooqRepository.getRecommendations(eq(project("prj")))).thenReturn(new Repository.AggregateList<>(of(new Repository.Aggregate<>(new User("user3"), 3), new Repository.Aggregate<>(new User("user4"), 4)).collect(Collectors.toList()), 8));
        get("/api/users/recommendations?project=prj").should().respond(200).contain("user3").contain("user4");
    }

    @Test
    public void test_get_recommended_documents() {
        when(jooqRepository.getRecommentationsBy(eq(project("prj")),eq(asList(new User("user1"), new User("user2"))))).thenReturn(of("doc1","doc2").collect(Collectors.toSet()));
        get("/api/prj/documents/recommendations?userids=user1,user2").should().respond(200).contain("doc1").contain("doc2");
    }

    @Test
    public void test_tag_document_with_project() throws Exception {
        when(jooqRepository.tag(eq(project("prj1")), anyString(), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.tag(eq(project("prj1")), anyString(), anyString(), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        put("/api/prj1/documents/tags/doc_id", "[\"tag1\", \"tag2\"]").should().respond(201);
        put("/api/prj1/documents/tags/doc_id", "[\"tag1\", \"tag2\"]").should().respond(200);

        verify(indexer, times(2)).tag(eq(project("prj1")), eq("doc_id"), eq("doc_id"), eq(tag("tag1")), eq(tag("tag2")));
    }

    @Test
    public void test_untag_document_with_project() throws Exception {
        when(jooqRepository.untag(eq(project("prj2")), anyString(), eq(tag("tag3")), eq(tag("tag4")))).thenReturn(true).thenReturn(false);
        when(indexer.untag(eq(project("prj2")), anyString(), any(), eq(tag("tag3")), eq(tag("tag4")))).thenReturn(true).thenReturn(false);

        put("/api/prj2/documents/untag/doc_id", "[\"tag3\", \"tag4\"]").should().respond(201);
        put("/api/prj2/documents/untag/doc_id", "[\"tag3\", \"tag4\"]").should().respond(200);

        verify(indexer, times(2)).untag(eq(project("prj2")), anyString(), any(), any(), any());
    }

    @Test
    public void test_group_tag_document_with_project() throws Exception {
        when(jooqRepository.tag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.tag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        post("/api/prj1/documents/batchUpdate/tag", "{\"tags\": [\"tag1\", \"tag2\"], \"docIds\": [\"doc1\", \"doc2\"]}").should().respond(200);
    }

    @Test
    public void test_group_untag_document_with_project() throws Exception {
        when(jooqRepository.untag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.untag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        post("/api/prj1/documents/batchUpdate/untag", "{\"tags\": [\"tag1\", \"tag2\"], \"docIds\": [\"doc1\", \"doc2\"]}").should().respond(200);
    }

    @Test
    public void test_get_tagged_documents_with_project() {
        when(jooqRepository.getDocuments(eq(project("prj3")), eq(tag("foo")), eq(tag("bar")), eq(tag("baz")))).thenReturn(asList("id1", "id2"));
        get("/api/prj3/documents/tagged/foo,bar,baz").should().respond(200).contain("id1").contain("id2");
    }

    @Test
    public void test_get_starred_documents_with_project() {
        when(jooqRepository.getStarredDocuments(project("local-datashare"), User.local())).thenReturn(asList("id1", "id2"));
        get("/api/local-datashare/documents/starred").should().respond(200).contain("id1").contain("id2");
    }

    @Test
    public void test_tags_for_document() {
        when(jooqRepository.getTags(eq(project("prj")), eq("docId"))).thenReturn(asList(tag("tag1"), tag("tag2")));
        get("/api/prj/documents/tags/docId").should().respond(200).contain("tag1").contain("tag2");
    }

    @Test
    public void test_tag_document_with_project_with_routing() throws Exception {
        when(jooqRepository.tag(eq(project("prj1")), anyString(), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.tag(eq(project("prj1")), any(), eq("routing"), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        put("/api/prj1/documents/tags/doc_id?routing=routing", "[\"tag1\", \"tag2\"]").should().respond(201);
        put("/api/prj1/documents/tags/doc_id?routing=routing", "[\"tag1\", \"tag2\"]").should().respond(200);

        verify(indexer, times(2)).tag(eq(project("prj1")), any(), eq("routing"), eq(tag("tag1")), eq(tag("tag2")));
    }


    @Test
    public void test_get_starred_documents() {
        when(jooqRepository.getStarredDocuments(any())).thenReturn(asList(createDoc("doc1").build(), createDoc("doc2").build()));
        get("/api/documents/starred").should().respond(200).haveType("application/json").contain("\"doc1\"").contain("\"doc2\"");
    }

    @Test
    public void test_get_document_source_with_good_mask() throws Exception {
        File txtFile = new File(temp.getRoot(), "file.txt");
        List<String> sourceExcludes = List.of("content", "content_translated");
        MockIndexer.write(txtFile, "content");
        when(indexer.get("local-datashare", "docId", "root", sourceExcludes)).thenReturn(createDoc("doc").with(txtFile.toPath()).build());

        when(jooqRepository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "*.*.*.*"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(200);

        when(jooqRepository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "127.0.0.*"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(200);
    }

    @Test
    public void test_get_document_source_with_bad_mask() {
        when(indexer.get("local-datashare", "docId", "root")).thenReturn(createDoc("doc").build());

        when(jooqRepository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "1.2.3.4"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(403);

        when(jooqRepository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "127.0.1.*"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(403);
    }

    @Test
    public void test_get_document() throws Exception {
        mockIndexer.indexFile("local-datashare", "id_txt", Paths.get("/path/to/file"), "application/pdf");

        get("/api/local-datashare/documents/id_txt").should().respond(200).
                haveType("application/json").
                contain("/path/to/file").
                contain("id_txt");
    }

    @Test
    public void test_get_document_with_routing() throws Exception {
        mockIndexer.indexFile("local-datashare", "id_txt", Paths.get("/path/to/file"), "application/pdf", "routing");

        get("/api/local-datashare/documents/id_txt?routing=routing").should().respond(200).
                haveType("application/json").
                contain("/path/to/file").
                contain("id_txt");
    }

    @Test
    public void test_get_document_source_with_unknown_project() throws IOException {
        File txtFile = new File(temp.getRoot(), "file.txt");
        List<String> sourceExcludes = List.of("content", "content_translated");
        MockIndexer.write(txtFile, "content");
        when(indexer.get("local-datashare", "docId", "root", sourceExcludes)).thenReturn(createDoc("doc").with(txtFile.toPath()).build());

        when(jooqRepository.getProject("local-datashare")).thenReturn(null);
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(200);
    }
    @Test
    public void test_get_document_extracted_text() throws IOException {
        when(indexer.getExtractedText("local-datashare", "docId", null,5, 6, null)).thenReturn(new ExtractedText("content", 5, 6, 7));
        get("/api/local-datashare/documents/content/docId?offset=5&limit=6").should().respond(200)
                .should()
                .haveType("application/json")
                .contain("\"content\":\"content\"")
                .contain("\"offset\":5")
                .contain("\"limit\":6")
                .contain("\"maxOffset\":7");
    }
    @Test
    public void test_get_document_content() throws IOException {

        HashMap<String, String> language = new HashMap<>() {{
            put("target_language", "fr");
            put("content", "mon-contenu");
        }};
        List<Map<String,String>> langs = new ArrayList<>();
        langs.add(language);


        Document doc = createDoc("docId")
                .with("my-content")
                .with(langs).build();
        when(jooqRepository.getDocument("docId")).thenReturn(doc);
        get("/api/local-datashare/documents/content/docId").should().respond(200)
                .haveType("application/json;charset=UTF-8")
                .contain("my-content");
        get("/api/local-datashare/documents/content/docId?targetLanguage=fr").should().respond(200)
                .haveType("application/json;charset=UTF-8")
                .contain("mon-contenu");
    }


    @Test
    public void test_get_document_extracted_text_with_routing() throws IOException {
        when(indexer.getExtractedText("local-datashare", "docId", "routingId", 5, 6, null)).thenReturn(new ExtractedText("content", 5, 6, 7));
        get("/api/local-datashare/documents/content/docId?offset=5&limit=6&routing=routingId").should().respond(200)
                .should()
                .haveType("application/json")
                .contain("\"content\":\"content\"")
                .contain("\"offset\":5")
                .contain("\"limit\":6")
                .contain("\"maxOffset\":7");
    }

    @Test
    public void test_get_document_extracted_text_with_blank_target_language() throws IOException {
        when(indexer.getExtractedText("local-datashare", "docId",null, 5, 6, "")).thenReturn(new ExtractedText("content", 5, 6, 7));
        get("/api/local-datashare/documents/content/docId?offset=5&limit=6&targetLanguage").should().respond(200);
    }

    @Test
    public void test_get_document_extracted_text_with_out_of_bound_args() throws IOException {
        when(indexer.getExtractedText("local-datashare", "docId",null, 6, -2, null))
                .thenThrow(
                        new StringIndexOutOfBoundsException("Range [6-4] is out of document range ([0-10])")
                );
        get("/api/local-datashare/documents/content/docId?limit=-2&offset=6")
                .should()
                .respond(400)
                .contain("Range [6-4] is out of document range ([0-10])");
    }

    @Test
    public void test_get_document_extracted_text_not_found() throws IOException {
        when(indexer.getExtractedText("local-datashare", "notFoundDoc", null,6, -2,null))
                .thenThrow(
                        new IllegalArgumentException("Document not found")
                );
        get("/api/local-datashare/documents/content/notFoundDoc?limit=-2&offset=6")
                .should()
                .respond(404);
    }

    @Test
    public void test_get_document_translated_extracted_text() throws IOException {
        when(indexer.getExtractedText("local-datashare", "docId", null, 5, 6,"french"))
                .thenReturn(new ExtractedText("content", 5, 6, 7, "french"));
        get("/api/local-datashare/documents/content/docId?offset=5&limit=6&targetLanguage=french").should().respond(200)
                .should()
                .haveType("application/json")
                .contain("\"content\":\"content\"")
                .contain("\"offset\":5")
                .contain("\"limit\":6")
                .contain("\"maxOffset\":7")
                .contain("\"targetLanguage\":\"french\"");
    }
    @Test
    public void test_get_document_translated_extracted_text_with_unknown_target_language() throws IOException {
        when(indexer.getExtractedText("local-datashare", "docId", null,5, 6,"unknown"))
                .thenThrow(
                        new IllegalArgumentException("Translated content in 'unknown' not found")
                );
        get("/api/local-datashare/documents/content/docId?offset=5&limit=6&targetLanguage=unknown")
                .should()
                .respond(404);
    }

    @Test
    public void test_search_text_occurrences_in_document_original_content() throws IOException {
        when(indexer.searchTextOccurrences("local-datashare", "docId", "test",null))
                .thenReturn(new SearchedText(new int[]{1,2}, 2, "test"));
        get("/api/local-datashare/documents/searchContent/docId?query=test").should().respond(200)
                .should()
                .haveType("application/json")
                .contain("\"query\":\"test\"")
                .contain("\"count\":2")
                .contain("\"offsets\":[1,2]");
    }

    @Test
    public void test_get_page_indices() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        mockIndexer.indexFile("local-datashare",
                "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623",
                Paths.get(path), "application/pdf", "id_eml", Map.of("tika_metadata_resourcename", "embedded.pdf"));

        get("/api/local-datashare/documents/pages/d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623?routing=id_eml")
                .should().respond(200)
                .haveType("application/json")
                .contain("[[0,16],[17,33]]");
    }

    @Test
    public void test_get_pages() throws JsonProcessingException {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        mockIndexer.indexFile("local-datashare",
                "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623",
                Paths.get(path), "application/pdf", "id_eml", Map.of("tika_metadata_resourcename", "embedded.pdf"));

        Response response = get("/api/local-datashare/documents/content/pages/d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623?routing=id_eml").response();
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo("application/json;charset=UTF-8");
        List<String> json = JsonObjectMapper.MAPPER.readValue(response.content(), new TypeReference<>() {});
        assertThat(json).hasSize(2);
        assertThat(json.get(0)).contains("HEAVY\nMETAL");
        assertThat(json.get(1)).contains("HEAVY\nMETAL");
    }
}
