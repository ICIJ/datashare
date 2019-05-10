package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;

public class DocumentTest {
    @Test
    public void test_json_project() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(new Document(project("prj"), Paths.get("path"), "content",
                        Language.FRENCH, Charset.defaultCharset(), "text/plain",
                        new HashMap<>(), Document.Status.INDEXED, 123L))).contains("\"projectId\":\"prj\"");
        assertThat(JsonObjectMapper.MAPPER.readValue(("{\"id\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"path\":\"file:///home/dev/src/datashare/datashare-api/path\"," +
                        "\"dirname\":\"file:///home/dev/src/datashare/datashare-api/\"," +
                        "\"content\":\"content\",\"language\":\"FRENCH\"," +
                        "\"extractionDate\":\"2019-05-09T16:12:17.589Z\",\"contentEncoding\":\"UTF-8\"," +
                        "\"contentType\":\"text/plain\",\"extractionLevel\":0," +
                        "\"metadata\":{},\"status\":\"INDEXED\",\"nerTags\":[]," +
                        "\"parentDocument\":null,\"rootDocument\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"contentLength\":123,\"projectId\":\"prj\"}").getBytes(),
                Document.class).getProject()).isEqualTo(project("prj"));
    }
}
