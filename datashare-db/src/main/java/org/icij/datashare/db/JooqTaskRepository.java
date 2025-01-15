package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.db.tables.records.TaskRecord;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.asynctasks.bus.amqp.Event.MAX_RETRIES_LEFT;
import static org.icij.datashare.db.Tables.TASK;

public class JooqTaskRepository implements TaskRepository {
    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    JooqTaskRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean containsKey(Object o) {
        return false;
    }

    @Override
    public boolean containsValue(Object o) {
        return false;
    }

    @Override
    public Task<?> get(Object o) {
        return createTaskFrom(DSL.using(connectionProvider, dialect).selectFrom(TASK).
                where(TASK.ID.eq((String) o)).fetchOne());
    }

    @Override
    public Task<?> put(String s, Task<?> task) {
        try {
            if (task == null || s == null || !s.equals(task.getId())) {
                throw new IllegalArgumentException(String.format("task is null or its id (%s) is different than the key (%s)", ofNullable(task).map(Task::getId).orElse(null), s));
            }
            final int inserted = DSL.using(connectionProvider, dialect)
                    .insertInto(TASK).columns(
                            TASK.ID, TASK.NAME, TASK.STATE, TASK.USER_ID, TASK.GROUP_ID, TASK.PROGRESS,
                            TASK.CREATED_AT, TASK.RETRIES_LEFT, TASK.MAX_RETRIES, TASK.ARGS)
                    .values(task.id, task.name,
                            task.getState().name(),
                            ofNullable(task.getUser()).map(u -> u.id).orElse(null),
                            ofNullable(task.getGroup()).map(Group::id).orElse(null),
                            task.getProgress(),
                            new Timestamp(task.createdAt.getTime()).toLocalDateTime(), task.getRetriesLeft(),
                            MAX_RETRIES_LEFT, new ObjectMapper().writeValueAsString(task.args)).execute();
            return inserted == 1 ? task : null;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Task<?> remove(Object o) {
        return null;
    }

    @Override
    public void putAll(Map<? extends String, ? extends Task<?>> map) {

    }

    @Override
    public void clear() {

    }

    @Override
    public Set<String> keySet() {
        return Set.of();
    }

    @Override
    public Collection<Task<?>> values() {
        return List.of();
    }

    @Override
    public Set<Entry<String, Task<?>>> entrySet() {
        return Set.of();
    }

    private Task<?> createTaskFrom(TaskRecord taskRecord) {
        return ofNullable(taskRecord).map(r ->
        {
            try {
                return new Task<>(r.getId(), r.getName(), Task.State.valueOf(r.getState()),
                        r.getProgress(), null, new ObjectMapper().readValue(r.getArgs(), new TypeReference<HashMap<String, Object>>(){}));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);
    }
}
