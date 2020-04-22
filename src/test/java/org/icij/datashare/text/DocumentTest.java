package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Document.nerMask;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.nlp.Pipeline.Type.*;
import static org.icij.datashare.text.nlp.Pipeline.set;

public class DocumentTest {
    @Test
    public void test_json_deserialize() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(createDoc("content").build())).contains("\"projectId\":\"prj\"");
        assertThat(JsonObjectMapper.MAPPER.readValue(("{\"id\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"path\":\"file:///home/dev/src/datashare/datashare-api/path\"," +
                        "\"dirname\":\"/home/dev/src/datashare/datashare:api/\"," +
                        "\"content\":\"content\",\"language\":\"FRENCH\"," +
                        "\"extractionDate\":\"2019-05-09T16:12:17.589Z\",\"contentEncoding\":\"UTF-8\"," +
                        "\"contentType\":\"text/plain\",\"extractionLevel\":0," +
                        "\"metadata\":{},\"status\":\"INDEXED\",\"nerTags\":[]," +
                        "\"parentDocument\":null,\"rootDocument\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"contentLength\":123,\"projectId\":\"prj\", \"tags\": [\"foo\", \"bar\"]}").getBytes(),
                Document.class).getProject()).isEqualTo(project("prj"));
    }

    @Test
    public void test_json_deserialize_path_and_dirname_with_special_chars() throws Exception {
        String path = "/dir/to/docs/shared/foo/Data/2018-05/My Administrations/TGA_BAR/htmls/responses3/3#19221-Arrow CVC SET: 3-CORP 4FR X 8CM - Qux, blah, infusion, central portal|Pouet International Inc.html";

        Document document = JsonObjectMapper.MAPPER.readValue(("{\"id\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"path\":\"" + path + "\"," +
                        "\"dirname\":\"" + get(path).getParent() + "\"," +
                        "\"content\":\"content\",\"language\":\"FRENCH\"," +
                        "\"extractionDate\":\"2019-05-09T16:12:17.589Z\",\"contentEncoding\":\"UTF-8\"," +
                        "\"contentType\":\"text/plain\",\"extractionLevel\":0," +
                        "\"metadata\":{},\"status\":\"INDEXED\",\"nerTags\":[]," +
                        "\"parentDocument\":null,\"rootDocument\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"contentLength\":123,\"projectId\":\"prj\", \"tags\": [\"foo\", \"bar\"]}").getBytes(),
                Document.class);

        assertThat(document.getPath().toString()).isEqualTo(path);
        assertThat(document.getDirname().toString()).isEqualTo(get(path).getParent().toString());
    }

    @Test
    public void test_ner_mask() {
        assertThat(nerMask(new HashSet<>())).isEqualTo((short) 0);
        assertThat(nerMask(set(CORENLP))).isEqualTo((short) 1);
        assertThat(nerMask(set(CORENLP, GATENLP))).isEqualTo((short) 3);
        assertThat(nerMask(set(OPENNLP, CORENLP))).isEqualTo((short) 17);
    }

    @Test
    public void test_from_mask() {
        assertThat(Document.fromNerMask(0)).isEmpty();
        assertThat(Document.fromNerMask(1)).contains(CORENLP);
        assertThat(Document.fromNerMask(5)).contains(CORENLP, IXAPIPE);
        assertThat(Document.fromNerMask(31)).contains(CORENLP, GATENLP, IXAPIPE, MITIE, OPENNLP);
    }

    @Test
    public void test_creation_date_without_zone() {
        assertThat(createDoc("name").with(new HashMap<String, Object>() {{
            put("tika_metadata_creation_date", "2019-02-04T11:37:30.368441317");}}).build().getCreationDate()).isNotNull();
    }

    @Test
    public void test_creation_date_zoned() {
        assertThat(createDoc("name").with(new HashMap<String, Object>() {{
            put("tika_metadata_creation_date", "2019-02-04T11:37:30Z");}}).build().getCreationDate()).isNotNull();
    }

    @Test
    public void test_creation_date_unparseable() {
        assertThat(createDoc("name").with(new HashMap<String, Object>() {{
            put("tika_metadata_creation_date", "not a date");}}).build().getCreationDate()).isNull();
    }
}
