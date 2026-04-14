package org.icij.datashare.tasks;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class TaskFinderTest {
    @Mock
    DatashareTaskFactory taskFactory;
    @Mock
    BatchSearchRepository batchSearchRepository;

    TaskFinder taskFinder;
    TaskManagerMemory taskManager;
    private final CountDownLatch waitForLoop = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of(
            "taskManagerPollingIntervalMilliseconds", "1000"
        ));
        taskManager = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), propertiesProvider, waitForLoop);
        waitForLoop.await();
        taskFinder = new TaskFinder(taskManager, batchSearchRepository);
    }

    @Test
    public void test_get_one_proxy_task() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        when(batchSearchRepository.getRecords(any(User.class), anyList())).thenReturn(List.of(batchSearchRecord));

        List<Task<?>> tasks = taskFinder.findVisibleTasksFor(user, TaskFilters.empty()).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).id).isEqualTo(batchSearchRecord.uuid);
        assertThat(tasks.get(0).getUser()).isEqualTo(user);
    }

    @Test
    public void test_get_tasks_and_proxy_task() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        Task<String> task = new Task<>("name", User.local(), new HashMap<>());
        taskManager.startTask(task, null);
        when(batchSearchRepository.getRecords(any(User.class), anyList())).thenReturn(List.of(batchSearchRecord));

        List<Task<?>> tasks = taskFinder.findVisibleTasksFor(user, TaskFilters.empty()).toList();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(1).id).isEqualTo(batchSearchRecord.uuid);
        assertThat(tasks.get(1).getUser()).isEqualTo(user);
    }

    @Test
    public void test_get_tasks_and_proxy_task_from_other_user() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User userLocal = User.localUser("fritz");
        User userOtherLocal = User.localUser("jdoe");
        List<String> projects = List.of("project");
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(UUID.randomUUID().toString(), projects, "name", "description", 123, 0, new Date(), BatchSearchRecord.State.SUCCESS, uri, userLocal, 1, true, null, null );
        Task<String> task = new Task<>("name", User.local(), new HashMap<>());
        taskManager.startTask(task, null);
        //Simulate that fritz and jdoe belong to the same project, and that fritz's batch search appears in jdoe's list
        when(batchSearchRepository.getRecords(eq(userOtherLocal), anyList())).thenReturn(List.of(batchSearchRecord));

        //Ensure jdoe sees the fritz's batch search, but not it's task
        List<Task<?>> tasks = taskFinder.findVisibleTasksFor(userOtherLocal, TaskFilters.empty()).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).id).isEqualTo(batchSearchRecord.uuid);
        assertThat(tasks.get(0).getUser()).isEqualTo(userLocal);
    }

    @Test
    public void test_get_unique_proxy_task() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        // Same batch search record appears twice in the repository result (deduplication must occur)
        when(batchSearchRepository.getRecords(any(User.class), anyList())).thenReturn(asList(batchSearchRecord, batchSearchRecord));

        List<Task<?>> tasks = taskFinder.findVisibleTasksFor(user, TaskFilters.empty()).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo("org.icij.datashare.tasks.BatchSearchRunnerProxy");
    }

    @Test
    public void test_skip_proxy_task_when_filter_by_name_is_given() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        when(batchSearchRepository.getRecords(any(User.class), anyList())).thenReturn(asList(batchSearchRecord, batchSearchRecord));

        List<Task<?>> tasks = taskFinder.findVisibleTasksFor(user,
            TaskFilters.empty().withNames("org.icij.datashare.tasks.IndexTask")).toList();
        assertThat(tasks).hasSize(0);
    }

    @Test
    public void test_not_skip_proxy_task_when_filter_by_name_is_given_with_correct_value() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        when(batchSearchRepository.getRecords(any(User.class), anyList())).thenReturn(asList(batchSearchRecord, batchSearchRecord));

        List<Task<?>> tasks = taskFinder.findVisibleTasksFor(user,
            TaskFilters.empty().withNames("org.icij.datashare.tasks.BatchSearchRunnerProxy")).toList();
        assertThat(tasks).hasSize(1);
    }

    @Test
    public void test_get_unique_proxy_task_not_in_priority() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        // A real task with the same id as the batch search is already in the task manager
        Task<String> task = new Task<>(batchSearchRecord.uuid, "name", User.local());
        taskManager.startTask(task, null);
        when(batchSearchRepository.getRecords(any(User.class), anyList())).thenReturn(List.of(batchSearchRecord));

        List<Task<?>> tasks = taskFinder.findVisibleTasksFor(user, TaskFilters.empty()).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo("name");
    }

    @After
    public void tearDown() throws Exception {
        taskManager.close();
    }
}
