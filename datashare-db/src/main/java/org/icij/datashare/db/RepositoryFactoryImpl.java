package org.icij.datashare.db;

import com.google.inject.Inject;
import com.mchange.v2.c3p0.DataSources;
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
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;

public class RepositoryFactoryImpl implements RepositoryFactory {
    private final PropertiesProvider propertiesProvider;

    public RepositoryFactoryImpl() {
        propertiesProvider = new PropertiesProvider();
    }

    @Inject
    public RepositoryFactoryImpl(final PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    public void initDatabase() {
        DataSource dataSource = null;
        try {
            dataSource = createDatasource();
            Liquibase liquibase = new liquibase.Liquibase("liquibase/changelog/db.changelog.yml", new ClassLoaderResourceAccessor(),
                            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection())));
            liquibase.update(new Contexts());
        } catch (LiquibaseException | SQLException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                DataSources.destroy(dataSource);
            } catch (SQLException e) {
                LoggerFactory.getLogger(getClass()).error("cannot destroy connection pool", e);
            }
        }
    }

    public Repository createRepository() {
        String dataSourceUrl = getDataSourceUrl();
        DataSourceConnectionProvider connectionProvider;
        try {
            DataSource dataSource = createDatasource();
            connectionProvider = new DataSourceConnectionProvider(dataSource);
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
        return new JooqRepository(connectionProvider, guessSqlDialectFrom(dataSourceUrl));
    }

    static SQLDialect guessSqlDialectFrom(String dataSourceUrl) {
        for (SQLDialect dialect: SQLDialect.values()) {
            if (dataSourceUrl.contains(dialect.getName().toLowerCase())) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("unknown SQL dialect for datasource : " + dataSourceUrl);
    }

    private DataSource createDatasource() throws SQLException, IOException {
        Properties props = new Properties();
        props.load(getClass().getResourceAsStream("/c3p0.properties"));
        return DataSources.unpooledDataSource(getDataSourceUrl(), props);
    }

    @NotNull
    private String getDataSourceUrl() {
        return propertiesProvider.get("dataSourceUrl").orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }
}
