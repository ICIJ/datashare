package org.icij.datashare.tabular;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static org.fest.assertions.Assertions.assertThat;

/**
 * A csv and the mapping over it, read end to end: the entities a file states, the relationship
 * between them, and the same ids on a second read.
 */
public class MappingExecutionTest {
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
            "doc-1", RowSourceOptions.defaults(), Map.of(
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
        MappingExecutor executor = new MappingExecutor(MAPPING);
        List<Statement> statements;
        try (InputStream source = new ByteArrayInputStream(CSV.getBytes(UTF_8));
             Stream<Row> rows = new DelimitedRowSource().rows(source, RowSourceOptions.defaults())) {
            statements = rows.flatMap(row -> executor.statements(row).stream()).toList();
        }

        Map<String, ModelEntity> entities = statements.stream().collect(groupingBy(Statement::entityId))
                .values().stream().map(statementGroup -> ModelEntity.from(statementGroup, Set.of()))
                .collect(toMap(entity -> entity.types().iterator().next(), entity -> entity));

        assertThat(entities.keySet()).contains("Person", "Company", "Employment");
        assertThat(entities.get("Person").properties().get("name")).containsExactly("Jane Doe");
        assertThat(entities.get("Person").properties().get("birthDate")).containsExactly("1970-03-01");
        assertThat(entities.get("Company").properties().get("name")).containsExactly("ACME");
        assertThat(entities.get("Employment").properties().get("role")).containsExactly("Director");
        assertThat(entities.get("Employment").properties().get("employee"))
                .containsExactly(entities.get("Person").id());
        assertThat(entities.get("Employment").properties().get("employer"))
                .containsExactly(entities.get("Company").id());
        assertThat(executor.skipped().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(0L);
    }

    @Test
    public void test_the_same_file_read_twice_produces_the_same_statement_ids() throws Exception {
        List<String> first = run().stream().map(Statement::id).sorted().toList();

        assertThat(first).isNotEmpty();
        assertThat(run().stream().map(Statement::id).sorted().toList()).isEqualTo(first);
    }

    private List<Statement> run() throws Exception {
        MappingExecutor executor = new MappingExecutor(MAPPING);
        try (InputStream source = new ByteArrayInputStream(CSV.getBytes(UTF_8));
             Stream<Row> rows = new DelimitedRowSource().rows(source, RowSourceOptions.defaults())) {
            return rows.flatMap(row -> executor.statements(row).stream()).toList();
        }
    }
}
