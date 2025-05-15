package org.icij.datashare.web;

import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.function.Pair;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TestTaskUtils;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskResourceBatchSearchTest extends AbstractProdWebServerTest {
    @Mock BatchSearchRepository batchSearchRepository;
    private static final TestTaskUtils.DatashareTaskFactoryForTest taskFactory = mock(TestTaskUtils.DatashareTaskFactoryForTest.class);
    private static final TaskManagerMemory taskManager = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), new PropertiesProvider());

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        TestTaskUtils.init(taskFactory);
        configure(routes -> routes.add(new TaskResource(taskFactory, taskManager, new PropertiesProvider(), batchSearchRepository, MAPPER)));
    }

    @After
    public void tearDown() throws Exception {taskManager.clear();}

    @Test
    public void test_upload_batch_search_csv_with_all_parameters()  {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addField("name","my batch search")
                        .addField("description","search description")
                        .addFile(
                                new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv").withContent("query one\n" +
                                        "query two\r\n" +
                                        "query three\r\n"))
                        .addField("published",String.valueOf(true))
                        .addField("fileTypes","application/pdf")
                        .addField("fileTypes","image/jpeg")
                        .addField("query_template","{\"test\":42}")
                        .addField("tags","tag_02")
                        .addField("paths","/path/to/document")
                        .addField("paths","/other/path/")
                        .addField("fuzziness",String.valueOf(4))
                        .addField("phrase_matches",String.valueOf(true)).build()).response();

        assertThat(response.code()).isEqualTo(200);
        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().published).isTrue();
        assertThat(argument.getValue().fileTypes).containsExactly("application/pdf", "image/jpeg");
        assertThat(argument.getValue().queryTemplate.toString()).isEqualTo("{\"test\":42}");
        assertThat(argument.getValue().paths).containsExactly("/path/to/document", "/other/path/");
        assertThat(argument.getValue().fuzziness).isEqualTo(4);
        assertThat(argument.getValue().phraseMatches).isTrue();
        assertThat(argument.getValue().description).isEqualTo("search description");
        assertThat(argument.getValue().hasQueryTemplate()).isTrue();
        Iterator<String> iterator = argument.getValue().queries.keySet().iterator();
        assertThat(iterator.next()).isEqualTo("query one");
        assertThat(iterator.next()).isEqualTo("query two");
        assertThat(iterator.next()).isEqualTo("query three");
        assertThat(iterator.hasNext()).isFalse();
    }


    @Test
    public void test_upload_batch_search_csv_without_name_should_send_bad_request() {
        when(batchSearchRepository.save(any())).thenReturn(true);
        postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addFile(new FileUpload("csvFile").withContent("value\r\n")).build()).should().respond(400);
    }

    @Test
    public void test_upload_batch_search_csv_without_csvFile_should_send_bad_request() {
        when(batchSearchRepository.save(any())).thenReturn(true);
        postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addField("name","name").build()).should().respond(400);
    }

    @Test
    public void test_upload_batch_search_csv_with_csvFile_with_60K_queries_should_send_request_too_large() throws IOException {
        when(batchSearchRepository.save(any())).thenReturn(true);
        StringBuilder content = new StringBuilder();
        IntStream.range(0,60000).boxed().toList().forEach(i -> content.append("Test ").append(i).append("\r\n"));
        Response response = postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addField("name","nameValue")
                        .addFile(new FileUpload("csvFile").withContent(content.toString())).build()).response();
        assertThat(response.code()).isEqualTo(413);
    }

    @Test
    public void test_upload_batch_search_csv_with_name_and_csvfile_should_send_OK()
        throws InterruptedException, IOException {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addField("name","nameValue")
                        .addFile(new FileUpload("csvFile").withContent("query\r\néèàç\r\n")).build()).response();
        assertThat(response.code()).isEqualTo(200);
        BatchSearch expected = new BatchSearch(response.content(),
                singletonList(project("prj")), "nameValue", null,
                asSet("query", "éèàç"), new Date(), BatchSearch.State.QUEUED, User.local());
        verify(batchSearchRepository).save(eq(expected));
        List<Task> tasks = taskManager.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo(BatchSearchRunner.class.getName());
    }

    @Test
    public void test_upload_batch_search_csv_triple_double_quote_match_phrases_false() {
        testTripleQuote(false, "\"\"\"query one\"\"\"\n","\"query one\"");
    }

    @Test
    public void test_upload_batch_search_csv_triple_double_quote_match_phrases_true(){
        testTripleQuote(true, "\"\"\"query one\"\"\"\n","\"\"\"query one\"\"\"");
    }

    @Test
    public void test_upload_batch_search_csv_double_quote_inside_query_match_phrases_false() {
        testTripleQuote(false, "\"\"\"term one\"\" AND term two\"\n","\"term one\" AND term two");
    }

    @Test
    public void test_upload_batch_search_csv_double_quote_inside_query_match_phrases_true() {
        testTripleQuote(true, "\"\"\"term one\"\" AND term two\"\n","\"\"\"term one\"\" AND term two\"");
    }

    @Test
    public void test_upload_batch_search_csv_one_double_quote_match_phrases_false() {
        testTripleQuote(false, "\"term one\" AND \"term two\"\n","\"term one\" AND \"term two\"");
    }

    @Test
    public void test_rerun_batch_search_not_found() {
        post("/api/task/batchSearch/copy/bad_uuid", "{}").should().respond(404);
    }

    @Test
    public void test_rerun_batch_search() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        BatchSearch sourceSearch = new BatchSearch(asList(project("prj1"), project("prj2")), "name", "description1", asSet("query 1", "query 2"), uri, User.local());
        when(batchSearchRepository.get(null, sourceSearch.uuid)).thenReturn(sourceSearch);
        when(batchSearchRepository.save(any())).thenReturn(true);

        post("/api/task/batchSearch/copy/" + sourceSearch.uuid,
                "{\"name\": \"test\", \"description\": \"test description\"}").
                should().respond(200);

        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().name).isEqualTo("test");
        assertThat(argument.getValue().description).isEqualTo("test description");
        assertThat(argument.getValue().uri).isEqualTo(sourceSearch.uri);
        assertThat(argument.getValue().projects).isEqualTo(sourceSearch.projects);
        assertThat(argument.getValue().queries).isEqualTo(sourceSearch.queries);
        assertThat(argument.getValue().user).isEqualTo(sourceSearch.user);
        assertThat(argument.getValue().queryTemplate).isEqualTo(sourceSearch.queryTemplate);

        assertThat(argument.getValue().state).isEqualTo(BatchSearchRecord.State.QUEUED);
        List<Task> tasks = taskManager.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo(BatchSearchRunner.class.getName());
    }

    @Test
    public void test_upload_batch_search_csv_less_that_2chars_queries_are_filtered() {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x").
                        addField("name","my batch search").
                        addField("description", "search description").
                        addFile(new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv")
                                .withContent("1\n" + "\n" + "query\r\n")).build()).response();

        assertThat(response.code()).isEqualTo(200);
        verify(batchSearchRepository).save(eq(new BatchSearch(response.content(),
                singletonList(project("prj")), "my batch search", "search description",
                asSet("query"), new Date(), BatchSearch.State.RUNNING, User.local())));
    }

    private void testTripleQuote(Boolean phraseMatch, String query, String tripleQuoteResult) {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x").
                        addField("name", "my batch search").
                        addFile(
                                new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv").withContent(query +
                                        "\"query two\"\r\n" +
                                        "query three\r\n" +
                                        "query\" four\r\n")).
                        addField("phrase_matches", String.valueOf(phraseMatch)).
                        build()).response();


        assertThat(response.code()).isEqualTo(200);
        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().queries.keySet()).containsOnly(tripleQuoteResult, "\"query two\"", "query three", "query\" four");
    }

    private static class MultipartContentBuilder {
        private final String boundary;
        private final List<Pair<String, String>> nameValuePairs = new LinkedList<>();
        private final List<FileUpload> files = new LinkedList<>();

        public MultipartContentBuilder(String boundary) {
            this.boundary = boundary;
        }

        public String build() {
            String fields = nameValuePairs.stream().map(nv -> format(
                    "--%s\r\n" +
                            "Content-Disposition: form-data; name=\"%s\"\r\n" +
                            "\r\n" +
                            "%s\r\n", boundary,
                    nv._1(), nv._2())).collect(Collectors.joining());
            String fileUploads = files.stream().map(fu -> format("--%s\r\n%s", boundary, fu.build())).collect(Collectors.joining());
            return fields + fileUploads + format("--%s--", boundary);
        }

        public MultipartContentBuilder addField(String name, String value) {
            nameValuePairs.add(new Pair<>(name, value));
            return this;
        }

        public MultipartContentBuilder addFile(FileUpload file) {
            files.add(file);
            return this;
        }
    }

    private static class FileUpload {
        private final String name;
        private String contentType;
        private String content;
        private String filename;

        FileUpload(String name) {
            this.name = name;
        }

        public FileUpload withContentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public FileUpload withContent(String content) {
            this.content = content;
            return this;
        }

        public String build() {
            return format("Content-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n" +
                    "Content-Type: %s\r\n" +
                    "\r\n" +
                    "%s", name, filename, contentType, content);
        }

        public FileUpload withFilename(String filename) {
            this.filename = filename;
            return this;
        }
    }
}
