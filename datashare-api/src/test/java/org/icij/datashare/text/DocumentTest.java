package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Document.nerMask;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.nlp.Pipeline.Type.*;
import static org.icij.datashare.text.nlp.Pipeline.set;

public class DocumentTest {
    @Test
    public void test_json_deserialize() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(createDoc("content"))).contains("\"projectId\":\"prj\"");
        assertThat(JsonObjectMapper.MAPPER.readValue(("{\"id\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"path\":\"file:///home/dev/src/datashare/datashare-api/path\"," +
                        "\"dirname\":\"file:///home/dev/src/datashare/datashare-api/\"," +
                        "\"content\":\"content\",\"language\":\"FRENCH\"," +
                        "\"extractionDate\":\"2019-05-09T16:12:17.589Z\",\"contentEncoding\":\"UTF-8\"," +
                        "\"contentType\":\"text/plain\",\"extractionLevel\":0," +
                        "\"metadata\":{},\"status\":\"INDEXED\",\"nerTags\":[]," +
                        "\"parentDocument\":null,\"rootDocument\":\"45a0a224c2836b4c558f3b56e2a1c69c21fcc8b3f9f4f99f2bc49946acfb28d8\"," +
                        "\"contentLength\":123,\"projectId\":\"prj\", \"tags\": [\"foo\", \"bar\"]}").getBytes(),
                Document.class).getProject()).isEqualTo(project("prj"));
    }

    @Test
    public void test_ner_mask() {
        assertThat(nerMask(new HashSet<>())).isEqualTo(0);
        assertThat(nerMask(set(CORENLP))).isEqualTo(1);
        assertThat(nerMask(set(CORENLP, GATENLP))).isEqualTo(3);
        assertThat(nerMask(set(OPENNLP, CORENLP))).isEqualTo(17);
    }

    @Test
    public void test_from_mask() {
        assertThat(Document.fromNerMask(0)).isEmpty();
        assertThat(Document.fromNerMask(1)).contains(CORENLP);
        assertThat(Document.fromNerMask(5)).contains(CORENLP, IXAPIPE);
        assertThat(Document.fromNerMask(31)).contains(CORENLP, GATENLP, IXAPIPE, MITIE, OPENNLP);
    }

    private Document createDoc(String name) {
        return new Document(project("prj"), "docid", Paths.get("/path/to/").resolve(name), name,
                FRENCH, Charset.defaultCharset(),
                "text/plain", new HashMap<>(), Document.Status.INDEXED,
                new HashSet<>(), new Date(), null, null,
                0, 123L);
    }
}
