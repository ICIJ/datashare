package org.icij.datashare.db;

import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.DatashareApiKey.getBase64Encoded;

@RunWith(Parameterized.class)
public class JooqApiKeyRepositoryTest {
    @Rule public DbSetupRule dbRule;
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-07-08T12:13:14Z");
    private final JooqApiKeyRepository repository;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Test
    public void test_save_and_get_api_key() throws NoSuchAlgorithmException {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();

        assertThat(repository.save(new DatashareApiKey(secretKey, User.local()))).isTrue();

        ApiKey apiKey = repository.get(getBase64Encoded(secretKey));
        assertThat(apiKey.match(getBase64Encoded(secretKey))).isTrue();
    }

    @Test
    public void test_save_api_key_for_user_overrides_old_key() throws NoSuchAlgorithmException {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        DatashareApiKey apiKey1 = new DatashareApiKey(secretKey, User.local());
        repository.save(apiKey1);

        DatashareTime.getInstance().addMilliseconds(12000);
        assertThat(repository.save(new DatashareApiKey(User.local()))).isTrue();

        assertThat(repository.get(getBase64Encoded(secretKey))).isNull();
        assertThat(repository.get(User.local()).getCreationDate()).isEqualTo(time.now());
    }

    @Test
    public void test_delete_api_key_from_user() throws NoSuchAlgorithmException {
        repository.save(new DatashareApiKey(User.local()));
        assertThat(repository.get(User.local())).isNotNull();

        assertThat(repository.delete(User.local())).isTrue();
        assertThat(repository.get(User.local())).isNull();
    }

    @Test
    public void test_get_from_user() throws NoSuchAlgorithmException {
        DatashareApiKey expected = new DatashareApiKey(User.local());
        repository.save(expected);
        assertThat(repository.get(User.local())).isEqualTo(expected);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }

    @AfterClass
    public static void shutdownPools() {
        for (DbSetupRule rule : rulesToClose) {
            rule.shutdown();
        }
    }

    public JooqApiKeyRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createApiKeyRepository();
        rulesToClose.add(dbRule);
    }
}
