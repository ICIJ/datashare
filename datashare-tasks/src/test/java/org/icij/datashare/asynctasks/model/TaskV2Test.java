
package org.icij.datashare.asynctasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.core.status.Status;
import java.util.HashMap;
import org.junit.Test;

public class TaskV2Test {

    @Test
    public void testTaskDefinitionIfAvailable() {
        TaskV2<?> task = new TaskV2();
        task.setState(Status.ERROR);
        assertEquals(Status.ERROR, task.getState());

        assertNull(task.getWorkflowTask());
        assertFalse(task.getTaskDefinition().isPresent());

        WorkflowTask workflowTask = new WorkflowTask();
        TaskDef taskDefinition = new TaskDef();
        workflowTask.setTaskDefinition(taskDefinition);
        task.setWorkflowTask(workflowTask);

        assertTrue(task.getTaskDefinition().isPresent());
        assertEquals(taskDefinition, task.getTaskDefinition().get());
    }

    @Test
    public void testTaskQueueWaitTime() {
        TaskV2 task = new TaskV2();

        long currentTimeMillis = System.currentTimeMillis();
        task.setScheduledTime(currentTimeMillis - 30_000); // 30 seconds ago
        task.setStartTime(currentTimeMillis - 25_000);

        long queueWaitTime = task.getQueueWaitTime();
        assertEquals(5000L, queueWaitTime);

        task.setUpdateTime(currentTimeMillis - 20_000);
        task.setCallbackAfterSeconds(10);
        queueWaitTime = task.getQueueWaitTime();
        assertTrue(queueWaitTime > 0);
    }

    @Test
    public void testDeepCopyTask() {
        final TaskV2 task = new TaskV2();
        // In order to avoid forgetting putting inside the copy method the newly added fields check
        // the number of declared fields.
        final int expectedTaskFieldsNumber = 40;
        final int declaredFieldsNumber = task.getClass().getDeclaredFields().length;

        assertEquals(expectedTaskFieldsNumber, declaredFieldsNumber);

        task.setCallbackAfterSeconds(111L);
        task.setCallbackFromWorker(false);
        task.setCorrelationId("correlation_id");
        task.setInputs(new HashMap<>());
        task.setOutputData(new HashMap<>());
        task.setReferenceTaskName("ref_task_name");
        task.setStartDelayS(1);
        task.setTaskDefName("task_def_name");
        task.setType("dummy_task_type");
        task.setWorkflowId("workflowInstanceId");
        task.setWorkflowType("workflowType");
        task.setResponseTimeoutS(11L);
        task.setState(Status.COMPLETED);
        task.setRetryCount(0);
        task.setPollCount(0);
        task.setTaskId("taskId");
        task.setWorkflowTask(new WorkflowTask());
        task.setDomain("domain");
        task.setRateLimitPerFrequency(11);
        task.setRateLimitFrequencyInSeconds(11);
        task.setExternalInputPayloadStoragePath("externalInputPayloadStoragePath");
        task.setExternalOutputPayloadStoragePath("externalOutputPayloadStoragePath");
        task.setWorkflowPriority(0);
        task.setIteration(1);
        task.setExecutionNameSpace("name_space");
        task.setIsolationGroupId("groupId");
        task.setStartTime(12L);
        task.setEndTime(20L);
        task.setScheduledTime(7L);
        task.setRetried(false);
        task.setReasonForIncompletion("");
        task.setWorkerId("");
        task.setSubWorkflowId("");
        task.setSubworkflowChanged(false);

        final TaskV2 copy = task.deepCopy();
        assertEquals(task, copy);
    }
}
