package org.icij.datashare;

import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.GenApiKeyTask;
import org.icij.datashare.tasks.GetApiKeyTask;
import org.icij.datashare.tasks.GrantAdminPolicyTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one-shot task flags CliApp dispatches before the pipeline: api key create/get, and grant admin. Exercised through the package-visible handlers rather than
 * runTaskWorker, which calls System.exit; same shape as CliAppUserDispatchTest.
 */
public class CliAppTaskDispatchTest {
    private ByteArrayOutputStream out;
    private PrintStream origOut;
    private DatashareTaskFactory taskFactory;

    @Before
    public void setUp() {
        // logback prints its own bootstrap status to stdout the first time it initializes, which
        // would land in the capture below and make these assertions depend on test ordering
        org.slf4j.LoggerFactory.getLogger(CliAppTaskDispatchTest.class).debug("initialize logging");
        origOut = System.out;
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        taskFactory = mock(DatashareTaskFactory.class);
    }

    @After
    public void restore() {
        System.setOut(origOut);
    }

    @Test
    public void test_create_api_key_prints_the_key_on_stdout() throws Exception {
        // logs moved to stderr, so the key has to be written to stdout explicitly:
        // `datashare --createApiKey alice | read KEY` must keep working
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        when(taskFactory.createGenApiKey(any())).thenReturn(task);

        assertThat(CliApp.handleApiKeyCreate(taskFactory, "alice")).isEqualTo(CliApp.EXIT_SUCCESS);

        assertThat(out.toString().trim()).isEqualTo("the-generated-key");
    }

    @Test
    public void test_create_api_key_keeps_the_key_off_the_log_line() throws Exception {
        // the log line goes to stderr and into ./logs/datashare.log; the secret must not
        GenApiKeyTask task = mock(GenApiKeyTask.class);
        when(task.call()).thenReturn("the-generated-key");
        when(taskFactory.createGenApiKey(any())).thenReturn(task);

        CliApp.handleApiKeyCreate(taskFactory, "alice");

        assertThat(out.toString().trim().lines().count()).isEqualTo(1);
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
}
