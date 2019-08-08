package org.icij.datashare.db;

import org.jooq.SQLDialect;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;


public class RepositoryFactoryImplTest {
    @Test
    public void test_guess_sql_dialect_sqlite() {
        assertThat(RepositoryFactoryImpl.guessSqlDialectFrom("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")).isEqualTo(SQLDialect.SQLITE);
        assertThat(RepositoryFactoryImpl.guessSqlDialectFrom("jdbc:sqlite://home/dev/test.sqlite")).isEqualTo(SQLDialect.SQLITE);
    }

    @Test
    public void test_guess_sql_dialect_postgresql() {
        assertThat(RepositoryFactoryImpl.guessSqlDialectFrom("jdbc:postgresql://host/db")).isEqualTo(SQLDialect.POSTGRES);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_guess_sql_dialect_unknown() {
        RepositoryFactoryImpl.guessSqlDialectFrom("jdbc:blah");
    }
}
