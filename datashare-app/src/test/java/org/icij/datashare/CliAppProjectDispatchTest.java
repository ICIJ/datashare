package org.icij.datashare;

import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectCreateRequest;
import org.icij.datashare.project.admin.ProjectCreated;
import org.icij.datashare.project.admin.ProjectExistsException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LABEL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CliAppProjectDispatchTest {

    private ProjectAdminService service;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @Before
    public void setUp() {
        service = mock(ProjectAdminService.class);
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

    @Test
    public void test_create_happy_path_text_output() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "Foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, true, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_LABEL_OPT, "Foo");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString()).contains("created project 'foo'");
        assertThat(stdout.toString()).contains("index=created");
    }

    @Test
    public void test_create_conflict_returns_4() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenThrow(new ProjectExistsException("foo"));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(4);
        assertThat(stderr.toString()).contains("already exists");
    }

    @Test
    public void test_create_validation_returns_5() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenThrow(new org.icij.datashare.project.admin.ValidationException("name", "bad"));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_create_if_not_exists_routes_to_createIfNotExists() throws Exception {
        when(service.createIfNotExists(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, false, true));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_IF_NOT_EXISTS_OPT, "true");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString()).contains("already exists (no-op)");
        verify(service).createIfNotExists(any(ProjectCreateRequest.class));
    }

    @Test
    public void test_create_json_output() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, true, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_JSON_OPT, "true");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        // Logback may emit its startup banner to the captured System.out before
        // our payload (mirrors the pattern in CliAppUserDispatchTest), so we
        // assert on inclusion rather than position.
        String captured = stdout.toString();
        assertThat(captured).contains("\"created\":true");
        assertThat(captured).contains("\"noop\":false");
        assertThat(captured).contains("\"name\":\"foo\"");
        assertThat(captured).contains("\"indexCreated\":true");
    }
}
