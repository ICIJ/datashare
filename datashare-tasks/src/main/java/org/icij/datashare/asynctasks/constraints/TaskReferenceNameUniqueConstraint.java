package org.icij.datashare.asynctasks.constraints;

import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.icij.datashare.asynctasks.model.WorkflowDef;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import org.icij.datashare.asynctasks.model.WorkflowTask;
import org.icij.datashare.asynctasks.utils.ConstraintParamUtil;

@Documented
@Constraint(validatedBy = TaskReferenceNameUniqueConstraint.TaskReferenceNameUniqueValidator.class)
@Target({TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TaskReferenceNameUniqueConstraint {

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class TaskReferenceNameUniqueValidator
            implements ConstraintValidator<TaskReferenceNameUniqueConstraint, WorkflowDef> {

        @Override
        public void initialize(TaskReferenceNameUniqueConstraint constraintAnnotation) {}

        @Override
        public boolean isValid(WorkflowDef workflowDef, ConstraintValidatorContext context) {
            context.disableDefaultConstraintViolation();

            boolean valid = true;

            // check if taskReferenceNames are unique across tasks or not
            HashMap<String, Integer> taskReferenceMap = new HashMap<>();
            for (WorkflowTask workflowTask : workflowDef.collectTasks()) {
                if (taskReferenceMap.containsKey(workflowTask.getTaskReferenceName())) {
                    String message =
                            String.format(
                                    "taskReferenceName: %s should be unique across tasks for a given workflowDefinition: %s",
                                    workflowTask.getTaskReferenceName(), workflowDef.getName());
                    context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
                    valid = false;
                } else {
                    taskReferenceMap.put(workflowTask.getTaskReferenceName(), 1);
                }
            }
            // check inputParameters points to valid taskDef
            return valid & verifyTaskInputParameters(context, workflowDef);
        }

        private boolean verifyTaskInputParameters(
                ConstraintValidatorContext context, WorkflowDef workflow) {
            MutableBoolean valid = new MutableBoolean();
            valid.setValue(true);

            if (workflow.getTasks() == null) {
                return valid.getValue();
            }

            workflow.getTasks().stream()
                    .filter(workflowTask -> workflowTask.getInputParameters() != null)
                    .forEach(
                            workflowTask -> {
                                List<String> errors =
                                        ConstraintParamUtil.validateInputParam(
                                                workflowTask.getInputParameters(),
                                                workflowTask.getName(),
                                                workflow);
                                errors.forEach(
                                        message ->
                                                context.buildConstraintViolationWithTemplate(
                                                                message)
                                                        .addConstraintViolation());
                                if (!errors.isEmpty()) {
                                    valid.setValue(false);
                                }
                            });

            return valid.getValue();
        }
    }
}
