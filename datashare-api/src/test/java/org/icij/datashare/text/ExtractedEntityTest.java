package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.ModelEntity;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

public class ExtractedEntityTest {
    private final ModelEntity bareKeyedSource = new ModelEntity("ftm", "person-1", Set.of("Person", "LegalEntity"),
            Set.of("4.10.2"), Set.of("doc-1", "doc-2"),
            Map.of("name", List.of("Jane Doe", "J. Doe"), "birthDate", List.of("1980-04-02")));

    @Test
    public void test_projects_every_field_of_the_model_entity() {
        ExtractedEntity entity = ExtractedEntity.from(bareKeyedSource);

        assertThat(entity.entityId()).isEqualTo("person-1");
        assertThat(entity.getId()).isEqualTo("ftm_person-1");
        assertThat(entity.model()).isEqualTo("ftm");
        assertThat(entity.modelVersions()).containsOnly("4.10.2");
        assertThat(entity.types()).containsOnly("Person", "LegalEntity");
        assertThat(entity.documentIds()).containsOnly("doc-1", "doc-2");
        assertThat(entity.properties().get("ftm_name")).containsExactly("Jane Doe", "J. Doe");
        assertThat(entity.properties().get("ftm_birthDate")).containsExactly("1980-04-02");
    }

    @Test
    public void test_the_index_type_is_the_class_name() {
        assertThat(JsonObjectMapper.getType(ExtractedEntity.from(bareKeyedSource))).isEqualTo("ExtractedEntity");
    }

    @Test
    public void test_serializes_the_namespaced_properties_as_written() {
        Map<String, Object> json = JsonObjectMapper.getJson(ExtractedEntity.from(bareKeyedSource));

        assertThat(json.get("model")).isEqualTo("ftm");
        assertThat(json.get("entityId")).isEqualTo("person-1");
        // the namespaced id lives in the _id alone, like NamedEntity's and Document's
        assertThat(json.keySet()).excludes("id");
        assertThat(((Map<?, ?>) json.get("properties")).get("ftm_birthDate")).isEqualTo(List.of("1980-04-02"));
    }

    @Test
    public void test_reads_back_the_document_it_wrote() {
        Map<String, Object> json = JsonObjectMapper.getJson(ExtractedEntity.from(bareKeyedSource));

        ExtractedEntity read = JsonObjectMapper.getObject(json, ExtractedEntity.class);

        assertThat(read).isEqualTo(ExtractedEntity.from(bareKeyedSource));
    }
}
