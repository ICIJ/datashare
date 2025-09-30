package org.icij.datashare.asynctasks.constraints;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import org.icij.datashare.asynctasks.model.TaskDef;

import static java.lang.annotation.ElementType.TYPE;


@Documented
@Constraint(validatedBy = TaskTimeoutConstraint.TaskTimeoutValidator.class)
@Target({TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TaskTimeoutConstraint {

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class TaskTimeoutValidator implements ConstraintValidator<TaskTimeoutConstraint, TaskDef> {

        @Override
        public void initialize(TaskTimeoutConstraint constraintAnnotation) {}

        @Override
        public boolean isValid(TaskDef taskDef, ConstraintValidatorContext context) {
            context.disableDefaultConstraintViolation();

            boolean valid = true;

            if (taskDef.getTimeoutS() > 0) {
                if (taskDef.getResponseTimeoutS() > taskDef.getTimeoutS()) {
                    valid = false;
                    String message =
                            String.format(
                                    "TaskDef: %s responseTimeoutSeconds: %d must be less than timeoutSeconds: %d",
                                    taskDef.getName(),
                                    taskDef.getResponseTimeoutS(),
                                    taskDef.getTimeoutS());
                    context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
                }
            }

            return valid;
        }
    }
}
