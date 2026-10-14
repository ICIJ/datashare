package org.icij.datashare.model.ftm;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.StatementRepository;
import org.junit.Test;
import tech.followthemoney.entity.ValueEntity;
import tech.followthemoney.model.Model;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FtmJsonExporterTest {
    private final FtmJsonExporter exporter = new FtmJsonExporter();

    private static ModelEntity entity(String model, String id, String type, Map<String, List<String>> properties) {
        return new ModelEntity(model, id, type, Set.of(), Set.of(), properties);
    }

    private String export(ModelEntity... entities) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.export(Stream.of(entities), out);
        return out.toString(UTF_8);
    }

    private static ValueEntity read(String line) throws Exception {
        return ValueEntity.fromJson(Model.loadDefault(), JsonObjectMapper.getMapper().readTree(line));
    }

    @Test
    public void test_every_line_reads_back_through_the_ftm_library() throws Exception {
        List<String> lines = export(
                entity("ftm", "p-1", "Person", Map.of("name", List.of("Ada Lovelace"), "birthDate", List.of("1815-12-10"))),
                entity("ftm", "c-1", "Company", Map.of("name", List.of("Analytical Engines Ltd")))).lines().toList();

        assertThat(lines.size()).isEqualTo(2);
        ValueEntity person = read(lines.get(0));
        assertThat(person.getId()).isEqualTo("p-1");
        assertThat(person.getSchema().getName()).isEqualTo("Person");
        assertThat(person.getValues(person.getSchema().getProperty("name"))).containsOnly("Ada Lovelace");
        assertThat(person.getValues(person.getSchema().getProperty("birthDate"))).containsOnly("1815-12-10");
        ValueEntity company = read(lines.get(1));
        assertThat(company.getId()).isEqualTo("c-1");
        assertThat(company.getSchema().getName()).isEqualTo("Company");
    }

    @Test
    public void test_every_entity_ends_with_a_newline() {
        assertThat(export(entity("ftm", "p-1", "Person", Map.of("name", List.of("Ada"))))).endsWith("}\n");
    }

    @Test
    public void test_a_value_with_a_newline_a_quote_and_non_ascii_text_stays_on_one_line() throws Exception {
        String value = "Zoë \"北京\"\nsecond line";

        String written = export(entity("ftm", "p-1", "Person", Map.of("name", List.of(value))));

        assertThat(written.lines().count()).isEqualTo(1);
        ValueEntity person = read(written.strip());
        assertThat(person.getValues(person.getSchema().getProperty("name"))).containsOnly(value);
    }

    @Test
    public void test_an_empty_stream_writes_nothing() {
        assertThat(export()).isEmpty();
    }

    @Test
    public void test_a_project_export_reads_only_the_ftm_entities_of_the_project() {
        StatementRepository statements = mock(StatementRepository.class);
        when(statements.entities(eq("prj"), eq("ftm"), any())).thenAnswer(
                call -> call.<Function<Stream<ModelEntity>, Object>>getArgument(2)
                            .apply(Stream.of(entity("ftm", "p-1", "Person", Map.of("name", List.of("Ada"))))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        exporter.export(statements, "prj", out);

        assertThat(out.toString(UTF_8)).contains("\"id\":\"p-1\"");
    }
}
