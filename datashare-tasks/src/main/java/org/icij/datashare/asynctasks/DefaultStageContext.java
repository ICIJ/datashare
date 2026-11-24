package org.icij.datashare.asynctasks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.icij.datashare.text.Project;

public class DefaultStageContext<O> extends ScopedStageContext<O> {
    private final String taskId;
    private final Project project;
    private final Map<String, Object> options;
    private final Consumer<List<O>> emitter;

    public DefaultStageContext(String taskId, Project project, Map<String, Object> options, Consumer<List<O>> emitter) {
        this.taskId = Objects.requireNonNull(taskId, "a stage context needs the id the run is tracked under");
        this.project = Objects.requireNonNull(project);
        this.options = Objects.requireNonNull(options);
        this.emitter = Objects.requireNonNull(emitter);
    }

    @Override
    public String taskId() {return taskId;}

    @Override
    public Project project() {return project;}

    @Override
    public Map<String, Object> options() {return options;}

    @Override
    public boolean isCancelled() {return Thread.currentThread().isInterrupted();}

    @Override
    public void emit(List<O> out) {emitter.accept(out);}
}
