package org.icij.datashare.db;

import org.icij.datashare.EnvUtils;


/**
 * Singleton provider for shared DbSetupRule instances across all test classes.
 *
 * This provider ensures only 2 pools are created and reused by all test classes
 */
public class DbTestRuleProvider {
    private static final DbSetupRule SQLITE_RULE = new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    private static final DbSetupRule POSTGRES_RULE = new DbSetupRule(
            EnvUtils.resolveUri("postgres", "jdbc:postgresql://postgres/dstest?user=dstest&password=test")
    );

    private static boolean shutdownHookRegistered = false;

    public static DbSetupRule getSqliteRule() {
        registerShutdownHookOnce();
        return SQLITE_RULE;
    }

    public static DbSetupRule getPostgresRule() {
        registerShutdownHookOnce();
        return POSTGRES_RULE;
    }

    private static synchronized void registerShutdownHookOnce() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                SQLITE_RULE.shutdown();
                POSTGRES_RULE.shutdown();
            }));
            shutdownHookRegistered = true;
        }
    }
}
