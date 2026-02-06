package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.db.tables.records.TaskRecord;
import org.icij.datashare.function.Pair;
import org.icij.datashare.json.JsonObjectMapper;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep11;
import org.jooq.SQLDialect;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.asynctasks.bus.amqp.Event.MAX_RETRIES_LEFT;
import static org.icij.datashare.db.Tables.TASK;
import static org.jooq.impl.DSL.selectFrom;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.using;

public class JooqTaskRepository implements TaskRepository {
    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    public JooqTaskRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public Task getTask(String taskId) throws IOException, UnknownTask {
        return Optional.ofNullable(createTaskFrom(DSL.using(connectionProvider, dialect).selectFrom(TASK)
            .where(TASK.ID.eq(taskId)).fetchOne())).orElseThrow(() -> new UnknownTask(taskId));
    }

    @Override
    public <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists {
        using(connectionProvider, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insertInto = insert(inner);
            insertValues(task, group, insertInto);
            try {
                insertInto.execute();
            } catch (IntegrityConstraintViolationException e) {
                String cause = e.getCause().getMessage();
                if (cause.contains("SQLITE_CONSTRAINT_PRIMARYKEY") || cause.contains("task_pkey")) {
                    throw new TaskAlreadyExists(task.id, e);
                }
                throw e;
            }
            return null;
        });
    }

    @Override
    public <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask {
        using(connectionProvider, dialect).transactionResult(configuration -> {
            TaskRecord r = using(configuration)
                .update(TASK)
                .set(TASK.ERROR, JsonObjectMapper.writeValueAsStringTyped(task.getError()))
                .set(TASK.RESULT, JsonObjectMapper.writeValueAsStringTyped(task.getResult()))
                .set(TASK.STATE, task.getState().name())
                .set(TASK.PROGRESS, task.getProgress())
                .set(TASK.COMPLETED_AT, ofNullable(task.getCompletedAt()).map(d -> new Timestamp(d.getTime()).toLocalDateTime()).orElse(null))
                .where(TASK.ID.eq(task.id))
                .returning()
                .fetchOne();
            if (r == null) {
                throw new UnknownTask(task.id);
            }
            return null;
        });
    }


    @Override
    public <V extends Serializable> Task<V> delete(String taskId) throws IOException, UnknownTask {
        Task<V> task = (Task<V>) createTaskFrom(DSL.using(connectionProvider, dialect)
            .deleteFrom(TASK).where(TASK.ID.eq(taskId)).returning().fetchOne()
        );
        if (task == null) {
            throw new UnknownTask(taskId);
        }
        return task;
    }

    @Override
    public void deleteAll() {
        DSL.using(connectionProvider, dialect).deleteFrom(TASK).execute();
    }

    @Override
    public Group getTaskGroup(String taskId) throws UnknownTask {
        String groupId = Optional.ofNullable(
            DSL.using(connectionProvider, dialect)
                .select(TASK.GROUP_ID)
                .from(TASK)
                .where(TASK.ID.eq(taskId))
                .fetchOne()
            )
            .map(r -> r.get(TASK.GROUP_ID))
            .orElseThrow(() -> new UnknownTask(taskId));
        return new Group(TaskGroupType.valueOf(groupId));
    }

    @Override
    public Stream<Task<? extends Serializable>> getTasks(TaskFilters filters) throws IOException {
        if (filters == null) {
            return selectFrom(TASK).stream().map(rethrowFunction(this::createTaskFrom));
        }
        Stream<Task<? extends Serializable>> tasks = selectTasks(DSL.using(connectionProvider, dialect), filters);
        if (filters.getArgs() != null && !filters.getArgs().isEmpty()) {
            tasks = tasks.filter(TaskFilters.empty().withArgs(filters.getArgs())::filter);
        }
        return tasks;
    }

    @Override
    public Stream<String> getTaskIds(TaskFilters filters) throws IOException, UnknownTask {
        if (filters == null) {
            return selectFrom(TASK).stream().map(this::getTaskIdFrom);
        }
        // Special case when we need to filter on args as we need to deserialize them
        if (filters.getArgs() != null) {
            // TODO: test me
            return selectTaskIdsAndArgs(DSL.using(connectionProvider, dialect), filters)
                .filter( p -> TaskFilters.empty().withArgs(filters.getArgs()).filter(p._2()))
                .map(Pair::_1);
        }
        return selectTaskStates(DSL.using(connectionProvider, dialect), filters);
    }

    private Task<?> createTaskFrom(TaskRecord taskRecord) throws IOException {
        return ofNullable(taskRecord).map(rethrowFunction(r ->
        {
            Date createdAt = r.getCreatedAt() == null ? null : Date.from(r.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
            Date completedAt = r.getCompletedAt() == null ? null :Date.from(r.getCompletedAt().atZone(ZoneId.systemDefault()).toInstant());
            Map<String, Object> args = JsonObjectMapper.readValueTyped(r.getArgs(), new TypeReference<>() {});
                TaskResult<?> result = r.getResult() == null ? null: JsonObjectMapper.readValueTyped(r.getResult(), new TypeReference<>() {});
                TaskError error  = r.getError() == null ? null: JsonObjectMapper.readValueTyped(r.getError(), TaskError.class);
                Task<?> task = new Task<>(r.getId(), r.getName(), Task.State.valueOf(r.getState()),
                        r.getProgress(), createdAt, r.getRetriesLeft(), completedAt, args, result, error);
                return task;
        })).orElse(null);
    }

    private String getTaskIdFrom(TaskRecord taskRecord) {
        return ofNullable(taskRecord).map(TaskRecord::getId).orElse(null);
    }

    private Pair<String, Map<String, Object>> createTaskIdsAndArgsFrom(TaskRecord taskRecord) throws IOException {
        return ofNullable(taskRecord)
            .map(rethrowFunction(r -> {
                Map<String, Object> args = JsonObjectMapper.readValueTyped(r.getArgs(), new TypeReference<>() {});
                return new Pair<>(r.getId(), args);
            }))
            .orElse(null);
    }

    private Stream<Task<? extends Serializable>> selectTasks(DSLContext ctx, TaskFilters filters) throws IOException {
        List<Condition> conditions = conditionsFromFilter(filters);
        return ctx.selectFrom(TASK).where(conditions).stream().map(rethrowFunction(this::createTaskFrom));
    }

    private Stream<String> selectTaskStates(DSLContext ctx, TaskFilters filters) {
        List<Condition> conditions = conditionsFromFilter(filters);
        return ctx.selectFrom(TASK).where(conditions).stream().map(this::getTaskIdFrom);
    }

    private Stream<Pair<String, Map<String, Object>>> selectTaskIdsAndArgs(DSLContext ctx, TaskFilters filters) throws IOException  {
        List<Condition> conditions = conditionsFromFilter(filters);
        return ctx.selectFrom(TASK).where(conditions).stream().map(rethrowFunction(this::createTaskIdsAndArgsFrom));
    }

    private static List<Condition> conditionsFromFilter(TaskFilters filters) {
        List<Condition> conditions = new ArrayList<>();
        if (filters.getStates() != null && !filters.getStates().isEmpty()) {
            Condition hasState = filters.getStates().stream()
                    .map(s -> TASK.STATE.eq(s.name()))
                    // Starting with falseCondition() ensures the OR chain only
                    // becomes true if at least one condition matches.
                    .reduce(falseCondition(), Condition::or);

            conditions.add(hasState);
        }
        if (filters.getName() != null) {
            conditions.add(TASK.NAME.likeRegex(filters.getName()));
        }
        if (filters.getUser() != null) {
            conditions.add(TASK.USER_ID.eq(filters.getUser().id));
        }
        return conditions;
    }

    
    private InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insert(DSLContext ctx) {
        return ctx.insertInto(TASK).columns(
                        TASK.ID, TASK.NAME, TASK.STATE, TASK.USER_ID, TASK.GROUP_ID, TASK.PROGRESS,
                        TASK.CREATED_AT, TASK.COMPLETED_AT, TASK.RETRIES_LEFT, TASK.MAX_RETRIES, TASK.ARGS);
    }

    private static void insertValues(Task<?> task, Group group, InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insert) throws JsonProcessingException {
        insert.values(task.id, task.name,
                    task.getState().name(),
                    ofNullable(task.getUser()).map(u -> u.id).orElse(null),
                    ofNullable(group).map(Group::getId).orElse(null),
                    task.getProgress(),
                    new Timestamp(task.createdAt.getTime()).toLocalDateTime(),
                    ofNullable(task.getCompletedAt()).map(d -> new Timestamp(d.getTime()).toLocalDateTime()).orElse(null),
                    task.getRetriesLeft(),
                    MAX_RETRIES_LEFT, JsonObjectMapper.writeValueAsStringTyped(task.args)); // to force writing @type fields in the hashmap
    }

}
