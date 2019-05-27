package org.icij.datashare.db;

import com.google.inject.Inject;
import com.mchange.v2.c3p0.DataSources;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.RepositoryFactory;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;

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

    public Repository createRepository() {
        String dataSourceUrl = propertiesProvider.get("dataSourceUrl").orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
        DataSourceConnectionProvider connectionProvider = null;
        try {
            connectionProvider = new DataSourceConnectionProvider(
                    DataSources.pooledDataSource(DataSources.unpooledDataSource(dataSourceUrl)));
        } catch (SQLException e) {
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
}
