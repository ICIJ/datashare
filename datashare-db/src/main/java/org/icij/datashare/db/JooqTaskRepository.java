package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.db.tables.records.TaskRecord;
import org.icij.datashare.json.JsonObjectMapper;
import org.jooq.DSLContext;
import org.jooq.InsertOnDuplicateSetMoreStep;
import org.jooq.InsertValuesStep11;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.asynctasks.bus.amqp.Event.MAX_RETRIES_LEFT;
import static org.icij.datashare.db.Tables.TASK;
import static org.jooq.impl.DSL.using;

public class JooqTaskRepository implements TaskRepository {
    public static final ObjectMapper TYPE_INCLUSION_MAPPER = JsonObjectMapper.createTypeInclusionMapper();
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
        return using(connectionProvider, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            Task<?> old = createTaskFrom(inner.selectFrom(TASK).where(TASK.ID.eq(task.getId())).fetchOne());
            if (old != null && old.equals(task)) return null; // no need to go further

            InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insertInto = insert(inner);
            insertValues(task, insertInto);
            InsertOnDuplicateSetMoreStep<TaskRecord> onDuplicate = insertInto.onDuplicateKeyUpdate()
                    .set(TASK.ERROR, TYPE_INCLUSION_MAPPER.writeValueAsString(task.getError()))
                    .set(TASK.RESULT, TYPE_INCLUSION_MAPPER.writeValueAsString(task.getResult()))
                    .set(TASK.STATE, task.getState().name())
                    .set(TASK.PROGRESS, task.getProgress())
                    .set(TASK.COMPLETED_AT, ofNullable(task.getCompletedAt()).map(d -> new Timestamp(d.getTime()).toLocalDateTime()).orElse(null));

            onDuplicate.execute();
            return old;
        });
    }

    @Override
    public Task<?> remove(Object key) {
        return createTaskFrom(DSL.using(connectionProvider, dialect).deleteFrom(TASK).where(TASK.ID.eq((String) key)).returning().fetchOne());
    }

    @Override
    public void putAll(Map<? extends String, ? extends Task<?>> map) {
        ofNullable(map).orElseThrow(() -> new IllegalArgumentException("task(s) map is null"));
        InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insert = insert(using(connectionProvider, dialect));
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
            Date createdAt = r.getCreatedAt() == null ? null : Date.from(r.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());;
            Date completedAt = r.getCompletedAt() == null ? null :Date.from(r.getCompletedAt().atZone(ZoneId.systemDefault()).toInstant());;
            try {
                Map<String, Object> args = TYPE_INCLUSION_MAPPER.readValue(r.getArgs(), new TypeReference<>() {});
                Object result = r.getResult() == null ? null: TYPE_INCLUSION_MAPPER.readValue(r.getResult(), new TypeReference<>() {});
                TaskError error  = r.getError() == null ? null: TYPE_INCLUSION_MAPPER.readValue(r.getError(), TaskError.class);
                return new Task<>(r.getId(), r.getName(), Task.State.valueOf(r.getState()),
                        r.getProgress(), createdAt, r.getRetriesLeft(), completedAt, args, result, error);

            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);
    }

    private InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insert(DSLContext ctx) {
        return ctx.insertInto(TASK).columns(
                        TASK.ID, TASK.NAME, TASK.STATE, TASK.USER_ID, TASK.GROUP_ID, TASK.PROGRESS,
                        TASK.CREATED_AT, TASK.COMPLETED_AT, TASK.RETRIES_LEFT, TASK.MAX_RETRIES, TASK.ARGS);
    }

    private static void insertValues(Task<?> t, InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insert) {
        try {
            insert.values(t.id, t.name,
                    t.getState().name(),
                    ofNullable(t.getUser()).map(u -> u.id).orElse(null),
                    ofNullable(t.getGroup()).map(Group::getId).orElse(null),
                    t.getProgress(),
                    new Timestamp(t.createdAt.getTime()).toLocalDateTime(),
                    ofNullable(t.getCompletedAt()).map(d -> new Timestamp(d.getTime()).toLocalDateTime()).orElse(null),
                    t.getRetriesLeft(),
                    MAX_RETRIES_LEFT, TYPE_INCLUSION_MAPPER.writeValueAsString(t.args)); // to force writing @type fields in the hashmap
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }
}
