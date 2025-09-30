package org.icij.datashare.asynctasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class TaskDefTest {

    private Validator validator;

    @Before
    public void setup() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    public void test() {
        String name = "test1";
        String description = "desc";
        int retryCount = 10;
        int timeout = 100;
        TaskDef def = new TaskDef(name, description, retryCount, timeout);
        assertEquals(36_00, def.getResponseTimeoutS());
        assertEquals(name, def.getName());
        assertEquals(description, def.getDescription());
        assertEquals(retryCount, def.getRetriesLeft());
        assertEquals(timeout, def.getTimeoutS());
    }

    @Test
    public void testTaskDef() {
        TaskDef taskDef = new TaskDef();
        taskDef.setName("task1");
        taskDef.setRetriesLeft(-1);
        taskDef.setTimeoutS(1000);
        taskDef.setResponseTimeoutS(1001);

        Set<ConstraintViolation<Object>> result = validator.validate(taskDef);
        assertEquals(3, result.size());

        List<String> validationErrors = new ArrayList<>();
        result.forEach(e -> validationErrors.add(e.getMessage()));

        assertTrue(
                validationErrors.contains(
                        "TaskDef: task1 responseTimeoutSeconds: 1001 must be less than timeoutSeconds: 1000"));
        assertTrue(validationErrors.contains("TaskDef retryCount: 0 must be >= 0"));
        assertTrue(validationErrors.contains("ownerEmail cannot be empty"));
    }

    @Test
    public void testTaskDefTotalTimeOutSeconds() {
        TaskDef taskDef = new TaskDef();
        taskDef.setName("test-task");
        taskDef.setRetriesLeft(1);
        taskDef.setTimeoutS(1000);
        taskDef.setResponseTimeoutS(1);

        Set<ConstraintViolation<Object>> result = validator.validate(taskDef);
        assertEquals(1, result.size());

        List<String> validationErrors = new ArrayList<>();
        result.forEach(e -> validationErrors.add(e.getMessage()));

        assertTrue(
                validationErrors.toString(),
                validationErrors.contains(
                        "TaskDef: test-task timeoutSeconds: 1000 must be less than or equal to totalTimeoutSeconds: 900"));
    }

    @Test
    public void testTaskDefInvalidEmail() {
        TaskDef taskDef = new TaskDef();
        taskDef.setName("test-task");
        taskDef.setRetriesLeft(1);
        taskDef.setTimeoutS(1000);
        taskDef.setResponseTimeoutS(1);

        Set<ConstraintViolation<Object>> result = validator.validate(taskDef);
        assertEquals(1, result.size());

        List<String> validationErrors = new ArrayList<>();
        result.forEach(e -> validationErrors.add(e.getMessage()));

        assertTrue(
                validationErrors.toString(),
                validationErrors.contains("ownerEmail cannot be empty"));
    }

    @Test
    public void testTaskDefValidEmail() {
        TaskDef taskDef = new TaskDef();
        taskDef.setName("test-task");
        taskDef.setRetriesLeft(1);
        taskDef.setTimeoutS(1000);
        taskDef.setResponseTimeoutS(1);

        Set<ConstraintViolation<Object>> result = validator.validate(taskDef);
        assertEquals(0, result.size());
    }
}
