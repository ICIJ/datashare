package org.icij.datashare.mode;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskModifier;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.junit.Test;

import java.util.HashMap;
import java.util.Properties;

import static java.lang.Integer.parseInt;
import static org.fest.assertions.Assertions.assertThat;

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

        assertThat(modeWithRedis.hasQueueType(QueueType.REDIS)).isTrue();
        assertThat(modeWithoutRedis.hasQueueType(QueueType.REDIS)).isFalse();
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
    public void test_has_queue_capacity_property_in_memory() {
        CommonMode modeInMemory = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.MEMORY.name());
            put("queueCapacity", "10");
        }});
        assertThat(modeInMemory.getQueueCapacity()).isEqualTo(10);
    }
    @Test
    public void test_has_no_queue_capacity_property_when_queue_is_not_in_memory() {
        CommonMode modeRedis = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.REDIS.name());
            put("queueCapacity", "10");
        }});
        assertThat(modeRedis.getQueueCapacity()).isEqualTo(-1);


    }
    @Test
    public void test_has_default_queue_capacity_when_property_not_set_in_memory() {
        CommonMode modeInMemoryNoCapacitySet = CommonMode.create(new HashMap<>() {{
            put("mode", Mode.LOCAL.name());
            put("queueType", QueueType.MEMORY.name());
        }});
        assertThat(modeInMemoryNoCapacitySet.getQueueCapacity()).isEqualTo(1000000);
    }

}
