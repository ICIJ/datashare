package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.db.tables.records.TaskRecord;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep10;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.asynctasks.bus.amqp.Event.MAX_RETRIES_LEFT;
import static org.icij.datashare.db.Tables.TASK;

public class JooqTaskRepository implements TaskRepository {
    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    public JooqTaskRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public int size() {
        return DSL.using(connectionProvider, dialect).selectCount().from(TASK).fetchOne(0, Integer.class);
    }

    @Override
    public boolean containsKey(Object o) {
        DSLContext ctx = DSL.using(connectionProvider, dialect);
        return ctx.fetchExists(
                ctx.selectOne()
                        .from(TASK)
                        .where(TASK.ID.eq((String) o))
        );
    }

    @Override
    public boolean containsValue(Object o) {
        return containsKey(((Task<?>) o).getId());
    }

    @Override
    public Task<?> get(Object o) {
        return createTaskFrom(DSL.using(connectionProvider, dialect).selectFrom(TASK).
                where(TASK.ID.eq((String) o)).fetchOne());
    }

    @Override
    public Task<?> put(String s, Task<?> task) {
        if (task == null || s == null || !s.equals(task.getId())) {
            throw new IllegalArgumentException(String.format("task is null or its id (%s) is different than the key (%s)", ofNullable(task).map(Task::getId).orElse(null), s));
        }
        InsertValuesStep10<TaskRecord, String, String, String, String, String, Double, LocalDateTime, Integer, Integer, String> insertInto = insert();
        insertValues(task, insertInto);
        int inserted = insertInto.execute();
        return inserted == 1 ? task : null;
    }

    @Override
    public Task<?> remove(Object key) {
        return createTaskFrom(DSL.using(connectionProvider, dialect).deleteFrom(TASK).where(TASK.ID.eq((String) key)).returning().fetchOne());
    }

    @Override
    public void putAll(Map<? extends String, ? extends Task<?>> map) {
        ofNullable(map).orElseThrow(() -> new IllegalArgumentException("task(s) map is null"));
        InsertValuesStep10<TaskRecord, String, String, String, String, String, Double, LocalDateTime, Integer, Integer, String> insert = insert();
        map.values().forEach(t -> insertValues(t, insert));
        insert.execute();
    }

    @Override
    public void clear() {
        DSL.using(connectionProvider, dialect).deleteFrom(TASK).execute();
    }

    @Override
    public Set<String> keySet() {
        return DSL.using(connectionProvider, dialect).select(TASK.ID).from(TASK)
                .stream().map(r -> r.field1().getValue(r)).collect(Collectors.toSet());
    }

    @Override
    public Collection<Task<?>> values() {
        return DSL.using(connectionProvider, dialect).selectFrom(TASK).stream()
                .map(this::createTaskFrom).collect(Collectors.toList());
    }

    @Override
    public Set<Entry<String, Task<?>>> entrySet() {
        return DSL.using(connectionProvider, dialect).selectFrom(TASK).stream()
                .map(t -> new AbstractMap.SimpleEntry<String, Task<?>>(t.getId(), createTaskFrom(t)))
                .collect(Collectors.toSet());
    }

    private Task<?> createTaskFrom(TaskRecord taskRecord) {
        return ofNullable(taskRecord).map(r ->
        {
            try {
                return new Task<>(r.getId(), r.getName(), Task.State.valueOf(r.getState()),
                        r.getProgress(), null, new ObjectMapper().readValue(r.getArgs(), new TypeReference<HashMap<String, Object>>() {
                }));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);
    }

    private InsertValuesStep10<TaskRecord, String, String, String, String, String, Double, LocalDateTime, Integer, Integer, String> insert() {
        return DSL.using(connectionProvider, dialect)
                .insertInto(TASK).columns(
                        TASK.ID, TASK.NAME, TASK.STATE, TASK.USER_ID, TASK.GROUP_ID, TASK.PROGRESS,
                        TASK.CREATED_AT, TASK.RETRIES_LEFT, TASK.MAX_RETRIES, TASK.ARGS);
    }

    private static void insertValues(Task<?> t, InsertValuesStep10<TaskRecord, String, String, String, String, String, Double, LocalDateTime, Integer, Integer, String> insert) {
        try {
            insert.values(t.id, t.name,
                    t.getState().name(),
                    ofNullable(t.getUser()).map(u -> u.id).orElse(null),
                    ofNullable(t.getGroup()).map(Group::id).orElse(null),
                    t.getProgress(),
                    new Timestamp(t.createdAt.getTime()).toLocalDateTime(), t.getRetriesLeft(),
                    MAX_RETRIES_LEFT, new ObjectMapper().writeValueAsString(t.args));
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }
}
