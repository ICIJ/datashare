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
import static org.icij.datashare.asynctasks.bus.amqp.Event.MAX_RETRIES_LEFT;
import static org.icij.datashare.db.Tables.TASK;
import static org.jooq.impl.DSL.selectFrom;
import static org.jooq.impl.DSL.trueCondition;
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
                .set(TASK.ERROR, TYPE_INCLUSION_MAPPER.writeValueAsString(task.getError()))
                .set(TASK.RESULT, TYPE_INCLUSION_MAPPER.writeValueAsString(task.getResult()))
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
    public Stream<Task<? extends Serializable>> getTasks(TaskFilters filters) {
        if (filters == null) {
            return selectFrom(TASK).stream().map(this::createTaskFrom);
        }
        Stream<Task<? extends Serializable>> tasks = selectTasks(DSL.using(connectionProvider, dialect), filters);
        if (filters.getArgs() != null && !filters.getArgs().isEmpty()) {
            tasks = tasks.filter(TaskFilters.empty().withArgs(filters.getArgs())::filter);
        }
        return tasks;
    }

    private Task<?> createTaskFrom(TaskRecord taskRecord) {
        return ofNullable(taskRecord).map(r ->
        {
            Date createdAt = r.getCreatedAt() == null ? null : Date.from(r.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
            Date completedAt = r.getCompletedAt() == null ? null :Date.from(r.getCompletedAt().atZone(ZoneId.systemDefault()).toInstant());
            try {
                Map<String, Object> args = TYPE_INCLUSION_MAPPER.readValue(r.getArgs(), new TypeReference<>() {});
                TaskResult<?> result = r.getResult() == null ? null: TYPE_INCLUSION_MAPPER.readValue(r.getResult(), new TypeReference<>() {});
                TaskError error  = r.getError() == null ? null: TYPE_INCLUSION_MAPPER.readValue(r.getError(), TaskError.class);
                Task<?> task = new Task<>(r.getId(), r.getName(), Task.State.valueOf(r.getState()),
                        r.getProgress(), createdAt, r.getRetriesLeft(), completedAt, args, result, error);
                return task;
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);
    }

    private Stream<Task<? extends Serializable>> selectTasks(DSLContext ctx, TaskFilters filters) {
        List<Condition> conditions = new ArrayList<>();
        if (filters.getStates() != null && !filters.getStates().isEmpty()) {
            Condition hasState = trueCondition();
            for (Task.State s : filters.getStates()) {
                hasState.and(TASK.STATE.eq(s.name()));
            }
            conditions.add(hasState);
        }
        if (filters.getName() != null) {
            conditions.add(TASK.NAME.likeRegex(filters.getName()));
        }
        if (filters.getUser() != null) {
            conditions.add(TASK.USER_ID.eq(filters.getUser().id));
        }
        return ctx.selectFrom(TASK).where(conditions).stream().map(this::createTaskFrom);
    }


    private InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insert(DSLContext ctx) {
        return ctx.insertInto(TASK).columns(
                        TASK.ID, TASK.NAME, TASK.STATE, TASK.USER_ID, TASK.GROUP_ID, TASK.PROGRESS,
                        TASK.CREATED_AT, TASK.COMPLETED_AT, TASK.RETRIES_LEFT, TASK.MAX_RETRIES, TASK.ARGS);
    }

    private static void insertValues(Task<?> task, Group group, InsertValuesStep11<TaskRecord, String, String, String, String, String, Double, LocalDateTime, LocalDateTime, Integer, Integer, String> insert) {
        try {
            insert.values(task.id, task.name,
                    task.getState().name(),
                    ofNullable(task.getUser()).map(u -> u.id).orElse(null),
                    ofNullable(group).map(Group::getId).orElse(null),
                    task.getProgress(),
                    new Timestamp(task.createdAt.getTime()).toLocalDateTime(),
                    ofNullable(task.getCompletedAt()).map(d -> new Timestamp(d.getTime()).toLocalDateTime()).orElse(null),
                    task.getRetriesLeft(),
                    MAX_RETRIES_LEFT, TYPE_INCLUSION_MAPPER.writeValueAsString(task.args)); // to force writing @type fields in the hashmap
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }

}
