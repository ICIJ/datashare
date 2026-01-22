package org.icij.datashare.tasks;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

public class DatashareTaskManagerTest {
    @Rule
    public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

    @Mock
    DatashareTaskFactory taskFactory;

    DatashareTaskManager taskManager;
    private final CountDownLatch waitForLoop = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of(
            "taskManagerPollingIntervalMilliseconds", "1000"
        ));
        taskManager = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), propertiesProvider, waitForLoop);
        waitForLoop.await();
    }

    @Test
    public void test_get_one_proxy_task() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);

        List<Task<?>> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user), Stream.of(batchSearchRecord)).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).id).isEqualTo(batchSearchRecord.uuid);
        assertThat(tasks.get(0).getUser()).isEqualTo(user);
    }

    @Test
    public void test_get_tasks_and_proxy_task() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord =
            new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        Task<String> task = new Task<>("name", User.local(), new HashMap<>());
        taskManager.startTask(task, null);

        List<Task<?>> tasks =
            taskManager.getTasks(TaskFilters.empty().withUser(user), Stream.of(batchSearchRecord)).toList();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(1).id).isEqualTo(batchSearchRecord.uuid);
        assertThat(tasks.get(1).getUser()).isEqualTo(user);
    }

    @Test
    public void test_get_tasks_and_proxy_task_from_other_user() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User userLocal = User.local();
        User userOtherLocal = User.localUser("jdoe");
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord =
            new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        Task<String> task = new Task<>("name", User.local(), new HashMap<>());
        taskManager.startTask(task, null);

        TaskFilters filters = TaskFilters.empty().withUser(userOtherLocal);
        List<Task<?>> tasks = taskManager.getTasks(filters, Stream.of(batchSearchRecord)).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).id).isEqualTo(batchSearchRecord.uuid);
        assertThat(tasks.get(0).getUser()).isEqualTo(userLocal);
    }

    @Test
    public void test_get_unique_proxy_task() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord =
            new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        List<BatchSearchRecord> batchSearchRecords = asList(batchSearchRecord, batchSearchRecord);

        List<Task<?>> tasks =
            taskManager.getTasks(TaskFilters.empty().withUser(user), batchSearchRecords.stream()).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo("org.icij.datashare.tasks.BatchSearchRunnerProxy");
    }

    @Test
    public void test_skip_proxy_task_when_filter_by_name_is_given() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord =
            new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        List<BatchSearchRecord> batchSearchRecords = asList(batchSearchRecord, batchSearchRecord);

        List<Task<?>> tasks =
            taskManager.getTasks(TaskFilters.empty().withUser(user).withNames("org.icij.datashare.tasks.IndexTask"),
                batchSearchRecords.stream()).toList();
        assertThat(tasks).hasSize(0);
    }

    @Test
    public void test_not_skip_proxy_task_when_filter_by_name_is_given_with_correct_value() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        List<BatchSearchRecord> batchSearchRecords = asList(batchSearchRecord, batchSearchRecord);

        List<Task<?>> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user).withNames("org.icij.datashare.tasks.BatchSearchRunnerProxy"), batchSearchRecords.stream()).toList();
        assertThat(tasks).hasSize(1);
    }

    @Test
    public void test_get_unique_proxy_task_not_in_priority() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord =
            new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);

        Task<String> task = new Task<>(batchSearchRecord.uuid, "name", User.local());
        taskManager.startTask(task, null);

        List<Task<?>> tasks =
            taskManager.getTasks(TaskFilters.empty().withUser(user), Stream.of(batchSearchRecord)).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo("name");
    }


    @After
    public void tearDown() throws Exception {
        taskManager.close();
    }
}
