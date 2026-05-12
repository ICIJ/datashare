package org.icij.datashare.asynctasks.temporal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.icij.datashare.EnvUtils;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.DEFAULT_NAMESPACE;

public class TemporalInterlocutorTest {
    @Test
    public void test_health_ok() throws InterruptedException {
        TemporalInterlocutor temporal = new TemporalInterlocutor(EnvUtils.resolve("temporalTarget", "temporal:7233"), DEFAULT_NAMESPACE);
        assertThat(temporal.getHealth()).isTrue();
    }

    @Test
    public void test_health_ko() {
        WorkflowServiceStubsOptions serviceStubsOptions = WorkflowServiceStubsOptions.newBuilder()
                .setTarget("localhost:1111")
                .build();
        WorkflowServiceStubs serviceStub = WorkflowServiceStubs.newServiceStubs(serviceStubsOptions);
        WorkflowClient koClient = WorkflowClient.newInstance(
                serviceStub, WorkflowClientOptions.newBuilder().setNamespace(DEFAULT_NAMESPACE).build()
        );

        try {
            TemporalInterlocutor koInterlocutor = new TemporalInterlocutor(koClient);
            assertThat(koInterlocutor.getHealth()).isFalse();
        } catch(StatusRuntimeException sre) {
            assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        }
    }
}