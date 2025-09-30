package org.icij.datashare.asynctasks.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.beanutils.BeanUtils;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;

public class Workflow implements Updatable {
    // TODO: replace Task.State by a dedicated enum
    private Task.State state = Task.State.CREATED;
    private Task.State previousState;

    private volatile Date createdAt;
    private volatile Date updatedAt;
    private volatile Date completedAt;
    private volatile Date lastRetriedAt;

    private String id;

    private List<TaskV2<?>> tasks = new LinkedList<>();

    private String correlationId;

    private String reasonForIncompletion;

    private String event;

    private Map<String, Group> taskGroups = new HashMap<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<String> failedReferenceTaskNames = new HashSet<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<String> failedTaskNames = new HashSet<>();

    private WorkflowDef workflowDefinition;

    private Map<String, Object> variables = new HashMap<>();

    private String app;

    private String failedTaskId;

    private Map<String, Object> input = new HashMap<>();

    private Map<String, Object> output = new HashMap<>();



    public Task.State getPreviousState() {
        return previousState;
    }

    public void setPreviousState(Task.State status) {
        this.previousState = status;
    }

    public Task.State getState() {
        return state;
    }

    public void setState(Task.State state) {
        if (this.state != state) {
            setPreviousState(this.state);
        }
        this.state = state;
    }

    public Date getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Date completedAt) {
        this.completedAt = completedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<TaskV2<?>> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskV2<?>> tasks) {
        this.tasks = tasks;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        if (input == null) {
            input = new HashMap<>();
        }
        this.input = input;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        if (output == null) {
            output = new HashMap<>();
        }
        this.output = output;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getReasonForIncompletion() {
        return reasonForIncompletion;
    }

    public void setReasonForIncompletion(String reasonForIncompletion) {
        this.reasonForIncompletion = reasonForIncompletion;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public Map<String, Group> getTaskGroups() {
        return taskGroups;
    }

    public void setTaskGroups(Map<String, Group> taskGroups) {
        this.taskGroups = taskGroups;
    }

    public Set<String> getFailedReferenceTaskNames() {
        return failedReferenceTaskNames;
    }

    public void setFailedReferenceTaskNames(Set<String> failedReferenceTaskNames) {
        this.failedReferenceTaskNames = failedReferenceTaskNames;
    }

    public Set<String> getFailedTaskNames() {
        return failedTaskNames;
    }

    public void setFailedTaskNames(Set<String> failedTaskNames) {
        this.failedTaskNames = failedTaskNames;
    }

    public WorkflowDef getWorkflowDefinition() {
        return workflowDefinition;
    }

    public void setWorkflowDefinition(WorkflowDef workflowDefinition) {
        this.workflowDefinition = workflowDefinition;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }

    public Date getLastRetriedAt() {
        return lastRetriedAt;
    }

    public void setLastRetriedAt(Date lastRetriedAt) {
        this.lastRetriedAt = lastRetriedAt;
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getFailedTaskId() {
        return failedTaskId;
    }

    public void setFailedTaskId(String failedTaskId) {
        this.failedTaskId = failedTaskId;
    }

    public String getWorkflowName() {
        Objects.requireNonNull(workflowDefinition, "Workflow definition is null");
        return workflowDefinition.getName();
    }

    public int getWorkflowVersion() {
        Objects.requireNonNull(workflowDefinition, "Workflow definition is null");
        return workflowDefinition.getVersion();
    }

    @Override
    public String toString() {
        String name = workflowDefinition != null ? workflowDefinition.getName() : null;
        Integer version = workflowDefinition != null ? workflowDefinition.getVersion() : null;
        return String.format("%s.%s/%s.%s", name, version, id, state);
    }


    public String toShortString() {
        String name = workflowDefinition != null ? workflowDefinition.getName() : null;
        Integer version = workflowDefinition != null ? workflowDefinition.getVersion() : null;
        return String.format("%s.%s/%s", name, version, id);
    }

    public TaskV2<?> getTaskByRefName(String refName) {
        if (refName == null) {
            throw new RuntimeException(
                "refName passed is null.  Check the workflow execution.  For dynamic tasks, make sure referenceTaskName is set to a not null value");
        }
        LinkedList<TaskV2<?>> found = new LinkedList<>();
        for (TaskV2<?> task : tasks) {
            if (task.getReferenceName() == null) {
                throw new RuntimeException("Task " + task.getName() + " does not have reference name specified.");
            }
            if (task.getReferenceName().equals(refName)) {
                found.add(task);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return found.getLast();
    }

    public Workflow copy() {
        Workflow copy = new Workflow();
        try {
            BeanUtils.copyProperties(this, copy);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("failed to copy " + Workflow.class.getName(), e);
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Workflow workflow = (Workflow) o;
        return getCreatedAt() == workflow.getCreatedAt()
            && getUpdatedAt() == workflow.getUpdatedAt()
            && getState() == workflow.getState() 
            && getPreviousState() == workflow.getPreviousState()
            && getCompletedAt().equals(workflow.getCompletedAt()) 
            && getLastRetriedAt().equals(workflow.getLastRetriedAt())
            && getId().equals(workflow.getId())
            && getTasks().equals(workflow.getTasks()) 
            && getCorrelationId().equals(workflow.getCorrelationId())
            && getReasonForIncompletion().equals(workflow.getReasonForIncompletion()) 
            && getEvent().equals(workflow.getEvent())
            && getTaskGroups().equals(workflow.getTaskGroups()) 
            && getFailedReferenceTaskNames().equals(workflow.getFailedReferenceTaskNames()) 
            && getFailedTaskNames().equals(workflow.getFailedTaskNames()) 
            && getWorkflowDefinition().equals(workflow.getWorkflowDefinition())
            && getVariables().equals(workflow.getVariables())
            && getApp().equals(workflow.getApp()) 
            && getFailedTaskId().equals(workflow.getFailedTaskId()) 
            && getInput().equals(workflow.getInput())
            && getOutput().equals(workflow.getOutput());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getCreatedAt(), getUpdatedAt(), getState(),
            getPreviousState(), getCompletedAt(), getLastRetriedAt(), getId(), getTasks(), getCorrelationId(), 
            getReasonForIncompletion(), getEvent(), getTaskGroups(), getFailedReferenceTaskNames(), 
            getFailedTaskNames(), getWorkflowDefinition(), getVariables(), getApp(), getFailedTaskId(), getInput(),
            getOutput());
    }

    public void addInput(String key, Object value) {
        this.input.put(key, value);
    }

    public void addInput(Map<String, Object> inputData) {
        if (inputData != null) {
            this.input.putAll(inputData);
        }
    }

    public void addOutput(String key, Object value) {
        this.output.put(key, value);
    }

    public void addOutput(Map<String, Object> outputData) {
        if (outputData != null) {
            this.output.putAll(outputData);
        }
    }

    @Override
    public Date getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public Date getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdateAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
