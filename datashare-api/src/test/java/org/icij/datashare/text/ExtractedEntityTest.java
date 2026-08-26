package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.ModelEntity;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

public class ExtractedEntityTest {
    // Bare keys, the shape JooqStatementRepository.toRow actually produces once it strips the
    // model prefix off the stored property.
    private final ModelEntity source = new ModelEntity("ftm", "person-1", Set.of("Person", "LegalEntity"),
            Set.of("4.10.2"), Set.of("doc-1", "doc-2"),
            Map.of("name", List.of("Jane Doe", "J. Doe"), "birthDate", List.of("1980-04-02")));

    @Test
    public void test_projects_every_field_of_the_model_entity() {
        ExtractedEntity entity = ExtractedEntity.from(source);

        assertThat(entity.getId()).isEqualTo("person-1");
        assertThat(entity.model()).isEqualTo("ftm");
        assertThat(entity.modelVersions()).containsOnly("4.10.2");
        assertThat(entity.types()).containsOnly("Person", "LegalEntity");
        assertThat(entity.documentIds()).containsOnly("doc-1", "doc-2");
        assertThat(entity.properties().get("ftm:name")).containsExactly("Jane Doe", "J. Doe");
        assertThat(entity.properties().get("ftm:birthDate")).containsExactly("1980-04-02");
    }

    @Test
    public void test_the_index_type_is_the_class_name() {
        assertThat(JsonObjectMapper.getType(ExtractedEntity.from(source))).isEqualTo("ExtractedEntity");
    }

    @Test
    public void test_serializes_the_namespaced_properties_as_written() {
        Map<String, Object> json = JsonObjectMapper.getJson(ExtractedEntity.from(source));

        assertThat(json.get("model")).isEqualTo("ftm");
        assertThat(((Map<?, ?>) json.get("properties")).get("ftm:birthDate")).isEqualTo(List.of("1980-04-02"));
    }

    @Test
    public void test_reads_back_the_document_it_wrote() {
        Map<String, Object> json = JsonObjectMapper.getJson(ExtractedEntity.from(source));

        ExtractedEntity read = JsonObjectMapper.getObject(json, ExtractedEntity.class);

        assertThat(read).isEqualTo(ExtractedEntity.from(source));
    }
}
