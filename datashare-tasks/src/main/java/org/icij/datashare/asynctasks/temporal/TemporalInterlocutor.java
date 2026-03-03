package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.asynctasks.TaskManagerTemporal.DEFAULT_NAMESPACE;

import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.enums.v1.NamespaceState;
import io.temporal.api.namespace.v1.NamespaceConfig;
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest;
import io.temporal.api.operatorservice.v1.DeleteNamespaceRequest;
import io.temporal.api.operatorservice.v1.ListSearchAttributesRequest;
import io.temporal.api.operatorservice.v1.OperatorServiceGrpc;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.serviceclient.OperatorServiceStubs;
import io.temporal.serviceclient.OperatorServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManagerTemporal;

public class TemporalInterlocutor {
    public final WorkflowClient client;

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


    public TemporalInterlocutor(String target, String namespace) throws InterruptedException {
        this.client = buildClient(target, namespace);
        setupNamespace(Duration.ofSeconds(30));
    }

    public TemporalInterlocutor(PropertiesProvider propertiesProvider) throws InterruptedException {
        this(propertiesProvider.get("messageBusAddress").orElse(EnvUtils.resolveUri("temporalTarget", "temporal:7233")),
            propertiesProvider.get("temporalNamespace").orElse(DEFAULT_NAMESPACE));
    }

    private static WorkflowClient buildClient(WorkflowServiceStubs serviceStub, WorkflowClientOptions clientOptions) {
        return WorkflowClient.newInstance(serviceStub, clientOptions);
    }

    public void setupNamespace(Duration timeout) throws InterruptedException {
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
        Set<String> searchAttributes = operatorServiceBlockingStub
            .listSearchAttributes(ListSearchAttributesRequest.newBuilder().setNamespace(namespace).build())
            .getCustomAttributesMap()
            .keySet();
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
