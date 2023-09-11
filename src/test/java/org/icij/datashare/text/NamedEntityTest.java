package org.icij.datashare.text;

import java.util.List;
import java.util.Map;
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
                        "\"extractor\":\"CORENLP\",\"extractorLanguage\":\"ENGLISH\",\"isHidden\":false," +
                    "\"metadata\":null,\"mentionNorm\":\"mention\"," +
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

    @Test
    public void test_serialize_with_metadata() throws Exception {
        Map<String, Object> meta = Map.of("some", "metadata");
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(NamedEntity.create(
                NamedEntity.Category.PERSON, "mention", asList(123L), "docId", "rootId",
                Pipeline.Type.CORENLP, Language.ENGLISH, meta))).
                isEqualTo("{\"category\":\"PERSON\",\"mention\":\"mention\",\"offsets\":[123]," +
                        "\"extractor\":\"CORENLP\",\"extractorLanguage\":\"ENGLISH\",\"isHidden\":false," +
                    "\"metadata\":{\"some\":\"metadata\"},\"mentionNorm\":\"mention\"," +
                        "\"partsOfSpeech\":null,\"mentionNormTextLength\":7}");
    }

    @Test
    public void test_id_with_metadata() {
        // Given
        Map<String, Object> meta = Map.of("some", "meta", "da", "ta");
        Map<String, Object> sameMetaDifferentOrder = Map.of("da", "ta", "some", "meta");
        Map<String, Object> otherMeta = Map.of("some", "othermetadata");

        // When
        NamedEntity withMeta = NamedEntity.create(NamedEntity.Category.PERSON, "mention", List.of(123L), "docId", "rootId",
                Pipeline.Type.CORENLP, Language.ENGLISH, meta);
        NamedEntity withSameMeta = NamedEntity.create(NamedEntity.Category.PERSON, "mention", List.of(123L), "docId", "rootId",
                Pipeline.Type.CORENLP, Language.ENGLISH, sameMetaDifferentOrder);
        NamedEntity withOtherMeta = NamedEntity.create(NamedEntity.Category.PERSON, "mention", List.of(123L), "docId", "rootId",
                Pipeline.Type.CORENLP, Language.ENGLISH, otherMeta);

        // Then
        assertThat(withMeta.getId()).isEqualTo(withSameMeta.getId());
        assertThat(withMeta.getId()).isNotEqualTo(withOtherMeta.getId());
    }
}
