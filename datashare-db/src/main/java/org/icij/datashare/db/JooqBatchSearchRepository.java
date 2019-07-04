package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.user.User;
import org.jooq.ConnectionProvider;
import org.jooq.SQLDialect;

import java.util.List;

public class JooqBatchSearchRepository implements BatchSearchRepository {
    private final ConnectionProvider connectionProvider;
    private final SQLDialect dialect;

    public JooqBatchSearchRepository(final ConnectionProvider connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public boolean save(User user, BatchSearch batchSearch) {
        return false;
    }

    @Override
    public List<BatchSearch> get(User user) {
        return null;
    }
}
