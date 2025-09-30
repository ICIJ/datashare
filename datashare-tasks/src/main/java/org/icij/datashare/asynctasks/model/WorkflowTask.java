package org.icij.datashare.asynctasks.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Task definition defined as part of the {@link WorkflowDef}
 */
public class WorkflowTask {

    @NotEmpty(message = "WorkflowTask name cannot be empty or null")
    private String name;

    @NotEmpty(message = "WorkflowTask taskReferenceName name cannot be empty or null")
    private String taskReferenceName;

    private String description;

    private Map<String, Object> inputParameters = new HashMap<>();

    private TaskType type = TaskType.NOOP;

    private String dynamicTaskNameParam;

    private String scriptExpression;

    public static class WorkflowTaskList {

        public List<WorkflowTask> getTasks() {
            return tasks;
        }

        public void setTasks(List<WorkflowTask> tasks) {
            this.tasks = tasks;
        }

        private List<WorkflowTask> tasks;
    }

    private Map<String, @Valid List<@Valid WorkflowTask>> decisionCases = new LinkedHashMap<>();

    private String dynamicForkTasksParam;

    private String dynamicForkTasksInputParamName;

    private List<@Valid WorkflowTask> defaultCase = new LinkedList<>();

    private List<@Valid List<@Valid WorkflowTask>> forkTasks = new LinkedList<>();

    @PositiveOrZero
    private int startDelayS;

    private List<String> joinOn = new LinkedList<>();

    private String sink;

    private TaskDef taskDefinition;

    private List<String> defaultExclusiveJoinTask = new LinkedList<>();

    private Boolean asyncComplete = false;

    private String loopCondition;

    private List<WorkflowTask> loopOver = new LinkedList<>();

    private Integer retriesLeft;

    private String evaluatorType;

    private String expression;

    private @Valid Map<String, List<StateChangeEvent>> onStateChange = new HashMap<>();

    private String joinStatus;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTaskReferenceName() {
        return taskReferenceName;
    }

