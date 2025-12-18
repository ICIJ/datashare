package org.icij.datashare;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.icij.datashare.activity.ProduceImpl;

public class MainWorker {
    public static final String TASK_QUEUE = "activities-JAVA";

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(TASK_QUEUE);

        worker.registerActivitiesImplementations(new ProduceImpl());

        factory.start();
    }
}