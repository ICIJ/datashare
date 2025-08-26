package org.icij.datashare.asynctasks;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.text.Project.project;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.util.stream.Stream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.event.Level;

public class TaskManagerMemoryTest {
    @Rule
    public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

    TaskFactory factory = new TestFactory();

    private TaskManagerMemory taskManager;
    private TaskInspector taskInspector;
    private final CountDownLatch waitForLoop = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        taskManager = new TaskManagerMemory(factory, new TaskRepositoryMemory(), new PropertiesProvider(), waitForLoop);
        taskInspector = new TaskInspector(taskManager);
        waitForLoop.await();
    }

    @Test
    public void test_run_task() throws Exception {
        Task task = new Task(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "world"));

        String tid = taskManager.startTask(task, new Group(TaskGroupType.Test));
        taskManager.awaitTermination(100, TimeUnit.MILLISECONDS);

        Task storedTask = taskManager.getTask(tid);
        assertThat(storedTask.getState()).isEqualTo(Task.State.DONE);
        byte[] expectedResult = MAPPER.writeValueAsBytes("Hello world!");
        assertThat(storedTask.getResult()).isEqualTo(expectedResult);
        assertThat(taskManager.getTasks().toList()).hasSize(1);
    }

    @Test
    public void test_stop_current_task() throws Exception {
        Task task = new Task(TestFactory.SleepForever.class.getName(), User.local(), Map.of("intParameter", 2000));
        String taskId = taskManager.startTask(task, new Group(TaskGroupType.Test));

        taskInspector.awaitToBeStarted(taskId, 10000);
        taskManager.stopTask(taskId);
        taskManager.awaitTermination(1, TimeUnit.SECONDS);

        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.numberOfExecutedTasks()).isEqualTo(0);
    }

    @Test
    public void test_stop_queued_task() throws Exception {
        Task t1 = new Task(TestFactory.SleepForever.class.getName(), User.local(), Map.of());
        Task t2 = new Task(TestFactory.HelloWorld.class.getName(), User.local(), Map.of("greeted", "stucked task"));

        Group group = new Group(TaskGroupType.Test);
        taskManager.startTask(t1, group);
        taskManager.startTask(t2, group);

        taskInspector.awaitToBeStarted(t1.id, 1000);
        taskManager.stopTask(t2.id); // the second is still in the queue
        taskManager.stopTask(t1.id);

        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(t2.getState()).isEqualTo(Task.State.CANCELLED);
        assertThat(taskManager.numberOfExecutedTasks()).isEqualTo(0);
        assertThat(taskManager.getTasks().toList()).hasSize(2);
    }

    @Test
    public void test_clear_the_only_task() throws Exception {
        Task task = new Task("sleep", User.local(), Map.of("intParameter", 12));

        taskManager.startTask(task, new Group(TaskGroupType.Test));
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(taskManager.getTasks().toList()).hasSize(1);

        taskManager.clearTask(task.id);

        assertThat(taskManager.getTasks().toList()).hasSize(0);
    }

    @Test(expected = IllegalStateException.class)
    public void test_clear_running_task_should_throw_exception() throws Exception {
        Task task = new Task("sleep", User.local(), Map.of("intParameter", 12));

        taskManager.startTask(task, new Group(TaskGroupType.Test));
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        taskManager.progress(task.id, 0.5);
        assertThat(taskManager.getTask(task.id).getState()).isEqualTo(Task.State.RUNNING);

        taskManager.clearTask(task.id);
    }

    @Test
    public void test_progress_on_unknown_task() throws Exception {
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        taskManager.progress("unknownId", 0.5);
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains(
            "unknown task id <unknownId> for progress=0.5 call");
    }

    @Test
    public void test_result_on_unknown_task() throws Exception {
        taskManager.awaitTermination(1, TimeUnit.SECONDS);
        taskManager.result("unknownId", MAPPER.writeValueAsBytes(0.5));
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains(
            "unknown task id <unknownId> for result=0.5 call");
    }

    @Test
    public void test_persist_task() throws TaskAlreadyExists, IOException, UnknownTask {
        Task task = new Task("name", User.local(), new HashMap<>());

        taskManager.insert(task, null);

        assertThat(taskManager.getTasks().toList()).hasSize(1);
        assertThat(taskManager.getTask(task.id)).isNotNull();
    }

    @Test
    public void test_get_one_proxy_task() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);

        List<Task> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user), Stream.of(batchSearchRecord)).toList();
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
        Task task = new Task("name", User.local(), new HashMap<>());
        taskManager.insert(task, null);

        List<Task> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user), Stream.of(batchSearchRecord)).toList();
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
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        Task task = new Task("name", User.local(), Map.of());
        taskManager.insert(task, null);

        TaskFilters filters = TaskFilters.empty().withUser(userOtherLocal);
        List<Task> tasks = taskManager.getTasks(filters, Stream.of(batchSearchRecord)).toList();
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
        List<BatchSearchRecord> batchSearchRecords = asList(batchSearchRecord, batchSearchRecord);

        List<Task> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user), batchSearchRecords.stream()).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo("org.icij.datashare.tasks.BatchSearchRunnerProxy");
    }

    @Test
    public void test_skip_proxy_task_when_filter_by_name_is_given() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        List<BatchSearchRecord> batchSearchRecords = asList(batchSearchRecord, batchSearchRecord);

        List<Task> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user).withNames("org.icij.datashare.tasks.IndexTask"), batchSearchRecords.stream()).toList();
        assertThat(tasks).hasSize(0);
    }

    @Test
    public void test_not_skip_proxy_task_when_filter_by_name_is_given_with_correct_value() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);
        List<BatchSearchRecord> batchSearchRecords = asList(batchSearchRecord, batchSearchRecord);

        List<Task> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user).withNames("org.icij.datashare.tasks.BatchSearchRunnerProxy"), batchSearchRecords.stream()).toList();
        assertThat(tasks).hasSize(1);
    }

    @Test
    public void test_get_unique_proxy_task_not_in_priority() throws IOException {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all";
        User user = User.local();
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "name", "description", 123, new Date(), uri);

        Task task = new Task(batchSearchRecord.uuid, "name", User.local());
        taskManager.insert(task, null);

        List<Task> tasks = taskManager.getTasks(TaskFilters.empty().withUser(user), Stream.of(batchSearchRecord)).toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo("name");
    }

    @Test
    public void test_update_task() throws TaskAlreadyExists, IOException, UnknownTask {
        // Given
        Task task = new Task("HelloWorld", User.local(), Map.of("greeted", "world"));
        Task update = new Task(task.id, task.name, task.getState(), 0.5, DatashareTime.getNow(), 3, null, task.args, null, null);
        // When
        taskManager.insert(task, null);
        taskManager.update(update);
        Task updated = taskManager.getTask(task.id);
        // Then
        assertThat(updated).isEqualTo(update);
    }

    @Test
    public void test_wait_task_to_be_done() throws Exception {
        taskManager.startTask(TestFactory.Sleep.class, User.local(), Map.of("duration", 100));
        List<Task> tasks = taskManager.waitTasksToBeDone(200, TimeUnit.MILLISECONDS);
        assertThat(tasks).hasSize(1);
    }

    @Test
    public void test_health_ok() {
        assertThat(taskManager.getHealth()).isTrue();
    }

    @Test
    public void test_health_ko() throws IOException {
        taskManager.shutdown();

        assertThat(taskManager.getHealth()).isFalse();
    }

    @After
    public void tearDown() throws Exception {
        taskManager.close();
    }

}
