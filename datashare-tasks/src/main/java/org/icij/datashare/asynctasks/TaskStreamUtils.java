package org.icij.datashare.asynctasks;

import static org.icij.datashare.text.StringUtils.getValue;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.icij.datashare.json.JsonObjectMapper;

public class TaskStreamUtils {
    public static Stream<Task<?>> getFilteredTaskStream(Map<String, Pattern> filters, Stream<Task<?>> taskStream) {
        for (Map.Entry<String, Pattern> filter : filters.entrySet()) {
            taskStream = taskStream.filter(task -> {
                Map<String, Object> objectMap = JsonObjectMapper.getJson(task);
                return filter.getValue().matcher(String.valueOf(getValue(objectMap, filter.getKey()))).matches();
            });
        }
        return taskStream;
    }
}
