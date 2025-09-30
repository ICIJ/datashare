package org.icij.datashare.asynctasks.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.icij.datashare.asynctasks.constraints.TaskReferenceNameUniqueConstraint;

@TaskReferenceNameUniqueConstraint
public class WorkflowDef implements Updatable {

    @NotEmpty(message = "WorkflowDef name cannot be null or empty")
    private String name;

    private String description;

    private int version = 1;

    private volatile Date createdAt;
    private volatile Date updatedAt;

    @NotNull
    @NotEmpty(message = "WorkflowTask list cannot be empty")
    private List<@Valid WorkflowTask> tasks = new LinkedList<>();

    private List<String> inputParameters = new LinkedList<>();

    private Map<String, Object> outputParameters = new HashMap<>();

    private String failureWorkflow;

    @Min(value = 1, message = "workflowDef schemaVersion: {value} is only supported")
    @Max(value = 1, message = "workflowDef schemaVersion: {value} is only supported")
    private int schemaVersion = 1;

    private Map<String, Object> variables = new HashMap<>();

    private Map<String, Object> inputTemplate = new HashMap<>();

    private SchemaDef inputSchema;

    private SchemaDef outputSchema;

    private boolean enforceSchema = true;

    private Map<String, Object> metadata = new HashMap<>();

    private List<String> maskedFields = new ArrayList<>();

    public static String getKey(String name, int version) {
        return name + "." + version;
    }

    public boolean isEnforceSchema() {
        return enforceSchema;
    }

    public void setEnforceSchema(boolean enforceSchema) {
        this.enforceSchema = enforceSchema;
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

    public List<WorkflowTask> getTasks() {
        return tasks;
    }

    public void setTasks(List<@Valid WorkflowTask> tasks) {
        this.tasks = tasks;
    }

    public List<String> getInputParameters() {
        return inputParameters;
    }

    public void setInputParameters(List<String> inputParameters) {
        this.inputParameters = inputParameters;
    }

    public Map<String, Object> getOutputParameters() {
        return outputParameters;
    }

    public void setOutputParameters(Map<String, Object> outputParameters) {
        this.outputParameters = outputParameters;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getFailureWorkflow() {
        return failureWorkflow;
    }

    public void setFailureWorkflow(String failureWorkflow) {
        this.failureWorkflow = failureWorkflow;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }

    public Map<String, Object> getInputTemplate() {
        return inputTemplate;
    }

    public void setInputTemplate(Map<String, Object> inputTemplate) {
        this.inputTemplate = inputTemplate;
    }

    public String key() {
        return getKey(name, version);
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public List<String> getMaskedFields() {
        return maskedFields;
    }

    public void setMaskedFields(List<String> maskedFields) {
        this.maskedFields = maskedFields;
    }

    public boolean containsType(TaskType taskType) {
        return collectTasks().stream().anyMatch(t -> t.getType().equals(taskType));
    }

    public WorkflowTask getTaskByRefName(String taskReferenceName) {
        return collectTasks().stream()
            .filter(
                workflowTask ->
                    workflowTask.getTaskReferenceName().equals(taskReferenceName))
            .findFirst()
            .orElse(null);
    }

    public List<WorkflowTask> collectTasks() {
        List<WorkflowTask> tasks = new LinkedList<>();
        for (WorkflowTask workflowTask : this.tasks) {
            tasks.addAll(workflowTask.collectTasks());
        }
        return tasks;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WorkflowDef that = (WorkflowDef) o;
        return version == that.version && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version);
    }

    @Override
    public String toString() {
        return "WorkflowDef{"
            + "name='"
            + name
            + '\''
            + ", description='"
            + description
            + '\''
            + ", version="
            + version
            + ", tasks="
            + tasks
            + ", inputParameters="
            + inputParameters
            + ", outputParameters="
            + outputParameters
            + ", failureWorkflow='"
            + failureWorkflow
            + '\''
            + ", schemaVersion="
            + schemaVersion
            + '\''
            + ", variables="
            + variables
            + ", inputTemplate="
            + inputTemplate
            + '\''
            + ", inputSchema="
            + inputSchema
            + ", outputSchema="
            + outputSchema
            + ", enforceSchema="
            + enforceSchema
            + ", maskedFields="
            + maskedFields
            + '}';
    }
}
