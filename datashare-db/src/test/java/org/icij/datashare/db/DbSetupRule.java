package org.icij.datashare.db;

import com.ninja_squad.dbsetup.DbSetup;
import com.ninja_squad.dbsetup.destination.DataSourceDestination;
import com.ninja_squad.dbsetup.operation.Operation;
import org.icij.datashare.PropertiesProvider;
import org.junit.rules.ExternalResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;

import static com.ninja_squad.dbsetup.Operations.deleteAllFrom;
import static com.ninja_squad.dbsetup.operation.CompositeOperation.sequenceOf;
import static java.util.Optional.ofNullable;

public class DbSetupRule extends ExternalResource {
    final DataSource dataSource;
    private static final Operation DELETE_ALL = deleteAllFrom(
            "document", "named_entity", "document_user_star", "document_tag", "batch_search", "batch_search_query");

    DbSetupRule(DataSource dataSource) { this.dataSource = dataSource;}

    @Override
    protected void before() {
        new RepositoryFactoryImpl().initDatabase(dataSource);
        Operation operation = sequenceOf(DELETE_ALL);
        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();
    }

    static DataSource createDatasource(final String jdbcUrl) throws IOException, SQLException {
        return new RepositoryFactoryImpl(new PropertiesProvider(new HashMap<String, String>() {{
            put("dataSourceUrl", ofNullable(jdbcUrl).orElse("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared"));
        }})).createDatasource();
    }
}
