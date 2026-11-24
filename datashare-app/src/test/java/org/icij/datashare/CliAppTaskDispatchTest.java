package org.icij.datashare;

import com.google.inject.ProvisionException;
import org.icij.datashare.Repository;
import org.icij.datashare.session.ApiKeyExpiration;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.User;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DelApiKeyTask;
import org.icij.datashare.tasks.GenApiKeyTask;
import org.icij.datashare.tasks.GetApiKeyTask;
import org.icij.datashare.tasks.GrantAdminPolicyTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one-shot task flags CliApp dispatches before the pipeline: api key create/get, and grant admin. Exercised through the package-visible handlers rather than
 * runTaskWorker, which calls System.exit; same shape as CliAppUserDispatchTest.
 */
public class CliAppTaskDispatchTest {
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream origOut;
    private PrintStream origErr;
    private DatashareTaskFactory taskFactory;

    @Before
    public void setUp() {
        // logback prints its own bootstrap status to stdout the first time it initializes, which
        // would land in the capture below and make these assertions depend on test ordering
        org.slf4j.LoggerFactory.getLogger(CliAppTaskDispatchTest.class).debug("initialize logging");
        origOut = System.out;
        origErr = System.err;
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
        taskFactory = mock(DatashareTaskFactory.class);
    }

    @After
    public void restore() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    @Test
    public void test_create_api_key_with_expiration_date() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        Date expiration = Date.from(Instant.parse("2027-02-01T00:00:00Z"));
        when(taskFactory.createGenApiKey(any(), eq(expiration))).thenReturn(task);

        assertThat(CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", "2027-02-01", List.of())).isEqualTo(CliApp.EXIT_SUCCESS);

        verify(taskFactory).createGenApiKey(any(), eq(expiration));
    }

