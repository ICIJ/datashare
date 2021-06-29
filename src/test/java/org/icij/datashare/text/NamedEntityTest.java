package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

public class NamedEntityTest {
    @Test
    public void test_serialize() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(NamedEntity.create(
                NamedEntity.Category.PERSON, "mention", asList(123L), "docId", "rootId",
                Pipeline.Type.CORENLP, Language.ENGLISH))).
                isEqualTo("{\"category\":\"PERSON\",\"mention\":\"mention\",\"offsets\":[123]," +
                        "\"extractor\":\"CORENLP\",\"extractorLanguage\":\"ENGLISH\",\"isHidden\":false,\"mentionNorm\":\"mention\"," +
                        "\"partsOfSpeech\":null,\"mentionNormTextLength\":7}");
    }

    @Test
    public void test_serialize_contains_mention_norm_text_length() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(NamedEntity.create(
                NamedEntity.Category.PERSON, "猫", asList(123L), "docId", "rootId",
                Pipeline.Type.CORENLP, Language.JAPANESE)))
                    .contains("\"mentionNormTextLength\":3")
                    .contains("\"mentionNorm\":\"mao\"");
    }
}
