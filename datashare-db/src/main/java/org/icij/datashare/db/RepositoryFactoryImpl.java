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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BiFunction;

public class RepositoryFactoryImpl implements RepositoryFactory {
    private final PropertiesProvider propertiesProvider;

    RepositoryFactoryImpl() {
        this(new PropertiesProvider());
    }

    @Inject
    public RepositoryFactoryImpl(final PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
        System.getProperties().setProperty("org.jooq.no-logo", "true");
    }

    public Repository createRepository() {
        return createRepository(JooqRepository::new);
    }

    public JooqBatchSearchRepository createBatchSearchRepository() {
        return createRepository(JooqBatchSearchRepository::new);
    }

    void initDatabase(final DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()){
            Liquibase liquibase = new liquibase.Liquibase("liquibase/changelog/db.changelog.yml", new ClassLoaderResourceAccessor(),
                            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
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

    private <T> T createRepository(BiFunction<DataSource, SQLDialect, T> constructor) {
        return constructor.apply(createDatasource(), guessSqlDialectFrom(getDataSourceUrl()));
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

    @NotNull
    private String getDataSourceUrl() {
        return propertiesProvider.get("dataSourceUrl").orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }
}
