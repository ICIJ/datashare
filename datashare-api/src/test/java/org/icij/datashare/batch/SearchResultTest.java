package org.icij.datashare.batch;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Project;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Date;

import static org.fest.assertions.Assertions.assertThat;


public class SearchResultTest {
    @Test
    public void test_json_serialize() throws Exception {
        Project project = Project.project("prj");
        SearchResult searchResult = new SearchResult("q1", project, "docId1", "rootId1", Paths.get("/path/to/doc1"), new Date(), "content/type", 123L, 1);
        assertThat(JsonObjectMapper.writeValueAsString(searchResult))
            .contains("\"name\":\"prj\"")
            .contains("\"sourcePath\":\"file:///vault/prj\"")
            .contains("\"documentPath\":\"/path/to/doc1\"");
        SearchResult searchResultFromJson = JsonObjectMapper.readValue(("{\"query\":\"q1\"," +
                        "\"project\":\"prj\"," +
                        "\"documentId\":\"docId1\"," +
                        "\"documentPath\":\"/path/to/doc1\"," +
                        "\"creationDate\":\"1608049139794\"," +
                        "\"rootId\":\"rootId1\",\"documentNumber\":\"1\"," +
                        "\"contentType\":\"content/type\",\"contentLength\":123}"),
                SearchResult.class);
        assertThat(searchResultFromJson.project.getId()).isEqualTo("prj");
        assertThat(searchResultFromJson.documentPath.toString()).isEqualTo("/path/to/doc1");

    }

    @Test
    public void test_json_serialize_without_project() throws Exception {
        assertThat(JsonObjectMapper.writeValueAsString(new SearchResult("q1", "docId1", "rootId1", Paths.get("/path/to/doc1"),
                new Date(), "content/type", 123L, 1))).contains("\"documentPath\":\"/path/to/doc1\"");
        assertThat(JsonObjectMapper.readValue(("{\"query\":\"q1\"," +
                        "\"documentId\":\"docId1\"," +
                        "\"documentPath\":\"/path/to/doc1\"," +
                        "\"creationDate\":\"1608049139794\"," +
                        "\"rootId\":\"rootId1\",\"documentNumber\":\"1\"," +
                        "\"contentType\":\"content/type\",\"contentLength\":123}"),
                SearchResult.class).documentPath.toString()).isEqualTo("/path/to/doc1");

    }
}