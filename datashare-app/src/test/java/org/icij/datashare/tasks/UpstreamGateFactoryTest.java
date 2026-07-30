package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.UpstreamGate.UPSTREAM_TASK_ID;

public class UpstreamGateFactoryTest {
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final UpstreamGate.Factory factory = new UpstreamGate.Factory(taskRepository);

    @Test
    public void test_no_upstream_id_returns_the_none_gate() {
        Task<Long> taskView = new Task<>(ExtractNlpTask.class.getName(), User.local(), Map.of());

        assertThat(factory.forTask(taskView)).isSameAs(UpstreamGate.NONE);
        assertThat(UpstreamGate.NONE.mayGrow()).isFalse();
    }

    @Test
    public void test_running_upstream_may_grow() throws Exception {
        Task<Long> upstream = new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), Map.of());
        upstream.setState(Task.State.RUNNING);
        taskRepository.insert(upstream, null);

        assertThat(gateOn(upstream.id).mayGrow()).isTrue();
    }

    @Test
    public void test_terminal_upstream_does_not_grow() throws Exception {
        Task<Long> upstream = new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), Map.of());
        upstream.setState(Task.State.RUNNING);
        taskRepository.insert(upstream, null);
        upstream.setResult(new TaskResult<>(0L));

        assertThat(gateOn(upstream.id).mayGrow()).isFalse();
    }

    @Test
    public void test_unknown_upstream_does_not_grow() {
        assertThat(gateOn("unknown-task-id").mayGrow()).isFalse();
    }

    @Test
    public void test_checked_repository_failure_may_grow() {
        UpstreamGate.Factory throwingFactory = new UpstreamGate.Factory(new TaskRepositoryMemory() {
            @Override
            public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
                throw new IOException("transient repository failure");
            }
        });

        UpstreamGate gate = throwingFactory.forTask(new Task<>(ExtractNlpTask.class.getName(), User.local(),
                Map.of(UPSTREAM_TASK_ID, "some-task-id")));

        assertThat(gate.mayGrow()).isTrue();
    }

    @Test
    public void test_unchecked_repository_failure_may_grow() {
        UpstreamGate.Factory throwingFactory = new UpstreamGate.Factory(new TaskRepositoryMemory() {
            @Override
            public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
                throw new IllegalStateException("unchecked repository failure");
            }
        });

        UpstreamGate gate = throwingFactory.forTask(new Task<>(ExtractNlpTask.class.getName(), User.local(),
                Map.of(UPSTREAM_TASK_ID, "some-task-id")));

        assertThat(gate.mayGrow()).isTrue();
    }

    private UpstreamGate gateOn(String upstreamTaskId) {
        return factory.forTask(new Task<>(ExtractNlpTask.class.getName(), User.local(),
                Map.of(UPSTREAM_TASK_ID, upstreamTaskId)));
    }
}
