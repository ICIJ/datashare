package org.icij.datashare.db;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.rules.ExternalResource;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

public class DbSetupRule extends ExternalResource {
    final DataSource dataSource;

    DbSetupRule(DataSource dataSource) { this.dataSource = dataSource;}

    @Override
    protected void before() throws Throwable {
        Liquibase liquibase = new liquibase.Liquibase("src/main/resources/liquibase/changelog/db.changelog.yml", new FileSystemResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection())));
        liquibase.update(new Contexts());
    }

    @Override
    protected void after() {

    }

    static DataSource createSqlite() {
        SQLiteDataSource sqLiteDataSource = new SQLiteDataSource();
        sqLiteDataSource.setUrl("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
        return sqLiteDataSource;
    }
}
