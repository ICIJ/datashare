package org.icij.datashare.asynctasks.temporal;

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
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.*;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.serviceclient.OperatorServiceStubs;
import io.temporal.serviceclient.OperatorServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.WorkflowInterface;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.*;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.function.Pair;
import org.icij.datashare.function.ThrowingSupplier;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.reflections.Reflections;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.*;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.PropertiesProvider.TEMPORAL_NAMESPACE_OPT;
import static org.icij.datashare.PropertiesProvider.TEMPORAL_ADDRESS_OPT;
import static org.icij.datashare.asynctasks.Task.USER_KEY;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.resolveWfTaskQueue;
import static org.icij.datashare.asynctasks.bus.amqp.Event.MAX_RETRIES_LEFT;
import static org.icij.datashare.asynctasks.temporal.TemporalHelper.*;

public class TemporalInterlocutor {
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final Duration DEFAULT_WORKFLOW_TASK_TIMEOUT = Duration.ofDays(7);
    private static final String TERMINATION_MSG = "terminated_by_user";
    static WorkflowImplementationOptions WF_IMPLEMENTATION_DEFAULT_OPTIONS = WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(Error.class) // Unregistered workflows
            .build();
    // TODO: in-memory hack for strong consistence, maybe a repository would be a better implem
    private final ConcurrentHashMap.KeySetView<String, Boolean> executions = ConcurrentHashMap.newKeySet();

