package org.icij.datashare.db;

import com.ninja_squad.dbsetup.DbSetup;
import com.ninja_squad.dbsetup.destination.DataSourceDestination;
import com.ninja_squad.dbsetup.operation.Operation;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.rules.ExternalResource;
import org.postgresql.ds.PGPoolingDataSource;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

import static com.ninja_squad.dbsetup.Operations.deleteAllFrom;
import static com.ninja_squad.dbsetup.operation.CompositeOperation.sequenceOf;

public class DbSetupRule extends ExternalResource {
    final DataSource dataSource;
    public static final Operation DELETE_ALL = deleteAllFrom(
            "document", "document_meta", "document_ner_pipeline_type", "named_entity", "ner_pipeline_type");

    DbSetupRule(DataSource dataSource) { this.dataSource = dataSource;}

    @Override
    protected void before() throws Throwable {
        Liquibase liquibase = new liquibase.Liquibase("src/main/resources/liquibase/changelog/db.changelog.yml", new FileSystemResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection())));
        liquibase.update(new Contexts());

        Operation operation = sequenceOf(DELETE_ALL);
        DbSetup dbSetup = new DbSetup(new DataSourceDestination(dataSource), operation);
        dbSetup.launch();
    }

    @Override
    protected void after() {

    }

    static DataSource createSqlite() {
        SQLiteDataSource sqLiteDataSource = new SQLiteDataSource();
        sqLiteDataSource.setUrl("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
        return sqLiteDataSource;
    }

    static DataSource createPostgresql() {
        PGPoolingDataSource source = new PGPoolingDataSource();
        source.setServerName("postgresql");
        source.setDatabaseName("test");
        source.setUser("test");
        source.setPassword("test");
        source.setMaxConnections(10);
        return source;
    }
}
