package org.icij.datashare;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.StatementRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OUTPUT_OPT;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CliAppEntitiesExportTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final Repository repository = mock(Repository.class);
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
        when(repository.getProject("prj")).thenReturn(project("prj"));
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
        when(statements.entities(eq("prj"), eq("ftm"), any())).thenAnswer(
                call -> call.<Function<Stream<ModelEntity>, Object>>getArgument(2).apply(Stream.of(entities)));
    }

    private static ModelEntity entity(String id, String type) {
        return new ModelEntity("ftm", id, type, Set.of(), Set.of(), Map.of("name", List.of("Ada")));
    }

    @Test
    public void test_writes_ndjson_to_stdout() {
        projectHolds(entity("p-1", "Person"));

        int exit = CliApp.handleEntitiesExport(repository, statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString(UTF_8)).contains("\"id\":\"p-1\"").endsWith("\n");
    }

    @Test
    public void test_output_writes_the_file_and_nothing_on_stdout() throws Exception {
        projectHolds(entity("p-1", "Person"));
        Path file = folder.getRoot().toPath().resolve("prj.ftm.json");

        int exit = CliApp.handleEntitiesExport(repository, statements, export("prj", "ftm", file.toString()));

        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(file)).contains("\"id\":\"p-1\"");
        assertThat(stdout.toString(UTF_8)).isEmpty();
    }

    @Test
    public void test_a_model_with_no_exporter_exits_5_without_reading() {
        int exit = CliApp.handleEntitiesExport(repository, statements, export("prj", "wikidata", null));

        assertThat(exit).isEqualTo(5);
        assertThat(stderr.toString(UTF_8)).contains("unknown data model 'wikidata'");
        verify(statements, never()).entities(anyString(), anyString(), any());
    }

    @Test
    public void test_an_unknown_project_exits_3() {
        int exit = CliApp.handleEntitiesExport(repository, statements, export("nope", "ftm", null));

        assertThat(exit).isEqualTo(3);
        assertThat(stderr.toString(UTF_8)).contains("project 'nope' does not exist");
    }

    @Test
    public void test_an_output_in_a_missing_directory_exits_1_with_a_message() {
        projectHolds(entity("p-1", "Person"));
        String file = folder.getRoot().toPath().resolve("missing/prj.ftm.json").toString();

        int exit = CliApp.handleEntitiesExport(repository, statements, export("prj", "ftm", file));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(UTF_8)).contains("error: ").contains("missing");
    }

    @Test
    public void test_an_entity_the_ontology_no_longer_knows_exits_1_naming_its_type() {
        projectHolds(entity("r-1", "Robot"));

        int exit = CliApp.handleEntitiesExport(repository, statements, export("prj", "ftm", null));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(UTF_8)).contains("Robot");
    }
}
