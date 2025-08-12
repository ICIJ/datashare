package org.icij.datashare.asynctasks;

import static com.netflix.conductor.common.metadata.tasks.Task.Status.COMPLETED;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.asynctasks.Task.State.CANCELLED;
import static org.icij.datashare.asynctasks.Task.State.DONE;
import static org.icij.datashare.asynctasks.Task.State.ERROR;
import static org.icij.datashare.asynctasks.Task.State.FINAL_STATES;
import static org.icij.datashare.asynctasks.Task.State.RUNNING;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.client.exception.ConductorClientException;
import com.netflix.conductor.client.http.ConductorClient;
import com.netflix.conductor.client.http.MetadataClient;
import com.netflix.conductor.client.http.WorkflowClient;
import com.netflix.conductor.common.config.ObjectMapperProvider;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.Workflow;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.icij.datashare.function.Pair;
import org.icij.datashare.tasks.RoutingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskManagerConductor implements TaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskManagerConductor.class);

    public static final String TASK_GROUP = "taskGroup";
    protected final ConductorClient client;
    private final WorkflowClient workflowClient;
    public final Map<String, Group> taskGroups = new ConcurrentHashMap<>();
    private final RoutingStrategy routingStrategy;


    public TaskManagerConductor(ConductorClient client, RoutingStrategy routingStrategy, List<Path> tasksDefPath,
                                List<Path> workflowsDefPath) throws IOException {
        this.client = client;
        this.routingStrategy = routingStrategy;
        this.workflowClient = new WorkflowClient(this.client);
        this.declareTaskAndWorkflow(tasksDefPath, workflowsDefPath);
    }

    @Override
    public boolean stopTask(String taskId) throws IOException, UnknownTask {
        if (FINAL_STATES.contains(this.getTask(taskId).getState())) {
            return false;
        }
        this.workflowClient.terminateWorkflow(taskId, "termination requested by user");
        return true;
    }

    @Override
    public <V extends Serializable> Task<V> clearTask(String taskId) throws IOException, UnknownTask {
        Task<V> task = this.getTask(taskId);
        if (task.getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        workflowClient.deleteWorkflow(taskId, false);
        return task;
    }

    @Override
    public boolean shutdown() throws IOException {
        // We didn't initialize the underlying client which might be shared this is not our
        // responsibility to shut it down
        return true;
    }

    @Override
    public <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists {
        // We don't do anything as Conductor will task care of save the task for us. We just save the group to start
        // the task using it
        taskGroups.put(task.id, group);
    }

    @Override
    public <V extends Serializable> void update(Task<V> task) throws UnknownTask {
        // We don't do anything as the updates are handled by Conductor
        try {
            workflowClient.getWorkflow(task.id, false);
        } catch (ConductorClientException e) {
            throw new UnknownTask(task.id);
        }
    }

    @Override
    public <V extends Serializable> void enqueue(Task<V> task) throws IOException {
        // We could also add the user with "withCreatedBy"
        StartWorkflowRequest request = new StartWorkflowRequest()
            .withName(task.name)
            .withInput(task.args)
            // TODO: if we want to route some tasks of the workflow to some specific workers, Java vs. Python groups,
            //  this must happen here, providing the task the same domain as the expected workers
            .withTaskToDomain(Map.of())
            .withCorrelationId(task.id);
        Group group = Optional.ofNullable(taskGroups.get(task.id)).orElseThrow(() -> new UnknownTask(task.id));
        switch (routingStrategy) {
            case NAME -> request.setTaskToDomain(Map.of(task.name, task.name));
            case GROUP -> request.setTaskToDomain(Map.of(task.name, group.id().name()));
        }
        workflowClient.startWorkflow(request);
    }

    @Override
    public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
        Workflow wf;
        wf = getWorkflow(taskId);
        return taskFromWorkflow(wf);
    }


    @Override
    public Stream<Task<?>> getTasks(TaskFilters filters) throws IOException {
        Stream<Task<?>> tasks = workflowClient.searchV2(null, null, null, freeTextQueryFromFilters(filters), null)
            .getResults()
            .stream()
            .map(TaskManagerConductor::taskFromWorkflow);
        // TODO: this could be done more efficiently through freeTextSearch directly !
        if (filters.getArgs() != null && !filters.getArgs().isEmpty()) {
            tasks = tasks.filter(TaskFilters.empty().withArgs(filters.getArgs())::filter);
        }
        return tasks;
    }


    @Override
    public Group getTaskGroup(String taskId) throws IOException {
        Workflow wf = getWorkflow(taskId);
        TaskGroupType id = TaskGroupType.valueOf(wf.getTaskToDomain().get(TASK_GROUP));
        return new Group(id);
    }

    @Override
    public List<Task<?>> clearDoneTasks(TaskFilters filters) throws IOException {
        // Require tasks to be in final state and apply user filters
        return (List<Task<?>>) workflowClient.searchV2("status = 'IN_PROGRESS' OR status = 'SCHEDULED'")
            .getResults()
            .stream()
            .map(wf -> {
                workflowClient.deleteWorkflow(wf.getWorkflowId(), false);
                return taskFromWorkflow(wf);
            });
    }


    @Override
    public int getTerminationPollingInterval() {
        return 100;
    }


    @Override
    public void clear() throws IOException {
        workflowClient.searchV2(null).getResults().forEach(
            wf -> workflowClient.deleteWorkflow(wf.getWorkflowId(), false)
        );
    }

    @Override
    public boolean getHealth() {
        // TODO: check the client's health
        return true;
    }

    private static <V extends Serializable> Task<V> taskFromWorkflow(Workflow wf) {
        Task.State state = conductorToDatashareState(wf.getStatus());
        Date createdAt = Date.from(Instant.ofEpochMilli(wf.getCreateTime()));
        Date completedAt = null;
        if (state.equals(DONE)) {
            completedAt = Date.from(Instant.ofEpochMilli(wf.getEndTime()));
        }
        TaskResult<V> result = null;
        if (state.equals(DONE)) {
            V rawResult = (V) Optional.ofNullable(wf.getOutput().get("result"))
                .orElseThrow(() -> new RuntimeException("inconsistent state"));
            result = new TaskResult<>(rawResult);
        }
        // TODO: we aggregate progress each time we access the task, it would be much better to cache this computation
        double progress = aggregateProgress(wf);
        Task<V> task = new Task<>(wf.getWorkflowId(), wf.getWorkflowName(), state, progress, createdAt, 0, completedAt,
            wf.getInput(), result, null);
        return task;
    }

    public static String freeTextQueryFromFilters(TaskFilters filters) {
        ArrayList<String> query = new ArrayList<>();
        // Name
        Optional.ofNullable(filters.getNamePattern()).ifPresent(p -> query.add("workflowType:/" + p.pattern() + "/"));
        // State
        String joinedStates = Optional.ofNullable(filters.getStates()).map(
            states -> states.stream().map(s -> "\"" + datashareToConductorState(s) + "\"")
                .collect(Collectors.joining(" OR "))
        ).orElse("");
        // FIXME: no search by user, this is an issue...
        if (!joinedStates.isEmpty()) {
            query.add("status:(" + joinedStates + ")");
        }
        if (query.isEmpty()) {
            return "*";
        }
        return String.join(" AND ", query.stream().map(i -> "(" + i + ")").toList());
    }

    private static Task.State conductorToDatashareState(Workflow.WorkflowStatus conductorState) {
        Task.State dsState;
        switch (conductorState) {
            case RUNNING -> dsState = RUNNING;
            case TIMED_OUT -> dsState = ERROR;
            case TERMINATED -> dsState = CANCELLED;
            case FAILED -> dsState = ERROR;
            case COMPLETED -> dsState = DONE;
            case PAUSED -> dsState = RUNNING; // We don't have pause
            default -> throw new IllegalStateException("Unexpected value: " + conductorState);
        }
        return dsState;
    }

    private static String datashareToConductorState(Task.State dsState) {
        String conductorState;
        switch (dsState) {
            case CREATED -> conductorState = "SCHEDULED";
            case QUEUED -> conductorState = "SCHEDULED";
            case RUNNING -> conductorState = "IN_PROGRESS";
            case CANCELLED -> conductorState = "CANCELLED";
            case ERROR -> conductorState = "FAILED";
            case DONE -> conductorState = "COMPLETED";
            default -> throw new IllegalStateException("Unexpected value: " + dsState);
        }
        return conductorState;
    }

    private Workflow getWorkflow(String taskId) {
        Workflow wf;
        try {
            wf = workflowClient.getWorkflow(taskId, false);
        } catch (ConductorClientException e) { // TODO: we should probably be more specific here
            throw new UnknownTask(taskId);
        }
        return wf;
    }

    private static double aggregateProgress(Workflow wf) {
        int maxProgress = 0;
        int progress = 0;
        Pair<Integer, Integer> progresses =
            wf.getTasks().stream().map(t -> Optional.ofNullable(t.getOutputData().get("progress"))
                .map(p -> {
                    HashMap<String, Integer> progressMap = (HashMap<String, Integer>) p;
                    return new Pair<>(progressMap.get("max_progress"), progressMap.get("progress"));
                })
                .orElseGet(() -> {
                    if (t.getStatus() == COMPLETED) {
                        return new Pair<>(1, 1);
                    }
                    return new Pair<>(1, 0);
                })
            ).reduce(new Pair<>(maxProgress, progress), (acc, p) -> new Pair<>(acc._1() + p._1(), acc._2() + p._2()));
        if (progresses._1() == 0) {
            return 0;
        }
        return (double) progresses._2() / (double) progresses._1();
    }

    private void declareTaskAndWorkflow(List<Path> tasksDefPaths, List<Path> workflowsDefPaths) throws IOException {
        MetadataClient metadataClient = new MetadataClient(client);
        ObjectMapper mapper = (new ObjectMapperProvider()).getObjectMapper();
        tasksDefPaths.forEach(rethrowConsumer(p -> {
            try (InputStream resource = this.getClass().getClassLoader().getResourceAsStream(p.toString())) {
                TaskDef taskDef = mapper.readValue(resource, TaskDef.class);
                try {
                    metadataClient.registerTaskDefs(List.of(taskDef));
                } catch (ConductorClientException e) {
                    if (e.getStatus() == 500 && e.getMessage().contains("already exists")) {
                        LOGGER.info("skipping task def as task already exist");
                    } else {
                        throw e;
                    }
                }
            }
        }));
        workflowsDefPaths.forEach(rethrowConsumer(p -> {
            try (InputStream resource = this.getClass().getClassLoader().getResourceAsStream(p.toString())) {
                WorkflowDef wfDef = mapper.readValue(resource, WorkflowDef.class);
                try {
                    metadataClient.registerWorkflowDef(wfDef);
                } catch (ConductorClientException e) {
                    if (e.getStatus() == 500 && e.getMessage().contains("already exists")) {
                        LOGGER.info("skipping workflow def as workflow already exist");
                    } else {
                        throw e;
                    }
                }
            }
        }));
    }


    @Override
    public void close() throws IOException {
    }
}
