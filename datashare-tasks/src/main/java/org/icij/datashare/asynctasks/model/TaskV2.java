package org.icij.datashare.asynctasks.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;

// TODO: changename
public class TaskV2<V extends Serializable> extends Task<V> {

    @JsonCreator
    public TaskV2(@JsonProperty("id") String id,
                  @JsonProperty("name") String name,
                  @JsonProperty("state") State state,
                  @JsonProperty("progress") double progress,
                  @JsonProperty("createdAt") Date createdAt,
                  @JsonProperty("retriesLeft") int retriesLeft,
                  @JsonProperty("completedAt") Date completedAt,
                  @JsonProperty("args") Map<String, Object> args,
                  @JsonProperty("result") TaskResult<V> result,
                  @JsonProperty("error") TaskError error
    ) {
        super(id, name, state, progress, createdAt, retriesLeft, completedAt, args, result, error);
    }

    private TaskType type;
    private Group group;
    private String referenceName;
    private int seq;
    private String correlationId;
    private Date queuedAt;
    private Date runningAt;
    private Date firstRunningAt;
    private int startDelayS;
    // Failing/cancelled workflows can be retried, they start again from the last failed/cancel task. At this point a
    // new task is created form the failed one
    private String retriedTaskId;
    private boolean retried;
    // TODO: the state of the task should be enough determine if the task is being
    private boolean executed;
    private long responseTimeoutS;
    private String workflowId;
    private String workflowName;

    // TODO: handle this one to see if needed
    private long callbackAfterSeconds;

    // TODO: handler outputs with bytes
    private Map<String, Object> outputData = new HashMap<>();

    private WorkflowTask workflowTask;

    private int iteration;

    public TaskV2() {
    }

    @Override
    public String getName() {
        if (name == null || name.isEmpty()) {
            this.name = type.name();
        }
        return name;
    }

    public TaskType getType() {
        return type;
    }

    public void setType(TaskType type) {
        this.type = type;
    }

    public Map<String, Object> getInputs() {
        return args;
    }

    public void setInputs(Map<String, Object> inputs) {
        if (inputs == null) {
            inputs = Map.of();
        }
        this.args = inputs;
    }

    public String getReferenceName() {
        return referenceName;
    }

