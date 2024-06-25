package org.icij.datashare.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

    public Repository createRepository() {
        return createRepository(JooqRepository::new);
    }
    public JooqApiKeyRepository createApiKeyRepository() {
        return createRepository(JooqApiKeyRepository::new);
    }

    public JooqBatchSearchRepository createBatchSearchRepository() {
        return createRepository(JooqBatchSearchRepository::new);
    }

    void initDatabase(final DataSource dataSource) {
        System.setProperty("liquibase.command.showSummaryOutput", "LOG"); // avoid double log
        try (Connection connection = dataSource.getConnection()){
            try (Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection))) {
                CommandScope updateCommand = new CommandScope(UpdateCommandStep.COMMAND_NAME);
                updateCommand.addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "liquibase/changelog/db.changelog.yml");
                Scope.enter(Map.of(Scope.Attr.ui.name(), new NullUIService()));
                updateCommand.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, db);
                updateCommand.execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch ( SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void initDatabase() {
        initDatabase(dataSource);
    }

    private <T> T createRepository(BiFunction<DataSource, SQLDialect, T> constructor) {
        return constructor.apply(dataSource, guessSqlDialectFrom(getDataSourceUrl()));
    }

    static SQLDialect guessSqlDialectFrom(String dataSourceUrl) {
        for (SQLDialect dialect: SQLDialect.values()) {
            if (dataSourceUrl.contains(dialect.name().toLowerCase())) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("unknown SQL dialect for datasource : " + dataSourceUrl);
    }

    DataSource createDatasource() {
        HikariConfig config = new HikariConfig();
        String dataSourceUrl = getDataSourceUrl();
        config.setJdbcUrl(dataSourceUrl);
        if (dataSourceUrl.contains("sqlite")) {
            config.setDriverClassName("org.sqlite.JDBC");
        }
        return new HikariDataSource(config);
    }

    private String getDataSourceUrl() {
        return propertiesProvider.get("dataSourceUrl").orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }

    private static class NullUIService extends LoggerUIService {
        @Override public void sendMessage(String message) {}
        @Override public void sendErrorMessage(String message, Throwable exception) {}
    }
}
