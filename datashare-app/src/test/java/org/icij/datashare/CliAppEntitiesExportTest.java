package org.icij.datashare;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.StatementRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OUTPUT_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CliAppEntitiesExportTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final StatementRepository statements = mock(StatementRepository.class);
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @Before
    public void setUp() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(stdout));
        System.setErr(new PrintStream(stderr));
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private static Properties export(String project, String model, String output) {
        Properties properties = new Properties();
        properties.setProperty(DEFAULT_PROJECT_OPT, project);
        properties.setProperty(ENTITIES_EXPORT_OPT, model);
        if (output != null) {
            properties.setProperty(ENTITIES_EXPORT_OUTPUT_OPT, output);
        }
        return properties;
    }

    private void projectHolds(ModelEntity... entities) {
        projectHolds(Stream.of(entities));
    }

    private void projectHolds(Stream<ModelEntity> entities) {
        when(statements.entities(eq("prj"), eq("ftm"), any())).thenAnswer(
                call -> call.<Function<Stream<ModelEntity>, Object>>getArgument(2).apply(entities));
    }

    private static ModelEntity entity(String id, String type) {
        return new ModelEntity("ftm", id, type, Set.of(), Set.of(), Map.of("name", List.of("Ada")));
    }

    private static ModelEntity anEntityNoOntologyKnows() {
        return entity("r-1", "Robot");
    }

    private static Stream<ModelEntity> countedEntities(AtomicInteger read, int count) {
        return Stream.iterate(0, i -> i + 1).limit(count).map(i -> entity("p-" + i, "Person"))
                     .peek(entity -> read.incrementAndGet());
    }

    private static PrintStream brokenStdout() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
        });
    }

    @Test
    public void test_writes_ndjson_to_stdout() {
        projectHolds(entity("p-1", "Person"));

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString(UTF_8)).contains("\"id\":\"p-1\"").endsWith("\n");
    }

    @Test
    public void test_output_writes_the_file_and_nothing_on_stdout() throws Exception {
        projectHolds(entity("p-1", "Person"));
        Path file = folder.getRoot().toPath().resolve("prj.ftm.json");

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", file.toString()));

        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(file)).contains("\"id\":\"p-1\"");
        assertThat(stdout.toString(UTF_8)).isEmpty();
    }

    @Test
    public void test_a_model_with_no_exporter_exits_5_without_reading() {
        int exit = CliApp.handleEntitiesExport(statements, export("prj", "wikidata", null));

        assertThat(exit).isEqualTo(5);
        assertThat(stderr.toString(UTF_8)).contains("unknown data model 'wikidata'");
        verify(statements, never()).entities(anyString(), anyString(), any());
    }

    @Test
    public void test_a_project_holding_nothing_exports_nothing() {
        when(statements.entities(eq("nope"), eq("ftm"), any())).thenAnswer(
                call -> call.<Function<Stream<ModelEntity>, Object>>getArgument(2).apply(Stream.of()));

        int exit = CliApp.handleEntitiesExport(statements, export("nope", "ftm", null));

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString(UTF_8)).doesNotContain("\"id\"");
    }

    @Test
    public void test_an_output_the_platform_cannot_parse_exits_5_without_reading() {
        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", "prj\u0000.ftm.json"));

        assertThat(exit).isEqualTo(5);
        assertThat(stderr.toString(UTF_8)).contains("error: not a valid path: ");
        verify(statements, never()).entities(anyString(), anyString(), any());
    }

    @Test
    public void test_an_output_in_a_missing_directory_exits_5_with_a_message() {
        projectHolds(entity("p-1", "Person"));
        String file = folder.getRoot().toPath().resolve("missing/prj.ftm.json").toString();

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", file));

        assertThat(exit).isEqualTo(5);
        assertThat(stderr.toString(UTF_8)).contains("error: no such file: " + file);
    }

    @Test
    public void test_a_failed_export_keeps_the_previous_output_file() throws Exception {
        projectHolds(anEntityNoOntologyKnows());
        Path file = folder.getRoot().toPath().resolve("prj.ftm.json");
        Files.writeString(file, "previous export");

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", file.toString()));

        assertThat(exit).isEqualTo(1);
        assertThat(Files.readString(file)).isEqualTo("previous export");
        assertThat(folder.getRoot().list()).containsOnly("prj.ftm.json");
    }

    @Test
    public void test_a_stdout_that_cannot_be_written_exits_1() {
        projectHolds(entity("p-1", "Person"));
        System.setOut(brokenStdout());

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(UTF_8)).contains("error: ");
    }

    @Test
    public void test_a_closed_stdout_stops_reading_the_project() {
        AtomicInteger read = new AtomicInteger();
        projectHolds(countedEntities(read, 100_000));
        System.setOut(brokenStdout());

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(1);
        assertThat(read.get()).isLessThan(1_000);
    }

    @Test
    public void test_an_output_that_is_a_directory_exits_5_and_keeps_it() throws Exception {
        projectHolds(entity("p-1", "Person"));
        Path dir = folder.newFolder("exports").toPath();

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", dir.toString()));

        assertThat(exit).isEqualTo(5);
        assertThat(Files.isDirectory(dir)).isTrue();
        assertThat(stderr.toString(UTF_8)).contains("error: is a directory: " + dir);
    }

    @Test
    public void test_a_failure_without_message_names_the_exception() {
        when(statements.entities(eq("prj"), eq("ftm"), any())).thenThrow(new IllegalStateException());

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(UTF_8)).contains("error: java.lang.IllegalStateException");
    }

    @Test
    public void test_a_database_failure_exits_1_with_its_message() {
        when(statements.entities(eq("prj"), eq("ftm"), any())).thenThrow(new IllegalStateException("db down"));

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(UTF_8)).contains("error: db down");
    }

    @Test
    public void test_an_entity_the_ontology_no_longer_knows_exits_1_naming_its_type() {
        projectHolds(anEntityNoOntologyKnows());

        int exit = CliApp.handleEntitiesExport(statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(UTF_8)).contains("Robot");
    }
}
