package org.icij.datashare.db;

import com.google.inject.Inject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.RepositoryFactory;
import org.jetbrains.annotations.NotNull;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;

import javax.sql.DataSource;
import java.sql.SQLException;

public class RepositoryFactoryImpl implements RepositoryFactory {
    private final PropertiesProvider propertiesProvider;

    public RepositoryFactoryImpl() {
        propertiesProvider = new PropertiesProvider();
    }

    @Inject
    public RepositoryFactoryImpl(final PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    void initDatabase(final DataSource dataSource) {
        try {
            Liquibase liquibase = new liquibase.Liquibase("liquibase/changelog/db.changelog.yml", new ClassLoaderResourceAccessor(),
                            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection())));
            liquibase.update(new Contexts());
        } catch (LiquibaseException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void initDatabase() {
        try (HikariDataSource datasource = (HikariDataSource) createDatasource()) {
            initDatabase(datasource);
        }
    }

    public Repository createRepository() {
        String dataSourceUrl = getDataSourceUrl();
        DataSourceConnectionProvider connectionProvider;
        DataSource dataSource = createDatasource();
        connectionProvider = new DataSourceConnectionProvider(dataSource);
        return new JooqRepository(connectionProvider, guessSqlDialectFrom(dataSourceUrl));
    }

    public JooqBatchSearchRepository createBatchSearchRepository() {
        String dataSourceUrl = getDataSourceUrl();
        DataSourceConnectionProvider connectionProvider;
        DataSource dataSource = createDatasource();
        connectionProvider = new DataSourceConnectionProvider(dataSource);
        return new JooqBatchSearchRepository(connectionProvider, guessSqlDialectFrom(dataSourceUrl));
    }

    static SQLDialect guessSqlDialectFrom(String dataSourceUrl) {
        for (SQLDialect dialect: SQLDialect.values()) {
            if (dataSourceUrl.contains(dialect.getName().toLowerCase())) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("unknown SQL dialect for datasource : " + dataSourceUrl);
    }

    DataSource createDatasource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(getDataSourceUrl());
        return new HikariDataSource(config);
    }

    @NotNull
    private String getDataSourceUrl() {
        return propertiesProvider.get("dataSourceUrl").orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }
}
