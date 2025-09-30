package org.icij.datashare.asynctasks.utils;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import org.icij.datashare.asynctasks.model.WorkflowDef;
import org.icij.datashare.asynctasks.model.WorkflowTask;

@SuppressWarnings("unchecked")
public class ConstraintParamUtil {

    public static final Pattern PATH_COMPONENT_PATTERN = Pattern.compile("(?=(?<!\\$)\\$\\{)|(?<=\\})");

    public static List<String> validateInputParam(
            Map<String, Object> input, String taskName, WorkflowDef workflow) {
        ArrayList<String> errorList = new ArrayList<>();

        for (Entry<String, Object> e : input.entrySet()) {
            Object value = e.getValue();
            if (value instanceof String) {
                errorList.addAll(
                        extractParamPathComponentsFromString(
                                e.getKey(), value.toString(), taskName, workflow));
            } else if (value instanceof Map) {
                // recursive call
                errorList.addAll(
                        validateInputParam((Map<String, Object>) value, taskName, workflow));
            } else if (value instanceof List) {
                errorList.addAll(
                        extractListInputParam(e.getKey(), (List<?>) value, taskName, workflow));
            } else {
                e.setValue(value);
            }
        }
        return errorList;
    }

    private static List<String> extractListInputParam(
            String key, List<?> values, String taskName, WorkflowDef workflow) {
        ArrayList<String> errorList = new ArrayList<>();
        for (Object listVal : values) {
            if (listVal instanceof String) {
                errorList.addAll(
                        extractParamPathComponentsFromString(
                                key, listVal.toString(), taskName, workflow));
            } else if (listVal instanceof Map) {
                errorList.addAll(
                        validateInputParam((Map<String, Object>) listVal, taskName, workflow));
            } else if (listVal instanceof List) {
                errorList.addAll(extractListInputParam(key, (List<?>) listVal, taskName, workflow));
            }
        }
        return errorList;
    }

    private static List<String> extractParamPathComponentsFromString(
            String key, String value, String taskName, WorkflowDef workflow) {
        ArrayList<String> errorList = new ArrayList<>();

        if (value == null) {
            String message = String.format("key: %s input parameter value: is null", key);
            errorList.add(message);
            return errorList;
        }

        String[] values = PATH_COMPONENT_PATTERN.split(value);

        for (String s : values) {
            if (s.startsWith("${") && s.endsWith("}")) {
                String paramPath = s.substring(2, s.length() - 1);
                if (!isValidPath(paramPath)) {
                    String message =
                            String.format(
                                    "key: %s input parameter value: %s is not valid",
                                    key, paramPath);
                    errorList.add(message);
                } // workflow, or task reference name
                else {
                    String[] components = paramPath.split("\\.");
                    if (!"workflow".equals(components[0])) {
                        WorkflowTask task = workflow.getTaskByRefName(components[0]);
                        if (task == null) {
                            String message =
                                    String.format(
                                            "taskReferenceName: %s for given task: %s input value: %s of input"
                                                    + " parameter: %s"
                                                    + " is not defined in workflow definition.",
                                            components[0], taskName, key, value);
                            errorList.add(message);
                        }
                    }
                }
            }
        }
        return errorList;
    }

    public static boolean isValidPath(String path) {
        try {
            JsonPath.compile(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