    @Test
    public void test_create_api_key_with_invalid_date_exits_validation() throws Exception {
        assertThat(CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", "2027-13-01", List.of())).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test
    public void test_create_api_key_with_date_beyond_year_9999_exits_validation() throws Exception {
        assertThat(CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", "+300000-01-01", List.of())).isEqualTo(CliApp.EXIT_VALIDATION);
        assertThat(CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", "+300000000-01-01", List.of())).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test
    public void test_create_api_key_rejected_by_policy_exits_validation() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenThrow(
                new ApiKeyExpiration.InvalidExpirationException("expiration date must be in the future"));
        when(taskFactory.createGenApiKey(any(), any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", "2020-01-01", List.of())).isEqualTo(CliApp.EXIT_VALIDATION);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_create_api_key_internal_illegal_argument_is_not_a_validation_error() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenThrow(new IllegalArgumentException("internal detail"));
        when(taskFactory.createGenApiKey(any(), any())).thenReturn(task);

        CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", null, List.of());
    }

    @Test
    public void test_create_api_key_with_invalid_max_lifetime_exits_validation() throws Exception {
        ApiKeyExpiration.InvalidExpirationException cause =
                new ApiKeyExpiration.InvalidExpirationException("invalid apiKeyMaxLifetime: 90d");
        when(taskFactory.createGenApiKey(any(), any())).thenThrow(new ProvisionException("guice", cause));

        assertThat(CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", null, List.of())).isEqualTo(CliApp.EXIT_VALIDATION);
        assertThat(err.toString()).contains("error: invalid apiKeyMaxLifetime: 90d");
    }

    @Test
    public void test_create_api_key_prints_the_key_on_stdout() throws Exception {
        // logs moved to stderr, so the key has to be written to stdout explicitly:
        // `datashare --createApiKey alice | read KEY` must keep working
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        when(taskFactory.createGenApiKey(any(), any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", null, List.of())).isEqualTo(CliApp.EXIT_SUCCESS);

        assertThat(out.toString().trim()).isEqualTo("the-generated-key");
    }

    @Test
    public void test_create_api_key_keeps_the_key_off_the_log_line() throws Exception {
        // the log goes to stderr and into ./logs/datashare.log, so that is where to look: a
        // stdout-only assertion would pass even if the secret were logged
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        when(taskFactory.createGenApiKey(any(), any())).thenReturn(task);

        CliApp.handleApiKeyCreate(taskFactory, mock(Repository.class), "alice", null, List.of());

        assertThat(out.toString().trim()).isEqualTo("the-generated-key");
        assertThat(err.toString()).excludes("the-generated-key");
    }

    @Test
    public void test_get_api_key_prints_the_hashed_key_on_stdout() throws Exception {
        GetApiKeyTask task = mock(GetApiKeyTask.class);
        when(task.call()).thenReturn("hashed-key");
        when(taskFactory.createGetApiKey(any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyGet(taskFactory, "alice")).isEqualTo(CliApp.EXIT_SUCCESS);

        assertThat(out.toString().trim()).isEqualTo("hashed-key");
    }

    @Test
    public void test_get_api_key_for_an_unknown_user_exits_not_found_and_prints_nothing() throws Exception {
        // exit 0 with empty stdout is indistinguishable from "the key is empty" to a caller script
        GetApiKeyTask task = mock(GetApiKeyTask.class);
        when(task.call()).thenReturn(null);
        when(taskFactory.createGetApiKey(any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyGet(taskFactory, "ghost")).isEqualTo(CliApp.EXIT_NOT_FOUND);

        assertThat(out.toString().trim()).isEmpty();
    }

    @Test
    public void test_grant_admin_exits_conflict_when_an_admin_already_exists() throws Exception {
        // `datashare --grantAdmin alice && setup-rest.sh` must not continue on a refused grant
        GrantAdminPolicyTask task = mock(GrantAdminPolicyTask.class);
        when(task.call()).thenReturn(false);
        when(taskFactory.createGrantAdminPolicyTask(any())).thenReturn(task);

        assertThat(CliApp.handleGrantAdmin(taskFactory, "alice")).isEqualTo(CliApp.EXIT_CONFLICT);
    }

    @Test
    public void test_grant_admin_exits_zero_on_success() throws Exception {
        GrantAdminPolicyTask task = mock(GrantAdminPolicyTask.class);
        when(task.call()).thenReturn(true);
        when(taskFactory.createGrantAdminPolicyTask(any())).thenReturn(task);

        assertThat(CliApp.handleGrantAdmin(taskFactory, "alice")).isEqualTo(CliApp.EXIT_SUCCESS);
    }

    @Test
    public void test_grant_admin_exits_conflict_on_a_null_result() throws Exception {
        // the task is declared Boolean, so null is reachable; treating it as success would be
        // the exact silent-pass this task exists to remove
        GrantAdminPolicyTask task = mock(GrantAdminPolicyTask.class);
        when(task.call()).thenReturn(null);
        when(taskFactory.createGrantAdminPolicyTask(any())).thenReturn(task);

        assertThat(CliApp.handleGrantAdmin(taskFactory, "alice")).isEqualTo(CliApp.EXIT_CONFLICT);
    }

    @Test
    public void test_delete_api_key_exits_not_found_when_the_user_had_none() throws Exception {
        // same outcome as --apiKey on a user with no key: both report not found, with the same code
        DelApiKeyTask task = mock(DelApiKeyTask.class);
        when(task.call()).thenReturn(false);
        when(taskFactory.createDelApiKey(any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyDelete(taskFactory, mock(ApiKeyRepository.class), "ghost", null)).isEqualTo(CliApp.EXIT_NOT_FOUND);
    }

    @Test
    public void test_delete_api_key_exits_zero_when_a_key_was_deleted() throws Exception {
        DelApiKeyTask task = mock(DelApiKeyTask.class);
        when(task.call()).thenReturn(true);
        when(taskFactory.createDelApiKey(any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyDelete(taskFactory, mock(ApiKeyRepository.class), "alice", null)).isEqualTo(CliApp.EXIT_SUCCESS);
    }

    @Test
    public void test_delete_api_key_exits_not_found_on_a_null_result() throws Exception {
        DelApiKeyTask task = mock(DelApiKeyTask.class);
        when(task.call()).thenReturn(null);
        when(taskFactory.createDelApiKey(any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyDelete(taskFactory, mock(ApiKeyRepository.class), "ghost", null)).isEqualTo(CliApp.EXIT_NOT_FOUND);
    }

    @Test
    public void test_grant_admin_exits_runtime_when_the_write_fails() throws Exception {
        // "an instance admin already exists" and "the write did not take" are different outcomes:
        // a script treating 4 as "already provisioned, carry on" must not swallow a real failure
        GrantAdminPolicyTask task = mock(GrantAdminPolicyTask.class);
        when(task.call()).thenThrow(new IllegalStateException("could not persist the role"));
        when(taskFactory.createGrantAdminPolicyTask(any())).thenReturn(task);

        assertThat(CliApp.handleGrantAdmin(taskFactory, "alice")).isEqualTo(CliApp.EXIT_RUNTIME);
    }

    @Test
    public void test_create_without_projects_rotates() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        when(taskFactory.createGenApiKey(any(), any())).thenReturn(task);
        Repository repository = mock(Repository.class);

        int exitCode = CliApp.handleApiKeyCreate(taskFactory, repository, "alice", null, List.of());

        assertThat(exitCode).isEqualTo(CliApp.EXIT_SUCCESS);
        verify(taskFactory).createGenApiKey(any(), eq(null));
        verify(taskFactory, never()).createGenApiKey(any(), any(), any());
    }

    @Test
    public void test_create_with_projects_adds() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        when(taskFactory.createGenApiKey(any(), any(), any())).thenReturn(task);
        Repository repository = mock(Repository.class);
        when(repository.getProjects(List.of("a", "b"))).thenReturn(List.of(new Project("a"), new Project("b")));

        int exitCode = CliApp.handleApiKeyCreate(taskFactory, repository, "alice", null, List.of("a", "b"));

        assertThat(exitCode).isEqualTo(CliApp.EXIT_SUCCESS);
        verify(taskFactory).createGenApiKey(any(), eq(null), eq(List.of("a", "b")));
    }

    @Test
    public void test_create_with_a_project_of_the_user_without_a_project_row_adds() throws Exception {
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        when(taskFactory.createGenApiKey(any(), any(), any())).thenReturn(task);
        Repository repository = mock(Repository.class);
        Map<String, Object> groups = Map.of("datashare", List.of("leak-a"));
        User alice = new User("alice", "alice", "", "", Map.of("groups_by_applications", groups));
        when(repository.getUser("alice")).thenReturn(alice);
        when(repository.getProjects(List.of("leak-a"))).thenReturn(List.of());

        int exitCode = CliApp.handleApiKeyCreate(taskFactory, repository, "alice", null, List.of("leak-a"));

        assertThat(exitCode).isEqualTo(CliApp.EXIT_SUCCESS);
        verify(taskFactory).createGenApiKey(any(), eq(null), eq(List.of("leak-a")));
    }

    @Test
    public void test_create_with_unknown_project_fails() throws Exception {
        Repository repository = mock(Repository.class);
        when(repository.getProjects(List.of("a", "nope"))).thenReturn(List.of(new Project("a")));

        int exitCode = CliApp.handleApiKeyCreate(taskFactory, repository, "alice", null, List.of("a", "nope"));

        assertThat(exitCode).isEqualTo(CliApp.EXIT_VALIDATION);
        assertThat(err.toString()).contains("nope");
        verify(taskFactory, never()).createGenApiKey(any(), any(), any());
        verify(taskFactory, never()).createGenApiKey(any(), any());
    }

    @Test
    public void test_list_prints_one_line_per_key() {
        ApiKey unscoped = mock(ApiKey.class);
        when(unscoped.getId()).thenReturn("h1");
        when(unscoped.getProjects()).thenReturn(List.of());
        ApiKey scoped = mock(ApiKey.class);
        when(scoped.getId()).thenReturn("h2");
        when(scoped.getProjects()).thenReturn(List.of("a", "b"));
        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        when(apiKeyRepository.getAll(any())).thenReturn(List.of(unscoped, scoped));
        ApiKeyExpiration expiration = mock(ApiKeyExpiration.class);
        when(expiration.effectiveExpiration(unscoped)).thenReturn(null);
        when(expiration.effectiveExpiration(scoped)).thenReturn(Date.from(Instant.parse("2027-02-01T00:00:00Z")));

        int exitCode = CliApp.handleApiKeyList(apiKeyRepository, expiration, "alice");

        assertThat(exitCode).isEqualTo(CliApp.EXIT_SUCCESS);
        assertThat(out.toString()).isEqualTo("h1\t*\t-\nh2\ta,b\t2027-02-01T00:00:00Z\n");
    }

    @Test
    public void test_delete_one_unknown_key_is_not_found() throws Exception {
        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        when(apiKeyRepository.delete(any(), eq("nope"))).thenReturn(false);

        int exitCode = CliApp.handleApiKeyDelete(taskFactory, apiKeyRepository, "alice", "nope");

        assertThat(exitCode).isEqualTo(CliApp.EXIT_NOT_FOUND);
    }
}
