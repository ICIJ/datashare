package org.icij.datashare.asynctasks;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.asynctasks.Task.State.FINAL_STATES;
import static org.icij.datashare.asynctasks.Task.USER_KEY;
import static org.icij.datashare.asynctasks.bus.amqp.Event.MAX_RETRIES_LEFT;
import static org.icij.datashare.asynctasks.temporal.TemporalHelper.asTaskState;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.enums.v1.NamespaceState;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.namespace.v1.NamespaceConfig;
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest;
import io.temporal.api.operatorservice.v1.DeleteNamespaceRequest;
import io.temporal.api.operatorservice.v1.ListSearchAttributesRequest;
import io.temporal.api.operatorservice.v1.OperatorServiceGrpc;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DeleteWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.serviceclient.OperatorServiceStubs;
import io.temporal.serviceclient.OperatorServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.temporal.TemporalInputPayload;
import org.icij.datashare.asynctasks.temporal.TemporalQueryBuilder;
import org.icij.datashare.function.Pair;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskManagerTemporal implements TaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskManagerTemporal.class);

    public static final String DEFAULT_NAMESPACE = "datashare-default";

    // See https://docs.temporal.io/list-filter#supported-operators
    public static final SearchAttributeKey<String> WORKFLOW_TYPE_ATTRIBUTE =
        SearchAttributeKey.forKeyword("WorkflowType");
    public static final SearchAttributeKey<String> EXECUTION_STATUS_ATTRIBUTE =
        SearchAttributeKey.forKeyword("ExecutionStatus");
    public static final SearchAttributeKey<String> USER_CUSTOM_ATTRIBUTE = SearchAttributeKey.forKeyword("UserId");
    public static final SearchAttributeKey<Double> MAX_PROGRESS_CUSTOM_ATTRIBUTE =
        SearchAttributeKey.forDouble("MaxProgress");
    public static final SearchAttributeKey<Double> PROGRESS_CUSTOM_ATTRIBUTE = SearchAttributeKey.forDouble("Progress");

    private final WorkflowClient client;
    private final String namespace;
    private final RoutingStrategy routingStrategy;


    private static final NamespaceConfig DEFAULT_NAMESPACE_CONFIG =
        NamespaceConfig.newBuilder().setWorkflowExecutionRetentionTtl(Durations.fromDays(365)).build();
    private static final Duration DEFAULT_WORKFLOW_TASK_TIMEOUT = Duration.ofDays(7);
    private static final Duration DEFAULT_NAMESPACE_POLL_INTERVAL = Duration.of(50, ChronoUnit.MILLIS);
    private static final int DEFAULT_PAGE_SIZE = 100;

    // TODO: add support for continue-as-new https://docs.temporal.io/develop/java/continue-as-new

    private static final String TERMINATION_MSG = "terminated_by_user";
    public static final String WORKFLOWS_DEFAULT = "default-java";

    private static final DefaultDataConverter defaultDataConverter = DefaultDataConverter.newDefaultInstance();


    private final WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceBlockingStub;

    public static final Map<String, IndexedValueType> CUSTOM_SEARCH_ATTRIBUTES = Map.of(
        MAX_PROGRESS_CUSTOM_ATTRIBUTE.getName(), IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE,
        PROGRESS_CUSTOM_ATTRIBUTE.getName(), IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE,
        USER_CUSTOM_ATTRIBUTE.getName(), IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD
    );

    private static final Set<Status.Code> NAMESPACE_EXISTS = Set.of(Status.ALREADY_EXISTS.getCode());


    public TaskManagerTemporal(WorkflowClient client, RoutingStrategy routingStrategy) {
        this(client, client.getWorkflowServiceStubs().blockingStub(), routingStrategy);
    }

    protected TaskManagerTemporal(WorkflowClient client,  WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceStubs, RoutingStrategy routingStrategy) {
        this.client = client;
        this.namespace = client.getOptions().getNamespace();
        this.workflowServiceBlockingStub = workflowServiceStubs;
        this.routingStrategy = routingStrategy;
    }

    @Override
    public <V extends Serializable> String startTask(Task<V> taskView, Group group) throws TaskAlreadyExists {
        String taskId = taskView.id;
        WorkflowOptions.Builder optionBuilder = WorkflowOptions.newBuilder().setWorkflowId(taskId)
            .setWorkflowTaskTimeout(DEFAULT_WORKFLOW_TASK_TIMEOUT) // TODO: set this per task
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
            .setTypedSearchAttributes(generateSearchAttributes(taskView))
            .setTaskQueue(resolveWfTaskQueue(taskView.name, group));
        try {
            client.newUntypedWorkflowStub(taskView.name, optionBuilder.build())
                .start(new TemporalInputPayload(taskView.args));
        } catch (WorkflowExecutionAlreadyStarted ex) {
            throw new TaskAlreadyExists(taskId, ex);
        }
        // Super important force description to refresh the cache and make the task visible
        client.newUntypedWorkflowStub(taskId).describe();
        return taskId;
    }

    @Override
    public <V extends Serializable> Task<V> getTask(String taskId) throws IOException, UnknownTask {
        return parseTask(getWorkflowExecution(taskId));
    }

    @Override
    public Stream<Task<?>> getTasks(TaskFilters filters) throws IOException {
        Stream<Task<?>> tasks = eventuallyConsistentListExecutions(filters).map(rethrowFunction(this::parseTask));
        // Temporal doesn't allow to search by args we have to post filter task retrieved with other filters
        if (filters.hasArgs()) {
            TaskFilters byArgs = TaskFilters.empty().withArgs(filters.getArgs());
            tasks = tasks.filter(byArgs::filter);
        }
        return tasks.sorted(Comparator.comparing(t -> t.createdAt));
    }

    @Override
    public Stream<String> getTaskIds(TaskFilters filters) throws IOException {
        Stream<WorkflowExecutionInfo> exec = eventuallyConsistentListExecutions(filters);
        // Temporal doesn't allow to search by args we have to post filter task retrieved with other filters
        if (filters.hasArgs()) {
            TaskFilters byArgs = TaskFilters.empty().withArgs(filters.getArgs());
            exec = exec.map(e -> new Pair<>(e, getArgs(e)))
                .filter(p -> byArgs.filter(p._2()))
                .map(Pair::_1);
        }
        return exec.map(e -> e.getExecution().getWorkflowId());
    }

    @Override
    public <V extends Serializable> Task<V> clearTask(String taskId) throws UnknownTask, IOException {
        Task<V> task = getTask(taskId);
        unknownIfNotFound(rethrowConsumer(t -> {
            DeleteWorkflowExecutionRequest.Builder requestBuilder = DeleteWorkflowExecutionRequest.newBuilder();
            requestBuilder
                .setNamespace(namespace)
                .setWorkflowExecution(requestBuilder.getWorkflowExecutionBuilder().setWorkflowId(t).build());
            workflowServiceBlockingStub.deleteWorkflowExecution(requestBuilder.build());
        }), taskId);
        return task;
    }

    @Override
    public boolean stopTask(String taskId) throws IOException, UnknownTask {
        // TODO: add support for cancellation rather than termination, update the TaskManager API accordingly
        return unknownIfNotFound(tId -> {
            WorkflowStub workflowStub = client.newUntypedWorkflowStub(tId);
            boolean isRunning = workflowStub.describe().getStatus() == WORKFLOW_EXECUTION_STATUS_RUNNING;
            try {
                workflowStub.terminate(TERMINATION_MSG);
            } catch (WorkflowNotFoundException ex) {
                if (!ex.getCause().getMessage().contains("workflow execution already completed")) {
                    throw ex;
                }
            }
            return isRunning;
        }, taskId);
    }

    @Override
    public List<Task<?>> clearDoneTasks(TaskFilters filters) throws IOException {
        Set<Task.State> states = new HashSet<>(FINAL_STATES);
        if (filters.hasStates()) {
            states.retainAll(filters.getStates());
        }
        List<Task<?>> tasks = getTasks(filters.withStates(states)).toList();
        tasks.stream().map(Task::getId).forEach(rethrowConsumer(this::deleteExecution));
        return tasks;
    }

    @Override
    public boolean shutdown() throws IOException {
        // TODO: should we shutdown the client, is that even possible ?
        return true;
    }

    @Override
    public void clear() throws IOException {
        Set<String> taskIds = getTaskIds().collect(Collectors.toSet());
        taskIds.forEach(rethrowConsumer(this::deleteExecution));
    }

    @Override
    public boolean getHealth() throws IOException {
        try {
            return client.getWorkflowServiceStubs().healthCheck().getStatus().equals(SERVING);
        } catch (StatusRuntimeException ignored) {
            return false;
        }
    }

    @Override
    public int getTerminationPollingInterval() {
        // We need a high interval to let the server propagate deletions
        return 1000;
    }


    @Override
    public void close() throws IOException {
    }

    public static WorkflowClient buildClient(String target, String namespace) {
        WorkflowClientOptions clientOptions = WorkflowClientOptions.newBuilder().setNamespace(namespace).build();
        WorkflowServiceStubsOptions serviceStubsOptions = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(target)
            .build();
        WorkflowServiceStubs serviceStub = WorkflowServiceStubs.newServiceStubs(serviceStubsOptions);
        return buildClient(serviceStub, clientOptions);
    }

    public static String resolveWfTaskQueue(RoutingStrategy routingStrategy, String queueKey, Group group) {
        switch (routingStrategy) {
            case UNIQUE -> {
                return WORKFLOWS_DEFAULT;
            }
            case GROUP -> {
                return group.getId().toLowerCase();
            }
            case NAME -> {
                return queueKey.toLowerCase();
            }
            default -> throw new IllegalArgumentException("invalid routing strategy " + routingStrategy);
        }
    }

    private static WorkflowClient buildClient(WorkflowServiceStubs serviceStub, WorkflowClientOptions clientOptions) {
        return WorkflowClient.newInstance(serviceStub, clientOptions);
    }


    protected static void setupNamespace(WorkflowClient client, Duration timeout) throws InterruptedException {
        long start = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        String namespace = client.getOptions().getNamespace();
        WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceBlockingStub =
            client.getWorkflowServiceStubs().blockingStub();
        OperatorServiceGrpc.OperatorServiceBlockingStub operatorServiceBlockingStub =
            OperatorServiceStubs.newServiceStubs(
                OperatorServiceStubsOptions.newBuilder()
                    .setChannel(client.getWorkflowServiceStubs().getRawChannel())
                    .validateAndBuildWithDefaults()).blockingStub();
        synchronized (TaskManagerTemporal.class) {
            boolean createNamespace = !hasNamespace(workflowServiceBlockingStub, client.getOptions().getNamespace());
            if (createNamespace) {
                try {
                    RegisterNamespaceRequest registerNamespaceRequest = RegisterNamespaceRequest.newBuilder()
                        .setWorkflowExecutionRetentionPeriod(
                            DEFAULT_NAMESPACE_CONFIG.getWorkflowExecutionRetentionTtl())
                        .setNamespace(namespace).build();
                    workflowServiceBlockingStub.registerNamespace(registerNamespaceRequest);
                } catch (StatusRuntimeException ex) {
                    if (!NAMESPACE_EXISTS.contains(ex.getStatus().getCode())) {
                        throw ex;
                    }
                }
            }
            if (createNamespace) {
                while (true) {
                    if ((System.currentTimeMillis() - start >= timeoutMillis)) {
                        throw new RuntimeException(
                            "failed to setup namespace search attribute in less than " + timeout);
                    }
                    try {
                        operatorServiceBlockingStub.addSearchAttributes(
                            AddSearchAttributesRequest.newBuilder().setNamespace(namespace)
                                .putAllSearchAttributes(CUSTOM_SEARCH_ATTRIBUTES).build()
                        );
                        break;
                    } catch (StatusRuntimeException ex) {
                        if (ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                            continue;
                        }
                        if (ex.getStatus().getCode().equals(Status.Code.FAILED_PRECONDITION)
                            && ex.getMessage().contains("Namespace has invalid state")) {
                            continue;
                        }
                        Thread.sleep(DEFAULT_NAMESPACE_POLL_INTERVAL.toMillis());
                    }
                }
            }
            while (!namespaceIsReady(operatorServiceBlockingStub, namespace)) {
                if ((System.currentTimeMillis() - start >= timeoutMillis)) {
                    throw new RuntimeException("failed to read namespace search attribute in less than " + timeout);
                }
            }
        }
    }

    protected static void deleteNamespace(WorkflowClient client, Duration timeout) {
        String namespace = client.getOptions().getNamespace();
        OperatorServiceStubs.newServiceStubs(
                OperatorServiceStubsOptions.newBuilder()
                    .setChannel(client.getWorkflowServiceStubs().getRawChannel())
                    .validateAndBuildWithDefaults())
            .blockingStub()
            .deleteNamespace(DeleteNamespaceRequest.newBuilder().setNamespace(namespace).build());
        awaitNamespaceDeleted(client.getWorkflowServiceStubs().blockingStub(), namespace, timeout);
    }

    private static boolean hasNamespace(WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceBlockingStub,
                                        String namespace) {
        NamespaceState namespaceState;
        try {
            namespaceState = workflowServiceBlockingStub.describeNamespace(
                DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build()
            ).getNamespaceInfo().getState();
        } catch (StatusRuntimeException ex) {
            if (!ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                throw ex;
            }
            return false;
        }
        return namespaceState.equals(NamespaceState.NAMESPACE_STATE_REGISTERED);
    }

    private static boolean namespaceIsReady(OperatorServiceGrpc.OperatorServiceBlockingStub operatorServiceBlockingStub,
                                            String namespace) {
        Set<String> searchAttributes = operatorServiceBlockingStub
            .listSearchAttributes(ListSearchAttributesRequest.newBuilder().setNamespace(namespace).build())
            .getCustomAttributesMap()
            .keySet();
        return searchAttributes.containsAll(CUSTOM_SEARCH_ATTRIBUTES.keySet());
    }


    private Stream<WorkflowExecutionInfo> eventuallyConsistentListExecutions(TaskFilters filters) {
        PageFetcher<WorkflowExecutionInfo> fetcher =
            getWorkflowExecutionFetcher(TemporalQueryBuilder.buildFromFilters(filters));
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(new TemporalPageIterator<>(fetcher), Spliterator.ORDERED), false);
    }

    private <V extends Serializable> Task<V> parseTask(WorkflowExecutionMetadata response) {
        return parseTask(response.getWorkflowExecutionInfo());
    }

    private <V extends Serializable> Task<V> parseTask(WorkflowExecutionInfo workflowExecutionInfo) {
        WorkflowExecution execution = workflowExecutionInfo.getExecution();
        String taskId = execution.getWorkflowId();
        String taskName = workflowExecutionInfo.getType().getName();
        double progress = parseProgress(workflowExecutionInfo);
        Date createdAt = new Date(workflowExecutionInfo.getStartTime().getSeconds() * 1000);
        Date completedAt = null;
        Timestamp closeTime = workflowExecutionInfo.getCloseTime();
        if (!closeTime.equals(Timestamp.getDefaultInstance())) {
            completedAt = new Date(closeTime.getSeconds() * 1000);
        }
        TaskResult<V> result = null;
        TaskError error = null;
        Task.State state = asTaskState(workflowExecutionInfo.getStatus());
        if (Objects.requireNonNull(state) == Task.State.ERROR || state == Task.State.DONE) {
            try {
                Serializable res = client.newUntypedWorkflowStub(workflowExecutionInfo.getExecution().getWorkflowId())
                    .getResult(Serializable.class);
                if (res != null) {
                    result = new TaskResult<>((V) res);
                }
            } catch (WorkflowFailedException ex) {
                error = new TaskError(ex.getCause());
            }
        }
        Map<String, Object> args = getArgs(workflowExecutionInfo);
        int retriesLeft = MAX_RETRIES_LEFT; // retriesLeft makes no sense in the context of a workflow,
        // it only makes sense for subtasks/activities
        return new Task<>(taskId, taskName, state, progress, createdAt, retriesLeft, completedAt, args, result, error);
    }

    private Map<String, Object> getArgs(WorkflowExecutionInfo workflowExecutionInfo) {
        Map<String, Object> args = null;
        WorkflowExecution execution = workflowExecutionInfo.getExecution();
        Payloads payloads = client.fetchHistory(execution.getWorkflowId(), execution.getRunId()).getEvents().get(0)
            .getWorkflowExecutionStartedEventAttributes().getInput();
        if (payloads.getPayloadsCount() > 1) {
            throw new RuntimeException("invalid payload count, expected exactly 1 payload");
        }
        TemporalInputPayload payload =
            defaultDataConverter.fromPayload(payloads.getPayloads(0), TemporalInputPayload.class,
                TemporalInputPayload.class);
        if (payload != null) {
            args = payload.args();
            args.computeIfPresent(USER_KEY, (k, v) -> new User((Map<String, Object>) v));
        }
        return args;
    }

    private static double parseProgress(WorkflowExecutionInfo workflowExecutionInfo) {
        double maxProgress = defaultDataConverter.fromPayload(workflowExecutionInfo.getSearchAttributes()
            .getIndexedFieldsOrThrow(MAX_PROGRESS_CUSTOM_ATTRIBUTE.getName()), Double.class, Double.class);
        if (maxProgress == 0) {
            return 0.0;
        }
        double currentProgress = defaultDataConverter.fromPayload(
            workflowExecutionInfo.getSearchAttributes().getIndexedFieldsOrThrow(PROGRESS_CUSTOM_ATTRIBUTE.getName()),
            Double.class, Double.class);
        return currentProgress / maxProgress;
    }

    protected String resolveWfTaskQueue(String taskName, Group group) {
        return resolveWfTaskQueue(routingStrategy, taskName, group);
    }

    private WorkflowExecutionDescription getWorkflowExecution(String taskId) throws UnknownTask {
        return unknownIfNotFound(t -> {
            return client.newUntypedWorkflowStub(taskId).describe();
        }, taskId);
    }

    @FunctionalInterface
    interface PageFetcher<P> {
        Page<P> fetchPage(ByteString nextPageToken);
    }

    record Page<P>(List<P> items, ByteString nextPageToken) {
    }

    private static class TemporalPageIterator<P> implements Iterator<P> {
        // TODO: check if Temporal returns a null of empty token at the ned
        private ByteString nextPageToken = null;
        private final PageFetcher<P> fetcher;
        private Iterator<P> itemsIterator;

        TemporalPageIterator(PageFetcher<P> fetcher) {
            this.fetcher = fetcher;
        }

        @Override
        public boolean hasNext() {
            if (itemsIterator != null && itemsIterator.hasNext()) {
                return true;
            }
            if (itemsIterator != null && nextPageToken.equals(ByteString.EMPTY)) {
                return false;
            }
            Page<P> newPage = fetcher.fetchPage(nextPageToken);
            nextPageToken = newPage.nextPageToken;
            itemsIterator = newPage.items.iterator();
            return itemsIterator.hasNext();
        }

        @Override
        public P next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return itemsIterator.next();
        }
    }

    private PageFetcher<WorkflowExecutionInfo> getWorkflowExecutionFetcher(String query) {
        return (ByteString nextPageToken) -> {
            ListWorkflowExecutionsRequest.Builder requestBuilder =
                ListWorkflowExecutionsRequest.newBuilder().setNamespace(namespace).setPageSize(DEFAULT_PAGE_SIZE);
            if (nextPageToken != null) {
                requestBuilder.setNextPageToken(nextPageToken);
            }
            Optional.ofNullable(query).ifPresent(q -> {
                if (!q.isEmpty()) {
                    requestBuilder.setQuery(q);
                }
            });
            ListWorkflowExecutionsResponse response = workflowServiceBlockingStub
                .listWorkflowExecutions(requestBuilder.build());
            return new Page<>(response.getExecutionsList(), response.getNextPageToken());
        };
    }

    private static void unknownIfNotFound(Consumer<String> supplier, String taskId) {
        unknownIfNotFound((t) -> {
            supplier.accept(t);
            return null;
        }, taskId);
    }

    private static <T> T unknownIfNotFound(Function<String, T> function, String taskId) {
        try {
            return function.apply(taskId);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().equals(Status.NOT_FOUND)) {
                throw new UnknownTask(taskId);
            }
            throw e;
        } catch (WorkflowNotFoundException e) {
            throw new UnknownTask(taskId);
        }
    }

    static SearchAttributes generateSearchAttributes(Task<?> taskView) {
        SearchAttributes.Builder builder = SearchAttributes.newBuilder()
            .set(USER_CUSTOM_ATTRIBUTE, taskView.getUser().getId())
            .set(PROGRESS_CUSTOM_ATTRIBUTE, 0d).set(MAX_PROGRESS_CUSTOM_ATTRIBUTE, 0d);
        return builder.build();
    }

    protected void awaitCleared(Set<String> taskIds, int timeout, TimeUnit timeUnit) throws IOException {
        long startTime = System.currentTimeMillis();
        long maxDuration = timeUnit.toMillis(timeout);
        while ((System.currentTimeMillis() - startTime < maxDuration)) {
            if (getTaskIds().noneMatch(taskIds::contains)) {
                return;
            }
            try {
                Thread.sleep(getTerminationPollingInterval());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("failed to clear task in " + timeout + " " + timeUnit);
    }

    protected void awaitCleared(int timeout, TimeUnit timeUnit) throws IOException {
        long startTime = System.currentTimeMillis();
        long maxDuration = timeUnit.toMillis(timeout);
        while ((System.currentTimeMillis() - startTime < maxDuration)) {
            try (Stream<String> taskIds = getTaskIds()) {
                if (taskIds.count() == 0) {
                    return;
                }
            }
            try {
                Thread.sleep(getTerminationPollingInterval());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("failed to clear task in " + timeout + " " + timeUnit);
    }

    private static void awaitNamespaceDeleted(
        WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceBlockingStub, String namespace, Duration timeout)
        throws RuntimeException {
        long startTime = System.currentTimeMillis();
        long maxDuration = timeout.toMillis();
        while ((System.currentTimeMillis() - startTime < maxDuration)) {
            try {
                workflowServiceBlockingStub.describeNamespace(
                    DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
            } catch (StatusRuntimeException e) {
                if (!e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                    throw e;
                }
                return;
            }
        }
        throw new RuntimeException("failed to delete namespace in " + timeout);
    }

    protected void awaitExecutionDeletion(Set<String> taskIds) throws InterruptedException {
        // TODO: avoid the infinite loop
        // TODO: implement throttling
        while (true) {
            boolean allDeleted = taskIds.stream().allMatch(tId -> {
                try {
                    client.newUntypedWorkflowStub(tId).describe();
                } catch (StatusRuntimeException ex) {
                    if (ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                        return true;
                    }
                } catch (WorkflowNotFoundException ignored) {
                    return true;
                }
                return false;
            });
            if (allDeleted) {
                break;
            }
            // TODO: define a proper duration
            Thread.sleep(DEFAULT_NAMESPACE_POLL_INTERVAL.toMillis());
        }
    }

    private void deleteExecution(String workflowId) {
        try {
            DeleteWorkflowExecutionRequest deleteWorkflowExecutionRequest = DeleteWorkflowExecutionRequest.newBuilder()
                .setNamespace(namespace)
                .setWorkflowExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                .build();
            workflowServiceBlockingStub.deleteWorkflowExecution(deleteWorkflowExecutionRequest);
            // Try to refresh the cache
            client.newUntypedWorkflowStub(workflowId).describe();
        } catch (WorkflowNotFoundException ignored) {
        }
    }

}