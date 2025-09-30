package org.icij.datashare.asynctasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class WorkflowTaskTest {

    @Test
    public void test() {
        WorkflowTask workflowTask = new WorkflowTask();
        workflowTask.setWorkflowTaskType(TaskType.NOOP);

        assertNotNull(workflowTask.getType());
        assertEquals(TaskType.NOOP, workflowTask.getType());
    }

    @Test
    public void testOptional() {
        WorkflowTask task = new WorkflowTask();
        assertFalse(task.isOptional());

        task.setOptional(Boolean.FALSE);
        assertFalse(task.isOptional());

        task.setOptional(Boolean.TRUE);
        assertTrue(task.isOptional());
    }

    @Test
    public void testWorkflowTaskName() {
        WorkflowTask taskDef = new WorkflowTask(); // name is null
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<Object>> result = validator.validate(taskDef);
        assertEquals(2, result.size());

        List<String> validationErrors = new ArrayList<>();
        result.forEach(e -> validationErrors.add(e.getMessage()));

        assertTrue(validationErrors.contains("WorkflowTask name cannot be empty or null"));
        assertTrue(
                validationErrors.contains(
                        "WorkflowTask taskReferenceName name cannot be empty or null"));
    }
}
