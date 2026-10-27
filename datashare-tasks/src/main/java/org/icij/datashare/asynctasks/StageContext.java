package org.icij.datashare.asynctasks;

import java.util.List;
import java.util.Map;
import org.icij.datashare.text.Project;

public interface StageContext<O> {
    Project project();

    Map<String, Object> options();

    boolean isCancelled();

    void emit(List<O> out);
}
