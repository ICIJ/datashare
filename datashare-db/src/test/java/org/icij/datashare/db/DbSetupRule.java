package org.icij.datashare.db;

import com.ninja_squad.dbsetup.DbSetup;
import com.ninja_squad.dbsetup.destination.DataSourceDestination;
import com.ninja_squad.dbsetup.operation.Operation;
import com.ninja_squad.dbsetup.operation.SqlOperation;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearchRepository;
import org.junit.rules.ExternalResource;

import javax.sql.DataSource;
import java.util.HashMap;

import static com.ninja_squad.dbsetup.Operations.deleteAllFrom;
import static com.ninja_squad.dbsetup.Operations.sql;
import static com.ninja_squad.dbsetup.operation.CompositeOperation.sequenceOf;
import static java.util.Optional.ofNullable;

public class DbSetupRule extends ExternalResource {
    final DataSource dataSource;
    private final String dataSourceUrl;
    private static final Operation DELETE_ALL = deleteAllFrom(
            "document", "named_entity", "document_user_star", "document_tag", "batch_search_project", "batch_search", "user_inventory",
            "batch_search_query", "batch_search_result", "project", "note", "document_user_recommendation", "api_key",
            "user_history_project", "user_history_project","user_history");
    private static final SqlOperation RESET_USER_HISTORY_ID_SEQ_POSTGRES = sql("ALTER SEQUENCE user_history_id_seq RESTART WITH 1;");
    private static final SqlOperation RESET_ID_SEQ_SQLITE = sql("DELETE FROM `sqlite_sequence`;");

    DbSetupRule(String dataSourceUrl) {
        this.dataSource = createDatasource(dataSourceUrl);
        this.dataSourceUrl = dataSourceUrl;
    }

    @Override
    protected void before() {
        new RepositoryFactoryImpl().initDatabase(dataSource);
        Operation operation = dataSourceUrl.contains("postgresql") ? sequenceOf(DELETE_ALL, RESET_USER_HISTORY_ID_SEQ_POSTGRES)
                : sequenceOf(DELETE_ALL, RESET_ID_SEQ_SQLITE);
        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();
    }

    JooqBatchSearchRepository createBatchSearchRepository() {
        return new JooqBatchSearchRepository(dataSource, RepositoryFactoryImpl.guessSqlDialectFrom(dataSourceUrl));
    }

    JooqRepository createRepository() {
        return new JooqRepository(dataSource, RepositoryFactoryImpl.guessSqlDialectFrom(dataSourceUrl));
    }

    public JooqApiKeyRepository createApiKeyRepository() {
        return new JooqApiKeyRepository(dataSource, RepositoryFactoryImpl.guessSqlDialectFrom(dataSourceUrl));
    }

    public JooqTaskRepository createTaskRepository() {
        return new JooqTaskRepository(dataSource, RepositoryFactoryImpl.guessSqlDialectFrom(dataSourceUrl));
    }

    private static DataSource createDatasource(final String jdbcUrl) {
        return new RepositoryFactoryImpl(new PropertiesProvider(new HashMap<>() {{
            put("dataSourceUrl", ofNullable(jdbcUrl).orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared"));
        }})).createDatasource();
    }
}
