package org.icij.datashare.db;

import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.DatashareApiKey.getBase64Encoded;

@RunWith(Parameterized.class)
public class JooqApiKeyRepositoryTest {
    @Rule public DbSetupRule dbRule;
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-07-08T12:13:14Z");
    private final JooqApiKeyRepository repository;

    @Test
    public void test_save_and_get_api_key() throws NoSuchAlgorithmException {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();

        assertThat(repository.save(new DatashareApiKey(secretKey, User.local()))).isTrue();

        ApiKey apiKey = repository.get(getBase64Encoded(secretKey));
        assertThat(apiKey.match(getBase64Encoded(secretKey))).isTrue();
    }

    @Test
    public void test_save_twice_keeps_both_keys() throws NoSuchAlgorithmException {
        SecretKey secretKey1 = DatashareApiKey.generateSecretKey();
        SecretKey secretKey2 = DatashareApiKey.generateSecretKey();
        repository.save(new DatashareApiKey(secretKey1, User.local()));
        repository.save(new DatashareApiKey(secretKey2, User.local()));

        assertThat(repository.getAll(User.local())).hasSize(2);
        assertThat(repository.get(getBase64Encoded(secretKey1))).isNotNull();
        assertThat(repository.get(getBase64Encoded(secretKey2))).isNotNull();
    }

    @Test
    public void test_get_user_returns_newest_key() throws NoSuchAlgorithmException {
        DatashareApiKey key1 = new DatashareApiKey(User.local());
        repository.save(key1);
        DatashareTime.getInstance().addMilliseconds(1000);
        DatashareApiKey key2 = new DatashareApiKey(User.local());
        repository.save(key2);

        assertThat(repository.get(User.local()).getId()).isEqualTo(key2.getId());
    }

    @Test
    public void test_save_and_get_projects() throws NoSuchAlgorithmException {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        repository.save(new DatashareApiKey(secretKey, User.local(), null, List.of("a", "b")));

        assertThat(repository.get(getBase64Encoded(secretKey)).getProjects()).containsOnly("a", "b");
    }

    @Test
    public void test_save_ignores_duplicate_projects() throws NoSuchAlgorithmException {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        repository.save(new DatashareApiKey(secretKey, User.local(), null, List.of("a", "b", "a")));

        assertThat(repository.get(getBase64Encoded(secretKey)).getProjects()).containsOnly("a", "b").hasSize(2);
    }

    @Test
    public void test_key_without_projects_is_unscoped() throws NoSuchAlgorithmException {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        repository.save(new DatashareApiKey(secretKey, User.local()));

        assertThat(repository.get(getBase64Encoded(secretKey)).getProjects()).isEmpty();
    }

    @Test
    public void test_delete_one_key_by_hash() throws Exception {
        SecretKey secretKey1 = DatashareApiKey.generateSecretKey();
        SecretKey secretKey2 = DatashareApiKey.generateSecretKey();
        DatashareApiKey key1 = new DatashareApiKey(secretKey1, User.local(), null, List.of("a"));
        repository.save(key1);
        repository.save(new DatashareApiKey(secretKey2, User.local()));

        assertThat(repository.delete(User.local(), key1.getId())).isTrue();

        assertThat(repository.get(getBase64Encoded(secretKey1))).isNull();
        assertThat(repository.get(getBase64Encoded(secretKey2))).isNotNull();
        assertThat(countScopeRows(key1.getId())).isEqualTo(0);
    }

    @Test
    public void test_delete_one_key_of_another_user_is_refused() throws Exception {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        DatashareApiKey key1 = new DatashareApiKey(secretKey, User.local(), null, List.of("a"));
        repository.save(key1);

        assertThat(repository.delete(new User("bob"), key1.getId())).isFalse();

        assertThat(repository.get(getBase64Encoded(secretKey))).isNotNull();
        assertThat(countScopeRows(key1.getId())).isEqualTo(1);
    }

    @Test
    public void test_delete_user_deletes_all_keys_and_scopes() throws Exception {
        DatashareApiKey key1 = new DatashareApiKey(DatashareApiKey.generateSecretKey(), User.local(), null, List.of("a"));
        DatashareApiKey key2 = new DatashareApiKey(DatashareApiKey.generateSecretKey(), User.local(), null, List.of("b"));
        repository.save(key1);
        repository.save(key2);

        assertThat(repository.delete(User.local())).isTrue();

        assertThat(repository.getAll(User.local())).isEmpty();
        assertThat(countScopeRows(key1.getId())).isEqualTo(0);
        assertThat(countScopeRows(key2.getId())).isEqualTo(0);
    }

    @Test
    public void test_replace_deletes_every_key_of_the_user() throws NoSuchAlgorithmException {
        repository.save(new DatashareApiKey(DatashareApiKey.generateSecretKey(), User.local(), null, List.of("a")));
        repository.save(new DatashareApiKey(DatashareApiKey.generateSecretKey(), User.local(), null, List.of("b")));
        DatashareApiKey key3 = new DatashareApiKey(User.local());

        assertThat(repository.replace(key3)).isTrue();

        List<ApiKey> keys = repository.getAll(User.local());
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getId()).isEqualTo(key3.getId());
        assertThat(keys.get(0).getProjects()).isEmpty();
    }

    @Test
    public void test_save_and_get_expiration_date() throws NoSuchAlgorithmException {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        Date expiration = Date.from(Instant.parse("2020-08-01T00:00:00Z"));
        repository.save(new DatashareApiKey(secretKey, User.local(), expiration, List.of()));

        assertThat(repository.get(getBase64Encoded(secretKey)).getExpirationDate()).isEqualTo(expiration);
    }

    @Test
    public void test_save_without_expiration_date_reads_null() throws NoSuchAlgorithmException {
        repository.save(new DatashareApiKey(User.local()));
        assertThat(repository.get(User.local()).getExpirationDate()).isNull();
    }

    @Test
    public void test_save_again_resets_expiration_date() throws NoSuchAlgorithmException {
        Date expiration = Date.from(Instant.parse("2020-08-01T00:00:00Z"));
        repository.save(new DatashareApiKey(DatashareApiKey.generateSecretKey(), User.local(), expiration, List.of()));
        DatashareTime.getInstance().addMilliseconds(1000);
        repository.save(new DatashareApiKey(User.local()));

        assertThat(repository.get(User.local()).getExpirationDate()).isNull();
    }

    @Test
    public void test_creation_date_round_trips_outside_utc() throws NoSuchAlgorithmException {
        TimeZone defaultZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        try {
            repository.save(new DatashareApiKey(User.local()));
            assertThat(repository.get(User.local()).getCreationDate()).isEqualTo(time.now());
        } finally {
            TimeZone.setDefault(defaultZone);
        }
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

    private int countScopeRows(String hashedKey) throws SQLException {
        try (Connection connection = dbRule.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select count(*) from api_key_project where api_key_id = ?")) {
            statement.setString(1, hashedKey);
            ResultSet resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {DbTestRuleProvider.getSqliteRule()},
                {DbTestRuleProvider.getPostgresRule()}
        });
    }

    public JooqApiKeyRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createApiKeyRepository();
    }
}
