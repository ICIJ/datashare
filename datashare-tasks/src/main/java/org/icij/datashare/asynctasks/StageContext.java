package org.icij.datashare.asynctasks;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.icij.datashare.text.Project;

public interface StageContext<O> extends AutoCloseable {
    String TASK_ID_OPT = "taskId";

    Project project();

    Map<String, Object> options();

    boolean isCancelled();

    void emit(List<O> out);

    <R> R resource(Class<R> type, Supplier<R> factory);

    @Override
    void close();
}
