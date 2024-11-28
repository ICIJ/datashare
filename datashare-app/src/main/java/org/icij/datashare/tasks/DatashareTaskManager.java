package org.icij.datashare.tasks;

import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.user.User;

public interface DatashareTaskManager extends TaskManager {
    // TODO: can we do better using generics instead of casts ?
    default String startTask(Class<?> taskClass, User user, Map<String, Object> properties) throws IOException {
        return startTask(DatashareTask.task(taskClass.getName(), user, properties), new Group(taskClass.getAnnotation(
           TaskGroup.class).value()));
    }

    default String startTask(Class<?> taskClass, User user, Group group, Map<String, Object> properties) throws IOException {
        return startTask(DatashareTask.task(taskClass.getName(), user, properties), group);
    }

    default  String startTask(String id, Class<?> taskClass, User user) throws IOException {
        return startTask(DatashareTask.task(id, taskClass.getName(), user), new Group(taskClass.getAnnotation(TaskGroup.class).value()));
    }

    default List<Task<?>> getTasks(Stream<Task<?>> stream, User user, Pattern pattern) {
        return getTasks(stream, pattern).stream().map( t -> ( Task<?> ) t)
            .filter(t -> user.equals(DatashareTask.getUser(t))).collect(Collectors.toList());
    }

    default List<Task<?>> getTasks(User user, Pattern pattern) throws IOException {
        return getTasks(this.getTasks().stream(), user, pattern);
    }

    default Map<String, Boolean> stopAllTasks(User user) throws IOException {
        return getTasks().stream()
            .filter(t -> user.equals(DatashareTask.getUser(t)))
            .filter(t -> t.getState() == Task.State.RUNNING || t.getState() == Task.State.QUEUED).collect(
                        toMap(t -> t.id, t -> {
                            try {
                                return stopTask(t.id);
                            } catch (IOException e) {
                                logger.error("cannot stop task {}", t.id, e);
                                return false;
                            }
                        }));
    }
}
