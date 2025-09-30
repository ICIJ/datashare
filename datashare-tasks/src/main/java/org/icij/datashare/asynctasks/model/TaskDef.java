package org.icij.datashare.asynctasks.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.constraints.TaskTimeoutConstraint;
import org.icij.datashare.user.User;

@TaskTimeoutConstraint
@Valid
public class TaskDef implements Updatable {

    public enum TimeoutPolicy {
        RETRY,
        TIME_OUT_WF
    }

    public enum RetryLogic {
        FIXED,
        EXPONENTIAL_BACKOFF,
        LINEAR_BACKOFF
    }

    public static final int ONE_HOUR = 60 * 60;

    @NotEmpty(message = "TaskDef name cannot be null or empty")
    private String name;

    private String description;

    @Min(value = 0, message = "TaskDef retriesLeft: {value} must be >= 0")
    private int retriesLeft = 3;

    private volatile Date createdAt;
    private volatile Date updatedAt;

    @NotNull private long timeoutS;

    private List<String> inputKeys = new ArrayList<>();

    private List<String> outputKeys = new ArrayList<>();

    private TimeoutPolicy timeoutPolicy = TimeoutPolicy.TIME_OUT_WF;

    private RetryLogic retryLogic = RetryLogic.FIXED;

    private int retryDelayS = 60;

    @Min(
            value = 1,
            message =
                    "TaskDef responseTimeoutS: ${validatedValue} should be minimum {value} second")
    private long responseTimeoutS = ONE_HOUR;


    private Map<String, Object> inputTemplate = new HashMap<>();

    private Group group;

    @Min(value = 1, message = "Backoff scale factor. Applicable for LINEAR_BACKOFF")
    private Integer backoffScaleFactor = 1;

    private SchemaDef inputSchema;
    private SchemaDef outputSchema;
    private boolean enforceSchema;

    public TaskDef() {}

    public TaskDef(String name) {
        this.name = name;
    }

    public TaskDef(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public TaskDef(String name, String description, int retriesLeft, long timeoutS) {
        this.name = name;
        this.description = description;
        this.retriesLeft = retriesLeft;
        this.timeoutS = timeoutS;
    }

    public TaskDef(
            String name,
            String description,
            int retriesLeft,
            long timeoutS,
            long responseTimeoutS) {
        this.name = name;
        this.description = description;
        this.retriesLeft = retriesLeft;
        this.timeoutS = timeoutS;
        this.responseTimeoutS = responseTimeoutS;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getRetriesLeft() {
        return retriesLeft;
    }

    public void setRetriesLeft(int retriesLeft) {
        this.retriesLeft = retriesLeft;
    }

    public long getTimeoutS() {
        return timeoutS;
    }

    public void setTimeoutS(long timeoutS) {
        this.timeoutS = timeoutS;
    }

    public List<String> getInputKeys() {
        return inputKeys;
    }

    public void setInputKeys(List<String> inputKeys) {
        this.inputKeys = inputKeys;
    }

    public List<String> getOutputKeys() {
        return outputKeys;
    }

    public void setOutputKeys(List<String> outputKeys) {
        this.outputKeys = outputKeys;
    }

    public TimeoutPolicy getTimeoutPolicy() {
        return timeoutPolicy;
    }

    public void setTimeoutPolicy(TimeoutPolicy timeoutPolicy) {
        this.timeoutPolicy = timeoutPolicy;
    }

    public RetryLogic getRetryLogic() {
        return retryLogic;
    }

    public void setRetryLogic(RetryLogic retryLogic) {
        this.retryLogic = retryLogic;
    }

    public int getRetryDelayS() {
        return retryDelayS;
    }

    public long getResponseTimeoutS() {
        return responseTimeoutS;
    }

    public void setResponseTimeoutS(long responseTimeoutS) {
        this.responseTimeoutS = responseTimeoutS;
    }

    public void setRetryDelayS(int retryDelayS) {
        this.retryDelayS = retryDelayS;
    }

    public Map<String, Object> getInputTemplate() {
        return inputTemplate;
    }

    public void setInputTemplate(Map<String, Object> inputTemplate) {
        this.inputTemplate = inputTemplate;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    public void setBackoffScaleFactor(Integer backoffScaleFactor) {
        this.backoffScaleFactor = backoffScaleFactor;
    }

    public Integer getBackoffScaleFactor() {
        return backoffScaleFactor;
    }

    public SchemaDef getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(SchemaDef inputSchema) {
        this.inputSchema = inputSchema;
    }

    public SchemaDef getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(SchemaDef outputSchema) {
        this.outputSchema = outputSchema;
    }

    public boolean isEnforceSchema() {
        return enforceSchema;
    }

    public void setEnforceSchema(boolean enforceSchema) {
        this.enforceSchema = enforceSchema;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskDef taskDef = (TaskDef) o;
        return getRetriesLeft() == taskDef.getRetriesLeft()
                && getTimeoutS() == taskDef.getTimeoutS()
                && getRetryDelayS() == taskDef.getRetryDelayS()
                && Objects.equals(getBackoffScaleFactor(), taskDef.getBackoffScaleFactor())
                && getResponseTimeoutS() == taskDef.getResponseTimeoutS()
                && Objects.equals(getName(), taskDef.getName())
                && Objects.equals(getDescription(), taskDef.getDescription())
                && Objects.equals(getInputKeys(), taskDef.getInputKeys())
                && Objects.equals(getOutputKeys(), taskDef.getOutputKeys())
                && getTimeoutPolicy() == taskDef.getTimeoutPolicy()
                && getRetryLogic() == taskDef.getRetryLogic()
                && Objects.equals(getInputTemplate(), taskDef.getInputTemplate())
                && Objects.equals(getInputSchema(), taskDef.getInputSchema())
                && Objects.equals(getOutputSchema(), taskDef.getOutputSchema());
    }

    @Override
    public int hashCode() {

        return Objects.hash(
                getName(),
                getDescription(),
                getRetriesLeft(),
                getTimeoutS(),
                getInputKeys(),
                getOutputKeys(),
                getTimeoutPolicy(),
                getRetryLogic(),
                getRetryDelayS(),
                getBackoffScaleFactor(),
                getResponseTimeoutS(),
                getInputTemplate(),
                getInputSchema(),
                getOutputSchema());
    }
}
