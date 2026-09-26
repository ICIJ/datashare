package org.icij.datashare.tabular;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static org.fest.assertions.Assertions.assertThat;

public class StatementBuilderCsvTest {
    private static final String CSV = """
            person_id,full_name,born,company_id,company_name,job_title
            p-1,Jane Doe,01/03/1970,c-1,ACME,Director
            """;

    private static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    private static ExtractionMapping.PropertyMapping reference(String alias) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, null, alias, null);
    }

    private static final ExtractionMapping MAPPING = new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm",
            "doc-1", null, RowSourceOptions.defaults(), Map.of(
            "person", new ExtractionMapping.EntityMapping("Person", List.of("person_id"), Map.of(
                    "name", column("full_name"),
                    "birthDate", new ExtractionMapping.PropertyMapping(
                            List.of("born"), null, null, null, "dd/MM/yyyy"))),
            "company", new ExtractionMapping.EntityMapping("Company", List.of("company_id"), Map.of(
                    "name", column("company_name"))),
            "job", new ExtractionMapping.EntityMapping("Employment", List.of("person_id", "company_id"), Map.of(
                    "employee", reference("person"),
                    "employer", reference("company"),
                    "role", column("job_title")))));

    @Test
    public void test_a_csv_and_a_mapping_produce_two_entities_and_the_relationship_between_them() throws Exception {
        StatementBuilder builder = new StatementBuilder(MAPPING, "");
        List<Statement> statements;
        try (InputStream source = new ByteArrayInputStream(CSV.getBytes(UTF_8));
             Rows rows = new DelimitedRowSource().rows(source, RowSourceOptions.defaults())) {
            statements = rows.rows().flatMap(row -> builder.statements(row).stream()).toList();
        }

        Map<String, ModelEntity> entities = statements.stream().collect(groupingBy(Statement::entityId))
                .values().stream().map(statementGroup -> ModelEntity.from(statementGroup, Set.of()))
                .collect(toMap(ModelEntity::type, entity -> entity));

        assertThat(entities.keySet()).contains("Person", "Company", "Employment");
        assertThat(entities.get("Person").properties().get("name")).containsExactly("Jane Doe");
        assertThat(entities.get("Person").properties().get("birthDate")).containsExactly("1970-03-01");
        assertThat(entities.get("Company").properties().get("name")).containsExactly("ACME");
        assertThat(entities.get("Employment").properties().get("role")).containsExactly("Director");
        assertThat(entities.get("Employment").properties().get("employee"))
                .containsExactly(entities.get("Person").id());
        assertThat(entities.get("Employment").properties().get("employer"))
                .containsExactly(entities.get("Company").id());
        assertThat(builder.skipped().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(0L);
    }
}
