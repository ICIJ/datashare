package org.icij.datashare.tabular;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

/** A mapping authored by hand is read strictly, and every reason it is refused names the field, not a Java type. */
public class ExtractionMappingReaderTest {
    private static final String MAPPING = """
            {"id": "m1", "projectId": "prj", "name": "companies", "model": "ftm", "documentId": "docId",
             "entities": {"c": {"type": "Company", "keys": ["id"], "properties": {"name": {"columns": ["name"]}}}}}
            """;

    @Test
    public void test_read_builds_the_mapping() throws Exception {
        assertThat(read(MAPPING).entities().get("c").type()).isEqualTo("Company");
    }

    @Test
    public void test_read_refuses_a_misspelled_field_instead_of_dropping_it() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("\"documentId\": \"docId\"", "\"documentId\": \"docId\", \"rootID\": \"zip\"")));

        assertThat(malformed.getMessage()).isEqualTo("unknown field 'rootID'");
    }

    @Test
    public void test_read_names_the_path_of_a_misspelled_nested_field() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("{\"columns\": [\"name\"]}", "{\"columns\": [\"name\"], \"dateformat\": \"yyyy\"}")));

        assertThat(malformed.getMessage()).isEqualTo("unknown field 'entities.c.properties.name.dateformat'");
    }

    @Test
    public void test_read_names_a_missing_field() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("\"name\": \"companies\", ", "")));

        assertThat(malformed.getMessage()).isEqualTo("missing field 'name'");
    }

    @Test
    public void test_read_names_a_field_of_the_wrong_type() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replaceFirst("\"entities\": \\{.*}", "\"entities\": \"none\"}")));

        assertThat(malformed.getMessage()).isEqualTo("field 'entities' has the wrong type");
    }

    @Test
    public void test_read_keeps_the_reason_a_mapping_is_refused() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("{\"columns\": [\"name\"]}", "{\"columns\": [\"name\"], \"literal\": \"x\"}")));

        assertThat(malformed.getMessage()).startsWith("entities.c.properties.name: a property is filled from exactly one");
    }

    @Test
    public void test_read_names_the_field_holding_a_null_key() {
        assertRefused(MAPPING.replace("\"keys\": [\"id\"]", "\"keys\": [null]"), "field 'entities.c' holds a null");
    }

    @Test
    public void test_read_names_the_field_holding_a_null_column() {
        assertRefused(MAPPING.replace("\"columns\": [\"name\"]", "\"columns\": [null]"),
                      "field 'entities.c.properties.name' holds a null");
    }

    @Test
    public void test_read_names_the_field_holding_a_null_entity() {
        assertRefused(MAPPING.replaceFirst("\"entities\": \\{.*}", "\"entities\": {\"c\": null}}"),
                      "field 'entities.c' holds a null");
    }

    @Test
    public void test_read_names_the_field_holding_a_null_entity_whose_alias_has_a_space() {
        assertRefused(MAPPING.replaceFirst("\"entities\": \\{.*}", "\"entities\": {\"my company\": null}}"),
                      "field 'entities.my company' holds a null");
    }

    @Test
    public void test_read_names_the_field_holding_a_null_property() {
        assertRefused(MAPPING.replace("{\"columns\": [\"name\"]}", "null"), "field 'entities.c' holds a null");
    }

    @Test
    public void test_parse_refuses_a_repeated_field_instead_of_keeping_the_last() {
        assertRefused(MAPPING.replace("\"id\": \"m1\"", "\"id\": \"m1\", \"id\": \"m2\""), "Duplicate field 'id'");
    }

    @Test
    public void test_parse_refuses_a_repeated_entity_alias_instead_of_dropping_one() {
        String entity = "\"c\": {\"type\": \"Company\", \"keys\": [], \"properties\": {\"name\": {\"literal\": \"x\"}}}";
        assertRefused(MAPPING.replaceFirst("\"entities\": \\{", "\"entities\": {" + entity + ", "), "Duplicate field 'c'");
    }

    @Test
    public void test_parse_refuses_content_after_the_mapping_instead_of_dropping_it() {
        assertRefused(MAPPING + MAPPING, "the mapping has content after its closing brace");
    }

    @Test
    public void test_parse_refuses_a_document_that_is_not_an_object() {
        for (String json : new String[] {"", "null", "[" + MAPPING + "]"}) {
            MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class, () -> read(json));

            assertThat(malformed.getMessage()).isEqualTo("the mapping is not a JSON object");
        }
    }

    private static void assertRefused(String json, String message) {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class, () -> read(json));

        assertThat(malformed.getMessage()).isEqualTo(message);
    }

    private static ExtractionMapping read(String json) throws Exception {
        return ExtractionMappingReader.read(ExtractionMappingReader.parse(json.getBytes()));
    }
}
