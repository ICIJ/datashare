package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class NamedEntityTest {
    @Test
    public void test_serialize() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(NamedEntity.create(
                NamedEntity.Category.PERSON, "mention", 123L, "docId",
                Pipeline.Type.CORENLP, Language.ENGLISH))).
                isEqualTo("{\"category\":\"PERSON\",\"mention\":\"mention\",\"offset\":123," +
                        "\"documentId\":\"docId\",\"rootDocument\":\"docId\",\"" +
                        "extractor\":\"CORENLP\",\"extractorLanguage\":\"ENGLISH\",\"isHidden\":false,\"mentionNorm\":\"mention\"," +
                        "\"id\":\"e679ebaefcb396f38ac099a2c1e95a7f3bded26ab4e1925f00767544e0d530fa7f18b922e137256e4b1222b0d66a6266\"," +
                        "\"partsOfSpeech\":null}");
    }
}
