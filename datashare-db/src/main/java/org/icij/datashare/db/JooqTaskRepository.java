package org.icij.datashare.db;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.jooq.SQLDialect;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return null;
    }

    @Override
    public Task<?> put(String s, Task<?> task) {
        return null;
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
}
