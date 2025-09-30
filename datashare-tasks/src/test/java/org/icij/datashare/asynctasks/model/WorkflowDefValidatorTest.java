package org.icij.datashare.asynctasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class WorkflowDefValidatorTest {

    @Before
    public void before() {
        System.setProperty("TEST_ENV", "test");
    }

    @Test
    public void testWorkflowDefConstraints() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(2);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(3, result.size());

        List<String> validationErrors = new ArrayList<>();
        result.forEach(e -> validationErrors.add(e.getMessage()));

        assertTrue(validationErrors.contains("WorkflowDef name cannot be null or empty"));
        assertTrue(validationErrors.contains("WorkflowTask list cannot be empty"));
        assertTrue(validationErrors.contains("ownerEmail cannot be empty"));
        // assertTrue(validationErrors.contains("workflowDef schemaVersion: 1 should be >= 2"));
    }

    @Test
    public void testWorkflowDefConstraintsWithMultipleEnvVariable() {
        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setSchemaVersion(2);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask_1 = new WorkflowTask();
        workflowTask_1.setName("task_1");
        workflowTask_1.setTaskReferenceName("task_1");
        workflowTask_1.setType(TaskType.NOOP);

        Map<String, Object> inputParam = new HashMap<>();
        inputParam.put("taskId", "${CPEWF_TASK_ID}");
        inputParam.put(
                "entryPoint",
                "${NETFLIX_ENVIRONMENT} ${NETFLIX_STACK} ${CPEWF_TASK_ID} ${workflow.input.status}");

        workflowTask_1.setInputParameters(inputParam);

        WorkflowTask workflowTask_2 = new WorkflowTask();
        workflowTask_2.setName("task_2");
        workflowTask_2.setTaskReferenceName("task_2");
        workflowTask_2.setType(TaskType.NOOP);

        Map<String, Object> inputParam2 = new HashMap<>();
        inputParam2.put("env", inputParam);

        workflowTask_2.setInputParameters(inputParam2);

        List<WorkflowTask> tasks = new ArrayList<>();
        tasks.add(workflowTask_1);
        tasks.add(workflowTask_2);

        workflowDef.setTasks(tasks);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }

    @Test
    public void testWorkflowDefConstraintsSingleEnvVariable() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(2);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask_1 = new WorkflowTask();
        workflowTask_1.setName("task_1");
        workflowTask_1.setTaskReferenceName("task_1");
        workflowTask_1.setType(TaskType.NOOP);

        Map<String, Object> inputParam = new HashMap<>();
        inputParam.put("taskId", "${CPEWF_TASK_ID}");

        workflowTask_1.setInputParameters(inputParam);

        List<WorkflowTask> tasks = new ArrayList<>();
        tasks.add(workflowTask_1);

        workflowDef.setTasks(tasks);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }

    @Test
    public void testWorkflowDefConstraintsDualEnvVariable() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(2);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask_1 = new WorkflowTask();
        workflowTask_1.setName("task_1");
        workflowTask_1.setTaskReferenceName("task_1");
        workflowTask_1.setType(TaskType.NOOP);

        Map<String, Object> inputParam = new HashMap<>();
        inputParam.put("taskId", "${CPEWF_TASK_ID} ${NETFLIX_STACK}");

        workflowTask_1.setInputParameters(inputParam);

        List<WorkflowTask> tasks = new ArrayList<>();
        tasks.add(workflowTask_1);

        workflowDef.setTasks(tasks);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }

    @Test
    public void testWorkflowDefConstraintsWithMapAsInputParam() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(2);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask_1 = new WorkflowTask();
        workflowTask_1.setName("task_1");
        workflowTask_1.setTaskReferenceName("task_1");
        workflowTask_1.setType(TaskType.NOOP);

        Map<String, Object> inputParam = new HashMap<>();
        inputParam.put("taskId", "${CPEWF_TASK_ID} ${NETFLIX_STACK}");
        Map<String, Object> envInputParam = new HashMap<>();
        envInputParam.put("packageId", "${workflow.input.packageId}");
        envInputParam.put("taskId", "${CPEWF_TASK_ID}");
        envInputParam.put("NETFLIX_STACK", "${NETFLIX_STACK}");
        envInputParam.put("NETFLIX_ENVIRONMENT", "${NETFLIX_ENVIRONMENT}");

        inputParam.put("env", envInputParam);

        workflowTask_1.setInputParameters(inputParam);

        List<WorkflowTask> tasks = new ArrayList<>();
        tasks.add(workflowTask_1);

        workflowDef.setTasks(tasks);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }

    @Test
    public void testWorkflowTaskInputParamInvalid() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(2);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask = new WorkflowTask(); // name is null
        workflowTask.setName("t1");
        workflowTask.setWorkflowTaskType(TaskType.NOOP);
        workflowTask.setTaskReferenceName("t1");

        Map<String, Object> map = new HashMap<>();
        map.put("blabla", "${workflow.input.Space Value}");
        workflowTask.setInputParameters(map);

        workflowDef.getTasks().add(workflowTask);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(1, result.size());

        List<String> validationErrors = new ArrayList<>();
        result.forEach(e -> validationErrors.add(e.getMessage()));

        assertTrue(
                validationErrors.contains(
                        "key: blabla input parameter value: workflow.input.Space Value is not valid"));
    }

    @Test
    public void testWorkflowTaskEmptyStringInputParamValue() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(2);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask = new WorkflowTask(); // name is null

        workflowTask.setName("t1");
        workflowTask.setWorkflowTaskType(TaskType.NOOP);
        workflowTask.setTaskReferenceName("t1");

        Map<String, Object> map = new HashMap<>();
        map.put("blabla", "");
        workflowTask.setInputParameters(map);

        workflowDef.getTasks().add(workflowTask);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }

    @Test
    public void testWorkflowTasklistInputParamWithEmptyString() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(2);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask = new WorkflowTask(); // name is null

        workflowTask.setName("t1");
        workflowTask.setWorkflowTaskType(TaskType.NOOP);
        workflowTask.setTaskReferenceName("t1");

        Map<String, Object> map = new HashMap<>();
        map.put("blabla", "");
        map.put("foo", new String[] {""});
        workflowTask.setInputParameters(map);

        workflowDef.getTasks().add(workflowTask);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }

    @Test
    public void testWorkflowSchemaVersion1() {
        WorkflowDef workflowDef = new WorkflowDef(); // name is null
        workflowDef.setSchemaVersion(3);
        workflowDef.setName("test_env");

        WorkflowTask workflowTask = new WorkflowTask();

        workflowTask.setName("t1");
        workflowTask.setWorkflowTaskType(TaskType.NOOP);
        workflowTask.setTaskReferenceName("t1");

        Map<String, Object> map = new HashMap<>();
        map.put("blabla", "");
        workflowTask.setInputParameters(map);

        workflowDef.getTasks().add(workflowTask);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(1, result.size());

        List<String> validationErrors = new ArrayList<>();
        result.forEach(e -> validationErrors.add(e.getMessage()));

        assertTrue(validationErrors.contains("workflowDef schemaVersion: 2 is only supported"));
    }

    @Test
    public void testWorkflowOwnerInvalidEmail() {
        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setName("test_env");

        WorkflowTask workflowTask = new WorkflowTask();

        workflowTask.setName("t1");
        workflowTask.setWorkflowTaskType(TaskType.NOOP);
        workflowTask.setTaskReferenceName("t1");

        Map<String, Object> map = new HashMap<>();
        map.put("blabla", "");
        workflowTask.setInputParameters(map);

        workflowDef.getTasks().add(workflowTask);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }

    @Test
    public void testWorkflowOwnerValidEmail() {
        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setName("test_env");

        WorkflowTask workflowTask = new WorkflowTask();

        workflowTask.setName("t1");
        workflowTask.setWorkflowTaskType(TaskType.NOOP);
        workflowTask.setTaskReferenceName("t1");

        Map<String, Object> map = new HashMap<>();
        map.put("blabla", "");
        workflowTask.setInputParameters(map);

        workflowDef.getTasks().add(workflowTask);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(workflowDef);
        assertEquals(0, result.size());
    }
}
