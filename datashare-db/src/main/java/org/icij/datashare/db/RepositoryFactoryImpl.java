package org.icij.datashare.db;

import com.zaxxer.hikari.HikariConfig;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.ui.LoggerUIService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.RepositoryFactory;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.UserPolicyRepository;
import org.jooq.SQLDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.BiFunction;

public class RepositoryFactoryImpl implements RepositoryFactory {
    private final PropertiesProvider propertiesProvider;
    private final DataSource dataSource;

    RepositoryFactoryImpl() {
        this(new PropertiesProvider());
    }

    public RepositoryFactoryImpl(final PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
        System.getProperties().setProperty("org.jooq.no-logo", "true");
        System.getProperties().setProperty("org.jooq.no-tips", "true");
        System.getProperties().setProperty("org.jooq.log.org.jooq.impl.DefaultExecuteContext.logVersionSupport", "ERROR");
        this.dataSource = createDatasource();
    }

    @Override
    public Repository createRepository() {
        return createRepository(JooqRepository::new);
    }
    @Override
    public ApiKeyRepository createApiKeyRepository() {
        return createRepository(JooqApiKeyRepository::new);
    }

    @Override
    public BatchSearchRepository createBatchSearchRepository() {
        return createRepository(JooqBatchSearchRepository::new);
    }

    @Override
    public UserPolicyRepository createUserPolicyRepository() {
        return createRepository(JooqUserPolicyRepository::new);
    }

    void initDatabase(final DataSource dataSource) {
        System.setProperty("liquibase.command.showSummaryOutput", "LOG"); // avoid double log
        try (Connection connection = dataSource.getConnection()) {
            // Normalize Liquibase changelog paths to ensure idempotence across execution contexts.
            //
            // Liquibase records the file path of each applied changeset in the `databasechangelog` table.
            // When migrations run from different contexts (Maven plugin vs Java code), the recorded paths
            // can differ (e.g., "classpath:liquibase/..." vs "liquibase/..."). This causes Liquibase to
            // treat already-applied changesets as "new" and attempt to re-apply them, failing with errors
            // like "relation already exists".
            //
            // By normalizing the FILENAME column to strip prefixes like "classpath:", we ensure Liquibase
            // correctly identifies previously-applied changesets regardless of how they were originally run.
            normalizeChangelogPaths(connection);
            try (Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection))) {
                CommandScope updateCommand = new CommandScope(UpdateCommandStep.COMMAND_NAME);
                updateCommand.addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "liquibase/changelog/db.changelog.yml");
                Scope.enter(Map.of(Scope.Attr.ui.name(), new NullUIService()));
                updateCommand.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, db);
                updateCommand.execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void normalizeChangelogPaths(Connection connection) {
        try (var stmt = connection.createStatement()) {
            // Check if databasechangelog table exists before attempting to update
            var rs = connection.getMetaData().getTables(null, null, "databasechangelog", new String[]{"TABLE"});
            if (rs.next()) {
                // Remove "classpath:" prefix from FILENAME to match Java code's path format
                stmt.execute("UPDATE databasechangelog SET filename = REPLACE(filename, 'classpath:', '') WHERE filename LIKE 'classpath:%'");
            }
        } catch (SQLException e) {
            // Table might not exist yet on fresh database - that's fine
        }
    }

    public void initDatabase() {
        initDatabase(dataSource);
    }

    private <T> T createRepository(BiFunction<DataSource, SQLDialect, T> constructor) {
        return constructor.apply(dataSource, guessSqlDialectFrom(getDataSourceUrl()));
    }

    public static SQLDialect guessSqlDialectFrom(String dataSourceUrl) {
        for (SQLDialect dialect: SQLDialect.values()) {
            if (dataSourceUrl.contains(dialect.name().toLowerCase())) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("unknown SQL dialect for datasource : " + dataSourceUrl);
    }

    public DataSource getDataSource() {return dataSource;}
    public SQLDialect guessSqlDialect() {return guessSqlDialectFrom(getDataSourceUrl());}

    DataSource createDatasource() {
        HikariConfig config = new HikariConfig();
        String dataSourceUrl = getDataSourceUrl();
        config.setJdbcUrl(dataSourceUrl);
        if (dataSourceUrl.contains("sqlite")) {
            config.setDriverClassName("org.sqlite.JDBC");
        }
        return new ExtendedHikariDatasource(config);
    }

    private String getDataSourceUrl() {
        return propertiesProvider.get("dataSourceUrl").orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }

    private static class NullUIService extends LoggerUIService {
        @Override public void sendMessage(String message) {}
        @Override public void sendErrorMessage(String message, Throwable exception) {}
    }
}
