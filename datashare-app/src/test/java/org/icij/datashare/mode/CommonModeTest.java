package org.icij.datashare.mode;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskModifier;
import org.icij.datashare.tasks.TaskSupplier;
import org.junit.Test;

import java.util.HashMap;

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

        assertThat(modeWithRedis.hasRedisProperty()).isTrue();
        assertThat(modeWithoutRedis.hasRedisProperty()).isFalse();
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
}