    public void setTaskReferenceName(String taskReferenceName) {
        this.taskReferenceName = taskReferenceName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getInputParameters() {
        return inputParameters;
    }

    public void setInputParameters(Map<String, Object> inputParameters) {
        this.inputParameters = inputParameters;
    }

    public TaskType getType() {
        return type;
    }

    public void setWorkflowTaskType(TaskType type) {
        this.type = type;
    }

    public void setType(@NotEmpty(message = "WorkTask type cannot be null or empty") TaskType type) {
        this.type = type;
    }

    public Map<String, List<WorkflowTask>> getDecisionCases() {
        return decisionCases;
    }

    public void setDecisionCases(Map<String, List<WorkflowTask>> decisionCases) {
        this.decisionCases = decisionCases;
    }

    public List<WorkflowTask> getDefaultCase() {
        return defaultCase;
    }

    public void setDefaultCase(List<WorkflowTask> defaultCase) {
        this.defaultCase = defaultCase;
    }

    public List<List<WorkflowTask>> getForkTasks() {
        return forkTasks;
    }

    public void setForkTasks(List<List<WorkflowTask>> forkTasks) {
        this.forkTasks = forkTasks;
    }

    public int getStartDelayS() {
        return startDelayS;
    }

    public void setStartDelayS(int startDelayS) {
        this.startDelayS = startDelayS;
    }

    public Integer getRetriesLeft() {
        return retriesLeft;
    }

    public void setRetriesLeft(final Integer retriesLeft) {
        this.retriesLeft = retriesLeft;
    }

    public String getDynamicTaskNameParam() {
        return dynamicTaskNameParam;
    }

    public void setDynamicTaskNameParam(String dynamicTaskNameParam) {
        this.dynamicTaskNameParam = dynamicTaskNameParam;
    }

    public String getDynamicForkTasksParam() {
        return dynamicForkTasksParam;
    }

    public void setDynamicForkTasksParam(String dynamicForkTasksParam) {
        this.dynamicForkTasksParam = dynamicForkTasksParam;
    }

    public String getDynamicForkTasksInputParamName() {
        return dynamicForkTasksInputParamName;
    }

    public void setDynamicForkTasksInputParamName(String dynamicForkTasksInputParamName) {
        this.dynamicForkTasksInputParamName = dynamicForkTasksInputParamName;
    }

    public String getScriptExpression() {
        return scriptExpression;
    }

    public void setScriptExpression(String expression) {
        this.scriptExpression = expression;
    }


    public List<String> getJoinOn() {
        return joinOn;
    }

    public void setJoinOn(List<String> joinOn) {
        this.joinOn = joinOn;
    }

    public String getLoopCondition() {
        return loopCondition;
    }

    public void setLoopCondition(String loopCondition) {
        this.loopCondition = loopCondition;
    }

    public List<WorkflowTask> getLoopOver() {
        return loopOver;
    }

    public void setLoopOver(List<WorkflowTask> loopOver) {
        this.loopOver = loopOver;
    }

    public String getSink() {
        return sink;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }

    public Boolean isAsyncComplete() {
        return asyncComplete;
    }

    public void setAsyncComplete(Boolean asyncComplete) {
        this.asyncComplete = asyncComplete;
    }


    public TaskDef getTaskDefinition() {
        return taskDefinition;
    }

    public void setTaskDefinition(TaskDef taskDefinition) {
        this.taskDefinition = taskDefinition;
    }

    public List<String> getDefaultExclusiveJoinTask() {
        return defaultExclusiveJoinTask;
    }

    public void setDefaultExclusiveJoinTask(List<String> defaultExclusiveJoinTask) {
        this.defaultExclusiveJoinTask = defaultExclusiveJoinTask;
    }

    public String getEvaluatorType() {
        return evaluatorType;
    }

    public void setEvaluatorType(String evaluatorType) {
        this.evaluatorType = evaluatorType;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getJoinStatus() {
        return joinStatus;
    }

    public void setJoinStatus(String joinStatus) {
        this.joinStatus = joinStatus;
    }

    private Collection<List<WorkflowTask>> children() {
        Collection<List<WorkflowTask>> workflowTaskLists = new LinkedList<>();
        switch (type) {
            // TODO: handle SWITCH/DO_WHILE/FORK_JOIN
            default:
                break;
        }
        return workflowTaskLists;
    }

    public List<WorkflowTask> collectTasks() {
        List<WorkflowTask> tasks = new LinkedList<>();
        tasks.add(this);
        for (List<WorkflowTask> workflowTaskList : children()) {
            for (WorkflowTask workflowTask : workflowTaskList) {
                tasks.addAll(workflowTask.collectTasks());
            }
        }
        return tasks;
    }

    public WorkflowTask next(String taskReferenceName, WorkflowTask parent) {
        switch (type) {
            // TODO: handle DO_WHILE/SWITCH/FORK_JOIN/DYNAMIC/TERMINATE/SIMPLE
            default:
                break;
        }
        return null;
    }

    public boolean has(String taskReferenceName) {
        // TODO: handle SWITCH/DO_WHILE/FORK_JOIN
        if (this.getTaskReferenceName().equals(taskReferenceName)) {
            return true;
        }
        switch (type) {
            default:
                break;
        }
        return false;
    }

    public WorkflowTask get(String taskReferenceName) {

        if (this.getTaskReferenceName().equals(taskReferenceName)) {
            return this;
        }
        for (List<WorkflowTask> childx : children()) {
            for (WorkflowTask child : childx) {
                WorkflowTask found = child.get(taskReferenceName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public Map<String, List<StateChangeEvent>> getOnStateChange() {
        return onStateChange;
    }

    public void setOnStateChange(Map<String, List<StateChangeEvent>> onStateChange) {
        this.onStateChange = onStateChange;
    }

    @Override
    public String toString() {
        return name + "/" + taskReferenceName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WorkflowTask that = (WorkflowTask) o;
        return Objects.equals(name, that.name)
            && Objects.equals(taskReferenceName, that.taskReferenceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, taskReferenceName);
    }
}
