package org.icij.datashare;

import org.icij.datashare.cli.Prompter;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectCreateRequest;
import org.icij.datashare.project.admin.ProjectCreated;
import org.icij.datashare.project.admin.ProjectDeleteOptions;
import org.icij.datashare.project.admin.ProjectDeleted;
import org.icij.datashare.project.admin.ProjectExistsException;
import org.icij.datashare.project.admin.ProjectNotFoundException;
import org.icij.datashare.project.admin.ProjectStats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.function.Supplier;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_CREATION_DATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LABEL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_UPDATE_DATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_IF_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_KEEP_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_YES_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                        "*.*.*.*", null, null, null, null, null, null, true, false));
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
                        "*.*.*.*", null, null, null, null, null, null, false, true));
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
                        "*.*.*.*", null, null, null, null, null, null, true, false));
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

    private static Supplier<Prompter> alwaysConfirming(String expected) {
        return () -> {
            Prompter mock = mock(Prompter.class);
            when(mock.promptString(any(), any())).thenReturn(expected);
            return mock;
        };
    }

    private static Supplier<Prompter> alwaysDeclining() {
        // Models the real Prompter contract: when the validator inside the
        // callback throws InvalidValueException on every retry, promptString
        // exhausts MAX_RETRIES and propagates ValidationFailedException.
        return () -> {
            Prompter mock = mock(Prompter.class);
            when(mock.promptString(any(), any()))
                    .thenThrow(new Prompter.ValidationFailedException(
                            "name", "typed name does not match"));
            return mock;
        };
    }

    @Test
    public void test_delete_happy_path_with_yes_skips_prompt() throws Exception {
        when(service.stats(eq("foo"), eq(true))).thenReturn(ProjectStats.of("foo", 42L, 3));
        when(service.delete(eq("foo"), any(ProjectDeleteOptions.class)))
                .thenReturn(new ProjectDeleted("foo", true, true, true, true, false, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "foo");
        props.setProperty(PROJECT_DELETE_YES_OPT, "true");

        int exit = CliApp.handleProjectDelete(service, props, alwaysDeclining());  // prompt should not be reached

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString()).contains("deleted project 'foo'");
        verify(service).delete(eq("foo"), any(ProjectDeleteOptions.class));
    }

    @Test
    public void test_delete_with_typed_name_confirmation_proceeds() throws Exception {
        when(service.stats(eq("foo"), eq(true))).thenReturn(ProjectStats.of("foo", 42L, 3));
        when(service.delete(eq("foo"), any(ProjectDeleteOptions.class)))
                .thenReturn(new ProjectDeleted("foo", true, true, true, true, false, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "foo");

        int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("foo"));

        assertThat(exit).isEqualTo(0);
        verify(service).delete(eq("foo"), any(ProjectDeleteOptions.class));
    }

    @Test
    public void test_delete_aborts_when_typed_name_mismatches() throws Exception {
        when(service.stats(eq("foo"), eq(true))).thenReturn(ProjectStats.of("foo", 42L, 3));
        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "foo");

        int exit = CliApp.handleProjectDelete(service, props, alwaysDeclining());

        assertThat(exit).isEqualTo(0);
        assertThat(stderr.toString()).contains("aborted");
        verify(service, never()).delete(any(), any());
    }

    @Test
    public void test_delete_missing_returns_3() throws Exception {
        when(service.stats(eq("ghost"), eq(true))).thenThrow(new ProjectNotFoundException("ghost"));
        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "ghost");
        props.setProperty(PROJECT_DELETE_YES_OPT, "true");

        int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("ghost"));

        assertThat(exit).isEqualTo(3);
        verify(service, never()).delete(any(), any());
    }

    @Test
    public void test_delete_if_exists_missing_returns_0_noop() throws Exception {
        when(service.stats(eq("ghost"), eq(true))).thenThrow(new ProjectNotFoundException("ghost"));
        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "ghost");
        props.setProperty(PROJECT_DELETE_IF_EXISTS_OPT, "true");
        props.setProperty(PROJECT_DELETE_YES_OPT, "true");

        int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("ghost"));

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString()).contains("does not exist (no-op)");
    }

    @Test
    public void test_delete_keep_index_passes_option() throws Exception {
        when(service.stats(eq("foo"), eq(false))).thenReturn(ProjectStats.withSkippedIndex("foo", 3));
        org.mockito.ArgumentCaptor<ProjectDeleteOptions> captor =
                org.mockito.ArgumentCaptor.forClass(ProjectDeleteOptions.class);
        when(service.delete(eq("foo"), captor.capture()))
                .thenReturn(new ProjectDeleted("foo", true, false, true, true, false, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "foo");
        props.setProperty(PROJECT_DELETE_KEEP_INDEX_OPT, "true");
        props.setProperty(PROJECT_DELETE_YES_OPT, "true");

        int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("foo"));

        assertThat(exit).isEqualTo(0);
        assertThat(captor.getValue().keepIndex()).isTrue();
    }

    @Test
    public void test_delete_json_output() throws Exception {
        when(service.stats(eq("foo"), eq(true))).thenReturn(ProjectStats.of("foo", 42L, 3));
        when(service.delete(eq("foo"), any(ProjectDeleteOptions.class)))
                .thenReturn(new ProjectDeleted("foo", true, true, true, true, true, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "foo");
        props.setProperty(PROJECT_DELETE_YES_OPT, "true");
        props.setProperty(PROJECT_DELETE_JSON_OPT, "true");

        int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("foo"));

        assertThat(exit).isEqualTo(0);
        String out = stdout.toString();
        assertThat(out).contains("\"deleted\":true");
        assertThat(out).contains("\"noop\":false");
        assertThat(out).contains("\"name\":\"foo\"");
        assertThat(out).contains("\"dbDeleted\":true");
        assertThat(out).contains("\"indexDeleted\":true");
    }

    @Test
    public void test_create_auto_grants_default_user_when_no_creator_flag() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, null, null, true, false));
        when(service.grant("foo", "promera", org.icij.datashare.policies.Role.PROJECT_ADMIN))
                .thenReturn(new org.icij.datashare.project.admin.ProjectGranted(
                        "foo", "promera", org.icij.datashare.policies.Role.PROJECT_ADMIN, null, false));

        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty("defaultUserName", "promera");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        verify(service).grant("foo", "promera", org.icij.datashare.policies.Role.PROJECT_ADMIN);
        assertThat(stdout.toString()).contains("granted PROJECT_ADMIN on 'foo' to 'promera'");
    }

    @Test
    public void test_create_skips_auto_grant_when_default_user_blank() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, null, null, true, false));

        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        // No defaultUserName set: nothing to grant to.

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        verify(service, never()).grant(any(), any(), any());
    }

    @Test
    public void test_create_explicit_creator_overrides_default_user() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, null, null, true, false));
        when(service.grant("foo", "alice", org.icij.datashare.policies.Role.PROJECT_ADMIN))
                .thenReturn(new org.icij.datashare.project.admin.ProjectGranted(
                        "foo", "alice", org.icij.datashare.policies.Role.PROJECT_ADMIN, null, false));

        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty("defaultUserName", "promera");
        props.setProperty("projectCreate.creator", "alice");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        verify(service).grant("foo", "alice", org.icij.datashare.policies.Role.PROJECT_ADMIN);
        verify(service, never()).grant("foo", "promera", org.icij.datashare.policies.Role.PROJECT_ADMIN);
    }

    @Test
    public void test_create_warns_when_explicit_creator_missing_from_inventory() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, null, null, true, false));
        when(service.grant("foo", "ghost", org.icij.datashare.policies.Role.PROJECT_ADMIN))
                .thenThrow(new org.icij.datashare.project.admin.UserNotFoundException("ghost"));

        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty("projectCreate.creator", "ghost");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        assertThat(stderr.toString()).contains("warning");
        assertThat(stderr.toString()).contains("ghost");
    }

    @Test
    public void test_create_silent_when_fallback_default_user_missing_from_inventory() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, null, null, true, false));
        when(service.grant("foo", "local-datashare", org.icij.datashare.policies.Role.PROJECT_ADMIN))
                .thenThrow(new org.icij.datashare.project.admin.UserNotFoundException("local-datashare"));

        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        // No --creator: launcher-injected --defaultUserName is the only signal.
        props.setProperty("defaultUserName", "local-datashare");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        // The grant attempt happened (service was called) but the warning
        // is suppressed for the fallback path.
        verify(service).grant("foo", "local-datashare", org.icij.datashare.policies.Role.PROJECT_ADMIN);
        assertThat(stderr.toString()).excludes("warning");
    }

    @Test
    public void test_create_propagates_explicit_dates_to_request() throws Exception {
        Date stampedCreation = Date.from(Instant.parse("2026-05-15T10:00:00Z"));
        Date stampedUpdate = Date.from(Instant.parse("2026-05-16T10:00:00Z"));
        org.mockito.ArgumentCaptor<ProjectCreateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ProjectCreateRequest.class);
        when(service.create(captor.capture()))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, stampedCreation, stampedUpdate, true, false));

        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_CREATION_DATE_OPT, "2026-05-15T10:00:00Z");
        props.setProperty(PROJECT_CREATE_UPDATE_DATE_OPT, "2026-05-16T10:00:00Z");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        ProjectCreateRequest request = captor.getValue();
        assertThat(request.creationDate()).isEqualTo(stampedCreation);
        assertThat(request.updateDate()).isEqualTo(stampedUpdate);
    }

    @Test
    public void test_create_skips_auto_grant_when_noop() throws Exception {
        when(service.createIfNotExists(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, null, null, false, true));

        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_IF_NOT_EXISTS_OPT, "true");
        props.setProperty("defaultUserName", "promera");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        verify(service, never()).grant(any(), any(), any());
    }

    @Test
    public void test_delete_aborts_when_prompter_exhausts_retries() throws Exception {
        when(service.stats(eq("foo"), eq(true))).thenReturn(ProjectStats.of("foo", 42L, 3));

        Supplier<Prompter> exhaustingPrompter = () -> {
            Prompter mock = mock(Prompter.class);
            when(mock.promptString(any(), any()))
                    .thenThrow(new Prompter.ValidationFailedException("name", "typed name does not match"));
            return mock;
        };

        Properties props = new Properties();
        props.setProperty(PROJECT_DELETE_OPT, "foo");
        // No --yes / --no-input: confirmation flow runs.

        int exit = CliApp.handleProjectDelete(service, props, exhaustingPrompter);

        assertThat(exit).isEqualTo(0);
        assertThat(stderr.toString()).contains("aborted");
        verify(service, never()).delete(any(), any());
    }
}