    public void setReferenceName(String referenceName) {
        this.referenceName = referenceName;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Date getQueue() {
        return queuedAt;
    }


    public Date getScheduledTime() {
        return this.queuedAt;
    }

    public void setScheduledTime(Date queuedAt) {
        this.queuedAt = queuedAt;
    }

    public Date getStartTime() {
        return runningAt;
    }

    public void setStartTime(Date runningAt) {
        this.runningAt = runningAt;
    }

    public void setCompletedAt(Date completedAt) {
        this.completedAt = completedAt;
    }

    public int getStartDelayS() {
        return startDelayS;
    }

    public void setStartDelayS(int startDelayS) {
        this.startDelayS = startDelayS;
    }

    public String getRetriedTaskId() {
        return retriedTaskId;
    }

    public void setRetriedTaskId(String retriedTaskId) {
        this.retriedTaskId = retriedTaskId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public long getQueueWaitTime() {
        if (this.runningAt != null && this.queuedAt != null) {
            if (this.updatedAt != null && getCallbackAfterSeconds() > 0) {
                long waitTime = System.currentTimeMillis() - (this.updatedAt + (getCallbackAfterSeconds() * 1000));
                return waitTime > 0 ? waitTime : 0;
            } else {
                return (this.runningAt.toInstant().toEpochMilli() - this.queuedAt.toInstant().toEpochMilli()) * 1000;
            }
        }
        return 0L;
    }

    public boolean isRetried() {
        return retried;
    }

    public void setRetried(boolean retried) {
        this.retried = retried;
    }

    public boolean isExecuted() {
        return executed;
    }

    public void setExecuted(boolean executed) {
        this.executed = executed;
    }

    public long getResponseTimeoutS() {
        return responseTimeoutS;
    }

    public void setResponseTimeoutS(long responseTimeoutS) {
        this.responseTimeoutS = responseTimeoutS;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public TaskV2<V> setWorkflowType(String workflowType) {
        this.workflowName = workflowType;
        return this;
    }

    public String getReasonForIncompletion() {
        return getError().getMessage();
    }

    public long getCallbackAfterSeconds() {
        return callbackAfterSeconds;
    }

    public void setCallbackAfterSeconds(long callbackAfterSeconds) {
        this.callbackAfterSeconds = callbackAfterSeconds;
    }

    // TODO + blocker: we need the output data to be a Map<String, Object>. Special keys are used to add builtin
    //  task results to user output
    public Map<String, Object> getOutputData() {
        return getResult();
    }


    public WorkflowTask getWorkflowTask() {
        return workflowTask;
    }

    public void setWorkflowTask(WorkflowTask workflowTask) {
        this.workflowTask = workflowTask;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    @JsonIgnore
    public Optional<TaskDef> getTaskDefinition() {
        return Optional.ofNullable(this.getWorkflowTask()).map(WorkflowTask::getTaskDefinition);
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int iteration) {
        this.iteration = iteration;
    }

    public boolean isLoopOverTask() {
        return iteration > 0;
    }

    public Date getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(Date queuedAt) {
        this.queuedAt = queuedAt;
    }

    public Date getRunningAt() {
        return runningAt;
    }

    public void setRunningAt(Date runningAt) {
        this.runningAt = runningAt;
    }

    public Date getFirstRunningAt() {
        return firstRunningAt;
    }

    public void setFirstRunningAt(Date firstRunningAt) {
        this.firstRunningAt = firstRunningAt;
    }

    public TaskV2<V> copy() {
        TaskV2<V> copy = new TaskV2<>(id);
        copy.id = id;
        copy.name = name;
        copy.retriesLeft = retriesLeft;
        copy.setCreatedAt(createdAt);
        copy.setError(getError());
        copy.setState(getState());
        copy.setCompletedAt(completedAt);
        copy.setProgress(getProgress());
        copy.setResult(getResult());
        copy.setType(type);
        copy.setGroup(group);
        copy.setReferenceName(referenceName);
        copy.setSeq(seq);
        copy.setCorrelationId(correlationId);
        copy.setQueuedAt(queuedAt);
        copy.setRunningAt(runningAt);
        copy.setFirstRunningAt(firstRunningAt);
        copy.setStartDelayS(startDelayS);
        copy.setRetried(retried);
        copy.setRetriedTaskId(retriedTaskId);
        copy.setExecuted(executed);
        copy.setResponseTimeoutS(responseTimeoutS);
        copy.setWorkflowId(workflowId);
        copy.setWorkflowType(workflowName);
        copy.setCallbackAfterSeconds(callbackAfterSeconds);
        copy.setWorkflowTask(workflowTask);
        copy.setIteration(iteration);
        return copy;
    }

    @Override
    public String toString() {
        return "Task{"
            + "taskType='"
            + type
            + '\''
            + ", state="
            + getState()
            + ", inputs="
            + getInputs()
            + ", refName='"
            + referenceName
            + '\''
            + ", retriesLeft="
            + retriesLeft
            + ", seq="
            + seq
            + ", correlationId='"
            + correlationId
            + '\''
            + ", name='"
            + name
            + '\''
            + ", queuedAt="
            + queuedAt
            + ", runningAt="
            + runningAt
            + ", completedAt="
            + completedAt
            + ", updateTime="
            + updateTime
            + ", startDelayS="
            + startDelayS
            + ", retriedTaskId='"
            + retriedTaskId
            + '\''
            + ", retried="
            + retried
            + ", executed="
            + executed
            + ", responseTimeoutSeconds="
            + responseTimeoutS
            + ", workflowInstanceId='"
            + workflowId
            + '\''
            + ", workflowType='"
            + workflowName
            + '\''
            + ", id='"
            + id
            + '\''
            + ", reasonForIncompletion='"
            + getReasonForIncompletion()
            + '\''
            + ", callbackAfterSeconds="
            + callbackAfterSeconds
            + '\''
            + ", outputData="
            + outputData
            + ", workflowTask="
            + workflowTask
            + ", group='"
            + group
            + '\''
            + ", firstStartTime='"
            + firstRunningAt
            + '\''
            + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskV2<?> task = (TaskV2<?>) o;
        return Objects.equals(getId(), task.getId())
            && Objects.equals(getName(), task.getName())
            && Objects.equals(retriesLeft, task.getRetriesLeft())
            && Objects.equals(createdAt, task.getCreatedAt())
            && Objects.equals(getError(), task.getError())
            && Objects.equals(getState(), task.getState())
            && Objects.equals(completedAt, task.getCompletedAt())
            && Objects.equals(getProgress(), task.getProgress())
            && Objects.equals(getResult(), task.getResult())
            && Objects.equals(type, task.getType())
            && Objects.equals(group, task.getGroup())
            && Objects.equals(referenceName, task.getReferenceName())
            && Objects.equals(seq, task.getSeq())
            && Objects.equals(correlationId, task.getCorrelationId())
            && Objects.equals(queuedAt, task.getQueuedAt())
            && Objects.equals(runningAt, task.getRunningAt())
            && Objects.equals(firstRunningAt, task.getFirstRunningAt())
            && Objects.equals(startDelayS, task.getStartDelayS())
            && Objects.equals(retried, task.isRetried())
            && Objects.equals(retriedTaskId, task.getRetriedTaskId())
            && Objects.equals(executed, task.isExecuted())
            && Objects.equals(responseTimeoutS, task.getResponseTimeoutS())
            && Objects.equals(workflowId, task.getWorkflowId())
            && Objects.equals(workflowName, task.getWorkflowName())
            && Objects.equals(callbackAfterSeconds, task.getCallbackAfterSeconds())
            && Objects.equals(workflowTask, task.getWorkflowTask())
            && Objects.equals(iteration, task.getIteration());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(),
            getName(),
            retriesLeft,
            createdAt,
            getError(),
            getState(),
            completedAt,
            getProgress(),
            getResult(),
            type,
            group,
            referenceName,
            seq,
            correlationId,
            queuedAt,
            runningAt,
            firstRunningAt,
            startDelayS,
            retried,
            retriedTaskId,
            executed,
            responseTimeoutS,
            workflowId,
            workflowName,
            callbackAfterSeconds,
            workflowTask,
            iteration);
    }
}