    public static final String DEFAULT_NAMESPACE = "datashare-default";
    public static final DefaultDataConverter defaultDataConverter = DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(JsonObjectMapper.getMapper()));
    private final WorkflowClient client;

    // See https://docs.temporal.io/list-filter#supported-operators
    public static final SearchAttributeKey<String> WORKFLOW_TYPE_ATTRIBUTE =
        SearchAttributeKey.forKeyword("WorkflowType");
    public static final SearchAttributeKey<String> EXECUTION_STATUS_ATTRIBUTE =
        SearchAttributeKey.forKeyword("ExecutionStatus");
    public static final SearchAttributeKey<String> USER_CUSTOM_ATTRIBUTE = SearchAttributeKey.forKeyword("UserId");
    public static final SearchAttributeKey<Double> MAX_PROGRESS_CUSTOM_ATTRIBUTE =
        SearchAttributeKey.forDouble("MaxProgress");
    public static final SearchAttributeKey<Double> PROGRESS_CUSTOM_ATTRIBUTE = SearchAttributeKey.forDouble("Progress");

    public static final Map<String, IndexedValueType> CUSTOM_SEARCH_ATTRIBUTES = Map.of(
        MAX_PROGRESS_CUSTOM_ATTRIBUTE.getName(), IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE,
        PROGRESS_CUSTOM_ATTRIBUTE.getName(), IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE,
        USER_CUSTOM_ATTRIBUTE.getName(), IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD
    );
    public static final Duration DEFAULT_NAMESPACE_POLL_INTERVAL = Duration.of(50, ChronoUnit.MILLIS);

    private static final Set<Status.Code> NAMESPACE_EXISTS = Set.of(Status.ALREADY_EXISTS.getCode());
    private static final NamespaceConfig DEFAULT_NAMESPACE_CONFIG =
        NamespaceConfig.newBuilder().setWorkflowExecutionRetentionTtl(Durations.fromDays(365)).build();
    private final WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceStubs;


    public TemporalInterlocutor(String target, String namespace) throws InterruptedException {
        this.client = buildClient(target, namespace);
        this.workflowServiceStubs = client.getWorkflowServiceStubs().blockingStub();
        setupNamespace(Duration.ofSeconds(30));
    }

    public TemporalInterlocutor(PropertiesProvider propertiesProvider) throws InterruptedException {
        this(propertiesProvider.get(TEMPORAL_ADDRESS_OPT).orElse(EnvUtils.resolveUri("temporalAddress", "temporal:7233")),
            propertiesProvider.get(TEMPORAL_NAMESPACE_OPT).orElse(DEFAULT_NAMESPACE));
    }

    // for tests
    TemporalInterlocutor(WorkflowClient client) {
        this.client = client;
        this.workflowServiceStubs = client.getWorkflowServiceStubs().blockingStub();
    }

    // for TaskManagerTemporal tests
    public TemporalInterlocutor(WorkflowClient client, WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceBlockingStub) {
        this.client = client;
        this.workflowServiceStubs = workflowServiceBlockingStub;
    }

    public <A extends TemporalActivityImpl<?, ?>> ThrowingSupplier<A> activityFactory(
        Class<A> activityCls,
        TaskFactory taskFactory,
        TaskRepository taskRepository,
        double progressWeight
    ) {
        return () -> activityCls
            .getConstructor(TaskFactory.class, WorkflowClient.class, TaskRepository.class, Double.class)
            .newInstance(taskFactory, client, taskRepository, progressWeight);
    }

    public void setupNamespace(Duration timeout) throws InterruptedException {
        long start = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        String namespace = getNamespace();
        WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceBlockingStub =
            client.getWorkflowServiceStubs().blockingStub();
        OperatorServiceGrpc.OperatorServiceBlockingStub operatorServiceBlockingStub =
            OperatorServiceStubs.newServiceStubs(
                OperatorServiceStubsOptions.newBuilder()
                    .setChannel(client.getWorkflowServiceStubs().getRawChannel())
                    .validateAndBuildWithDefaults()).blockingStub();
        synchronized (this) {
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
            if (!namespaceIsReady(operatorServiceBlockingStub, namespace)) {
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
                Thread.sleep(DEFAULT_NAMESPACE_POLL_INTERVAL.toMillis());
            }
        }
    }

    public void deleteNamespace(Duration timeout) {
        String namespace = client.getOptions().getNamespace();
        OperatorServiceStubs.newServiceStubs(
                OperatorServiceStubsOptions.newBuilder()
                    .setChannel(client.getWorkflowServiceStubs().getRawChannel())
                    .validateAndBuildWithDefaults())
            .blockingStub()
            .deleteNamespace(DeleteNamespaceRequest.newBuilder().setNamespace(namespace).build());
        awaitNamespaceDeleted(client.getWorkflowServiceStubs().blockingStub(), namespace, timeout);
    }

    public void createWorkflow(String taskId, String name, String queueName, SearchAttributes searchAttributes, Map<String, Object> args) {
        WorkflowOptions.Builder optionBuilder = WorkflowOptions.newBuilder().setWorkflowId(taskId)
                .setWorkflowTaskTimeout(DEFAULT_WORKFLOW_TASK_TIMEOUT) // TODO: set this per task
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .setTypedSearchAttributes(searchAttributes)
                .setTaskQueue(queueName);
        WorkflowStub workflowStub = client.newUntypedWorkflowStub(name, optionBuilder.build());
        WorkflowExecution exec = workflowStub.start(new TemporalInputPayload(args));
        // Super important force description to refresh the cache and make the task visible
        workflowStub.describe();
        executions.add(taskId);
    }

    public WorkflowStub createWorkflowStub(String workflowId) {
        return client.newUntypedWorkflowStub(workflowId);
    }

    public String getNamespace() {
        return client.getOptions().getNamespace();
    }

    public boolean terminateWorkflow(String taskId) {
        // TODO: add support for cancellation rather than termination, update the TaskManager API accordingly
        return unknownIfNotFound(tId -> {
            WorkflowStub workflowStub = createWorkflowStub(tId);
            if(workflowStub.describe().getStatus() != WORKFLOW_EXECUTION_STATUS_RUNNING) {
                return false;
            }
            try {
                workflowStub.terminate(TERMINATION_MSG);
            } catch (WorkflowNotFoundException ex) {
                if (!ex.getCause().getMessage().contains("workflow execution already completed")) {
                    throw ex;
                }
            }
            return workflowStub.describe().getStatus() == WORKFLOW_EXECUTION_STATUS_TERMINATED;
        }, taskId);
    }

    public boolean getHealth() {
        return client.getWorkflowServiceStubs().healthCheck().getStatus().equals(SERVING);
    }

    public Map<String, Object> getArgs(WorkflowExecutionInfo workflowExecutionInfo) {
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
            args.computeIfPresent(USER_KEY, (k, v) -> JsonObjectMapper.convertValue(v, User.class));
        }
        return args;
    }

    public PageFetcher<WorkflowExecutionInfo> getWorkflowExecutionFetcher(String query) {
        return (ByteString nextPageToken) -> {
            ListWorkflowExecutionsRequest.Builder requestBuilder =
                ListWorkflowExecutionsRequest.newBuilder().setNamespace(getNamespace()).setPageSize(DEFAULT_PAGE_SIZE);
            if (nextPageToken != null) {
                requestBuilder.setNextPageToken(nextPageToken);
            }
            Optional.ofNullable(query).ifPresent(q -> {
                if (!q.isEmpty()) {
                    requestBuilder.setQuery(q);
                }
            });
            ListWorkflowExecutionsResponse response = workflowServiceStubs
                .listWorkflowExecutions(requestBuilder.build());
            return new Page<>(response.getExecutionsList(), response.getNextPageToken());
        };
    }

    public void deleteExecution(String workflowId) {
        try {
            DeleteWorkflowExecutionRequest deleteWorkflowExecutionRequest = DeleteWorkflowExecutionRequest.newBuilder()
                .setNamespace(getNamespace())
                .setWorkflowExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                .build();
            workflowServiceStubs.deleteWorkflowExecution(deleteWorkflowExecutionRequest);
            // Try to refresh the cache
            client.newUntypedWorkflowStub(workflowId).describe();
        } catch (StatusRuntimeException ex) {
            if (!ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                throw ex;
            }
        } catch (WorkflowNotFoundException ignored) {
        }
        executions.remove(workflowId);
    }

    public Stream<String> getWorkflowsIds(TaskFilters filters) {
        Stream<WorkflowExecutionInfo> execs = eventuallyConsistentListExecutions(filters);
        Iterator<WorkflowExecutionInfo> iterator =
                new TemporalInterlocutor.StronglyConsistentExecutionIterator(execs, executions, this::fetchExecByIdsIfExist, filters);
        execs = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                false // not parallel
        );
        // Temporal doesn't allow to search by args we have to post filter task retrieved with other filters
        if (filters.hasArgs()) {
            TaskFilters byArgs = TaskFilters.empty().withArgs(filters.getArgs());
            execs = execs.map(e -> new Pair<>(e, getArgs(e)))
                    .filter(p -> byArgs.filter(p._2()))
                    .map(Pair::_1);
        }
        return execs.map(e -> e.getExecution().getWorkflowId());
    }

    public Stream<WorkflowExecutionInfo> eventuallyConsistentListExecutions(TaskFilters filters) {
        PageFetcher<WorkflowExecutionInfo> fetcher =
            getWorkflowExecutionFetcher(TemporalQueryBuilder.buildFromFilters(filters));
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(new TemporalPageIterator<>(fetcher), Spliterator.ORDERED), false);
    }

    public WorkflowExecutionDescription getWorkflowExecution(String taskId) throws UnknownTask {
        return unknownIfNotFound(t -> createWorkflowStub(taskId).describe(), taskId);
    }

    List<RegisteredWorkflow> discoverWorkflows(String packageName, TaskFactory taskFactory, TaskRepository taskRepository, RoutingStrategy routingStrategy, Group group) {
        Reflections reflections = new Reflections(packageName);
        Predicate<Class<?>> workflowFilter = makeWorkflowFilter(routingStrategy, group);
        // We rely on naming convention rather than on inspection, that's OK as code is generated
        try {
            return reflections.getTypesAnnotatedWith(WorkflowInterface.class)
                    .stream()
                    .filter(workflowFilter)
                    .map(rethrowFunction(c -> {
                        String workflowKey = parseWorkflowKey(c);
                        String workflowClassName = c.getName();
                        String baseName = workflowClassName.replace("Workflow", "");
                        Class<TemporalWorkflowImpl> wfImplClass = (Class<TemporalWorkflowImpl>) Class.forName(workflowClassName + "Impl");
                        Class<TemporalActivityImpl<?, ?>> actImplCls = (Class<TemporalActivityImpl<?, ?>>) Class.forName(baseName + "ActivityImpl");
                        String taskQueue = resolveWfTaskQueue(routingStrategy, workflowKey, group);
                        List<RegisteredActivity> activities = List.of(new RegisteredActivity(activityFactory(actImplCls, taskFactory, taskRepository, 1d), taskQueue));
                        return new RegisteredWorkflow(wfImplClass, taskQueue, activities);
                    }))
                    .toList();
        } catch (ClassNotFoundException e) {
            throw new UnknownTask("Workflow class not found: ", e);
        }
    }

    public Closeable discoverWorkflows(int taskWorkersNb, TaskFactory taskFactory, TaskRepository taskRepository, RoutingStrategy routingStrategy, Group group) {
        List<RegisteredWorkflow> registeredWorkflows = discoverWorkflows("org.icij.datashare.tasks", taskFactory, taskRepository, routingStrategy, group);
        return createFactory(taskWorkersNb, registeredWorkflows);
    }

    public CloseableWorkerFactoryHandle createFactory(int taskWorkersNb, List<RegisteredWorkflow> registeredWorkflows) {
        WorkerFactory workerFactory = WorkerFactory.newInstance(client);
        HashMap<String, Worker> workers = new HashMap<>();
        WorkerOptions workerOptions = WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskExecutionSize(taskWorkersNb)
                .setMaxConcurrentActivityExecutionSize(taskWorkersNb)
                .build();
        registeredWorkflows.forEach(rethrowConsumer(wf -> {
            String wfTaskQueue = wf.taskQueue();
            workers.computeIfAbsent(wfTaskQueue, workerFactory::newWorker)
                    .registerWorkflowImplementationTypes(WF_IMPLEMENTATION_DEFAULT_OPTIONS, wf.workflowCls());
            wf.activities().forEach(rethrowConsumer(act -> {
                workers.computeIfAbsent(act.taskQueue(), q -> workerFactory.newWorker(q, workerOptions))
                        .registerActivitiesImplementations(act.activityFactory().get());
            }));
        }));
        return new CloseableWorkerFactoryHandle(workerFactory);
    }

    /**
     * Get the information of a Workflow running in Temporal represented as a Task
     * @param taskId
     * @return
     */
    public <V extends Serializable> Task<V> getTask(String taskId) {
        return parseTask(getWorkflowExecution(taskId));
    }

    public record Page<P>(List<P> items, ByteString nextPageToken) { }
    @FunctionalInterface
    public interface PageFetcher<P> {
        Page<P> fetchPage(ByteString nextPageToken);
    }

    public static class TemporalPageIterator<P> implements Iterator<P> {
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

    public static class StronglyConsistentExecutionIterator implements Iterator<WorkflowExecutionInfo> {
        private final Iterator<WorkflowExecutionInfo> listWorkflowsResults;
        private final Set<String> remainingIds;
        private final Function<Set<String>, Stream<WorkflowExecutionInfo>> fetchKnownExecInfoFn;
        private final TaskFilters filters;

        private Iterator<WorkflowExecutionInfo> missingItems = null;

        StronglyConsistentExecutionIterator(Stream<WorkflowExecutionInfo> listWorkflowsResults, Set<String> knownIds,
                                            Function<Set<String>, Stream<WorkflowExecutionInfo>> fetchKnownExecInfoFn,
                                            TaskFilters filters) {
            this.listWorkflowsResults = listWorkflowsResults.iterator();
            this.remainingIds = new HashSet<>(knownIds);
            this.fetchKnownExecInfoFn = fetchKnownExecInfoFn;
            this.filters = filters;
        }

        @Override
        public boolean hasNext() {
            if (listWorkflowsResults.hasNext()) {
                return true;
            }
            if (missingItems == null) {
                if (!this.remainingIds.isEmpty()) {
                    missingItems = fetchKnownExecInfoFn.apply(this.remainingIds)
                        .filter(asExecInfoFilter(filters))
                        .iterator();
                } else {
                    missingItems = Stream.<WorkflowExecutionInfo>of().iterator();
                }
            }
            return missingItems.hasNext();
        }

        @Override
        public WorkflowExecutionInfo next() {
            if (listWorkflowsResults.hasNext()) {
                WorkflowExecutionInfo fromSearch = listWorkflowsResults.next();
                remainingIds.remove(fromSearch.getExecution().getWorkflowId());
                return fromSearch;
            }
            return missingItems.next();
        }
    }

    public record CloseableWorkerFactoryHandle(WorkerFactory factory) implements Closeable {
            public CloseableWorkerFactoryHandle(WorkerFactory factory) {
                this.factory = factory;
                this.factory.start();
            }

            @Override
            public void close() throws IOException {
                synchronized (factory) {
                    if (!this.factory.isShutdown()) {
                        this.factory.shutdown();
                    }
                }
            }
        }

    public record RegisteredActivity(ThrowingSupplier<?> activityFactory, String taskQueue) { }
    public record RegisteredWorkflow(Class<?> workflowCls, String taskQueue, List<RegisteredActivity> activities) { }

    // ------------------------
    // private utility functions
    private <V extends Serializable> Task<V> parseTask(WorkflowExecutionDescription workflowExecutionDescription) {
        WorkflowExecutionInfo workflowExecutionInfo = workflowExecutionDescription.getWorkflowExecutionInfo();
        WorkflowExecution execution = workflowExecutionInfo.getExecution();
        String taskId = execution.getWorkflowId();

        // Use the search attributes of the workflowExecutionDescription which retrieves the updated search attributes.
        // The search attributes from workflowExecutionInfo are called indexedFields, and are not updated over time
        double progress = parseProgress(workflowExecutionDescription.getTypedSearchAttributes());
        String taskName = workflowExecutionInfo.getType().getName();
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
                Serializable res = createWorkflowStub(workflowExecutionInfo.getExecution().getWorkflowId())
                        .getResult(Serializable.class);
                //TODO this is a big hack because Temporal does not use the TYPE_INCLUSION_MAPPER
                // to deserialize for now
                if (res instanceof Map<?, ?>) {
                    try {
                        res = JsonObjectMapper.readValueTyped(
                                JsonObjectMapper.writeValueAsString(res), Serializable.class);
                    } catch (IOException e) {
                        TaskManager.logger.warn("could not convert result to typed object", e);
                    }
                }
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

    private static WorkflowClient buildClient(WorkflowServiceStubs serviceStub, WorkflowClientOptions clientOptions) {
        return WorkflowClient.newInstance(serviceStub, clientOptions);
    }

    private Stream<WorkflowExecutionInfo> fetchExecByIdsIfExist(Set<String> executionIds) {
        return executionIds.stream()
                .map(id -> {
                    try {
                        return createWorkflowStub(id).describe().getWorkflowExecutionInfo();
                    } catch (StatusRuntimeException ex) {
                        if (!ex.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                            throw ex;
                        }
                        return null;
                    } catch (WorkflowNotFoundException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull);
    }

    private static double parseProgress(SearchAttributes searchAttributes) {
        Double progress = searchAttributes.get(PROGRESS_CUSTOM_ATTRIBUTE);
        Double maxProgress = searchAttributes.get(MAX_PROGRESS_CUSTOM_ATTRIBUTE);
        if(maxProgress == 0d) {
            return 0.0;
        }
        return progress == null ? 0.0 : progress / maxProgress;
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

    private static WorkflowClient buildClient(String target, String namespace) {
        WorkflowClientOptions clientOptions = WorkflowClientOptions.newBuilder().setNamespace(namespace).build();
        WorkflowServiceStubsOptions serviceStubsOptions = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target)
                .build();
        WorkflowServiceStubs serviceStub = WorkflowServiceStubs.newServiceStubs(serviceStubsOptions);
        return buildClient(serviceStub, clientOptions);
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
        Set<String> searchAttributes;
        try {
            searchAttributes = operatorServiceBlockingStub
                    .listSearchAttributes(ListSearchAttributesRequest.newBuilder().setNamespace(namespace).build())
                    .getCustomAttributesMap()
                    .keySet();
        } catch (StatusRuntimeException e) {
            if (!e.getStatus().getCode().equals(Status.Code.NOT_FOUND) && !e.getStatus().getCode().equals(Status.Code.FAILED_PRECONDITION)) {
                throw e;
            }
            return false;
        }
        return searchAttributes.containsAll(CUSTOM_SEARCH_ATTRIBUTES.keySet());
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
}
