package org.icij.datashare.mode;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asyncsearch.AsyncSearchStore;
import org.icij.datashare.asyncsearch.MemoryAsyncSearchStore;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskModifier;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.cli.AuthUsersProvider;
import org.icij.datashare.policies.Authorizer;
import net.codestory.http.security.Users;
import org.icij.datashare.session.UsersInDb;
import org.icij.datashare.session.UsersInRedis;
import org.junit.Test;

import java.util.HashMap;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BUS_TYPE;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PARALLELISM;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_QUEUE_TYPE;
import static org.icij.datashare.cli.DatashareCliOptions.REDIS_POOL_SIZE_OVERHEAD;

public class CommonModeTest {
    @Test
    public void test_has_redis_property() {
        CommonMode modeWithRedis = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("busType", QueueType.REDIS.name());
        }});

        CommonMode modeWithoutRedis = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.MEMORY.name());
        }});

        assertThat(CommonMode.getQueueType(modeWithRedis.propertiesProvider,"busType", DEFAULT_BUS_TYPE)).isEqualTo(QueueType.REDIS);
        assertThat(CommonMode.getQueueType(modeWithoutRedis.propertiesProvider,"queueType", DEFAULT_QUEUE_TYPE)).isEqualTo(QueueType.MEMORY);
    }

    @Test
    public void test_tasks_instances() {
        Injector injector = Guice.createInjector(CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.MEMORY.name());
        }}));
        assertThat(injector.getInstance(TaskManager.class)).isSameAs(injector.getInstance(TaskModifier.class));
        assertThat(injector.getInstance(TaskManager.class)).isSameAs(injector.getInstance(TaskSupplier.class));
    }
    @Test
    public void test_check_queue_type_with_default_value() {
        Properties props = new Properties(){{
            put("busType", "REDIS");
            put("queueType", "amqp");
            put("otherProp", "badValueQueueType");}};

        QueueType normalQueueType =  CommonMode.getQueueType(new PropertiesProvider(props),"busType", QueueType.MEMORY);
        assertThat(normalQueueType).isEqualTo(QueueType.REDIS);

        QueueType caseInsensitiveQueueType =  CommonMode.getQueueType(new PropertiesProvider(props),"queueType", QueueType.MEMORY);
        assertThat(caseInsensitiveQueueType).isEqualTo(QueueType.AMQP);

        QueueType defaultQueueType =  CommonMode.getQueueType(new PropertiesProvider(props),"propertyNotSet",QueueType.MEMORY);
        assertThat(defaultQueueType).isEqualTo(QueueType.MEMORY);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_throw_exception_when_queue_type_is_not_enum_value() {
        Properties props = new Properties(){{ put("otherProp", "badValueQueueType");}};

        CommonMode.getQueueType(new PropertiesProvider(props),"otherProp", QueueType.AMQP);
    }

    @Test
    public void test_has_queue_capacity_property_in_memory() throws Exception {
        CommonMode modeInMemory = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.MEMORY.name());
            put("queueCapacity", "10");
        }});
        assertThat(modeInMemory.propertiesProvider.queueCapacity()).isEqualTo(10);
    }

    @Test
    public void test_has_default_queue_capacity_when_property_not_set_in_memory() throws Exception {
        CommonMode modeInMemoryNoCapacitySet = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.MEMORY.name());
        }});
        assertThat(modeInMemoryNoCapacitySet.propertiesProvider.queueCapacity()).isEqualTo(1000000);
    }

    @Test
    public void test_bad_users_class_uses_default() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("authUsersProvider", "org.icij.UnknownClass");
        }});
        assertThat(mode.get(Users.class)).isInstanceOf(UsersInDb.class);
    }

    @Test
    public void test_class_for_maps_every_users_provider() {
        assertThat(CommonMode.classFor(AuthUsersProvider.DATABASE)).isEqualTo(UsersInDb.class);
        assertThat(CommonMode.classFor(AuthUsersProvider.REDIS)).isEqualTo(UsersInRedis.class);
    }

    @Test
    public void test_users_provider_label_database() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("authUsersProvider", "database");
        }});
        assertThat(mode.get(Users.class)).isInstanceOf(UsersInDb.class);
    }

    @Test
    public void test_users_provider_label_redis() {
        // UsersInRedis builds its JedisPool lazily, so this resolves without a live Redis.
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("authUsersProvider", "redis");
        }});
        assertThat(mode.get(Users.class)).isInstanceOf(UsersInRedis.class);
    }

    @Test
    public void test_users_provider_default_is_database() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
        }});
        assertThat(mode.get(Users.class)).isInstanceOf(UsersInDb.class);
    }

    @Test
    public void test_legacy_users_provider_class_name_still_resolves() {
        CommonMode mode = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("authUsersProvider", "org.icij.datashare.session.UsersInDb");
        }});
        assertThat(mode.get(Users.class)).isInstanceOf(UsersInDb.class);
    }

    @Test
    public void test_async_search_store_is_memory_in_memory_mode() {
        Injector injector = Guice.createInjector(CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.MEMORY.name());
        }}));
        assertThat(injector.getInstance(AsyncSearchStore.class)).isInstanceOf(MemoryAsyncSearchStore.class);
    }

    @Test
    public void test_redis_pool_size_defaults_to_parallelism_plus_overhead() {
        Properties props = new Properties() {{ put("parallelism", "9"); }};
        assertThat(CommonMode.redisPoolSize(new PropertiesProvider(props))).isEqualTo(9 + REDIS_POOL_SIZE_OVERHEAD);
    }

    @Test
    public void test_redis_pool_size_explicit_value_wins_when_above_floor() {
        Properties props = new Properties() {{ put("parallelism", "9"); put("redisPoolSize", "20"); }};
        assertThat(CommonMode.redisPoolSize(new PropertiesProvider(props))).isEqualTo(20);
    }

    @Test
    public void test_redis_pool_size_is_floored_when_explicit_value_below_worker_count() {
        Properties props = new Properties() {{ put("parallelism", "9"); put("redisPoolSize", "6"); }};
        assertThat(CommonMode.redisPoolSize(new PropertiesProvider(props))).isEqualTo(9 + REDIS_POOL_SIZE_OVERHEAD);
    }

    @Test
    public void test_redis_pool_size_falls_back_to_default_parallelism_when_unset() {
        assertThat(CommonMode.redisPoolSize(new PropertiesProvider(new Properties())))
                .isEqualTo(DEFAULT_PARALLELISM + REDIS_POOL_SIZE_OVERHEAD);
    }

    @Test
    public void test_redis_pool_size_backward_compatible_with_single_worker() {
        Properties props = new Properties() {{ put("parallelism", "1"); }};
        assertThat(CommonMode.redisPoolSize(new PropertiesProvider(props))).isEqualTo(5);
    }

    @Test
    public void test_authorizer_injectable_in_memory_mode() {
        Injector injector = Guice.createInjector(CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("busType", QueueType.MEMORY.name());
        }}));
        Authorizer authorizer = injector.getInstance(Authorizer.class);
        assertThat(authorizer).isNotNull();
        assertThat(injector.getInstance(Authorizer.class)).isSameAs(authorizer);
    }
}
