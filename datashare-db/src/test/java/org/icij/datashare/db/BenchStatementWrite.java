package org.icij.datashare.db;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
import org.jooq.SQLDialect;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.icij.datashare.db.Tables.STATEMENT;

/**
 * What a million-line FtM dump costs the write path as it stands. Not a unit test: it needs a real
 * Postgres and takes minutes, so it is named Bench rather than Test and surefire never picks it up.
 */
public class BenchStatementWrite {
    private static final Logger LOGGER = LoggerFactory.getLogger(BenchStatementWrite.class);
    private static final long ROWS = 1_000_000L;
    private static final int PROPERTIES = 6;

    @Rule public DbSetupRule dbRule = new DbSetupRule(
            EnvUtils.resolveUri("postgres", "jdbc:postgresql://postgres/dstest?user=dstest&password=test"));

    @Test
    public void bench_replace_of_a_million_row_dump() {
        StatementRepository repository = new JooqStatementRepository(dbRule.dataSource, SQLDialect.POSTGRES);
        long begin = System.currentTimeMillis();
        StatementRepository.Replaced replaced = repository.replace("prj", "run-1", "doc-1", "", statements());
        long elapsed = System.currentTimeMillis() - begin;

        LOGGER.info("BENCH wrote {} statements in {} ms ({} per second)", replaced.written(), elapsed,
                    replaced.written() * 1000L / Math.max(elapsed, 1));
        LOGGER.info("BENCH table {}",
                    dbRule.dsl().fetchOne("select pg_size_pretty(pg_total_relation_size('statement'))").get(0));
        LOGGER.info("BENCH heap {}",
                    dbRule.dsl().fetchOne("select pg_size_pretty(pg_relation_size('statement'))").get(0));
        LOGGER.info("BENCH rows {}", dbRule.dsl().selectCount().from(STATEMENT).fetchOne().value1());
        dbRule.shutdown();
    }

    private static Stream<Statement> statements() {
        return LongStream.range(0, ROWS).boxed().flatMap(row -> LongStream.range(0, PROPERTIES).mapToObj(
                property -> Statement.of("ftm", "entity-" + row, "Person", "name", "value-" + row + "-" + property,
                                         new Statement.Provenance("doc-1", "", row + 1, "column-" + property))));
    }
}
