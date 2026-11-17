package org.icij.datashare.asynctasks;

import java.util.Map;
import java.util.concurrent.Callable;
import org.icij.datashare.user.User;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class TaskFactoryHelperTest {
    private final TestFactory factory = new TestFactory();

    @Test
    public void test_creates_the_task_named_by_its_class() throws Exception {
        String name = TestFactory.Sleep.class.getName();

        Callable<?> callable = TaskFactoryHelper.createTaskCallable(factory, name,
                new Task<>(name, User.local(), Map.of("duration", 1)), p -> null);

        assertThat(callable).isInstanceOf(TestFactory.Sleep.class);
    }

    @Test
    public void test_creates_a_task_whose_factory_method_returns_a_supertype() throws Exception {
        String name = TestFactory.HelloWorld.class.getName();

        Callable<?> callable = TaskFactoryHelper.createTaskCallable(factory, name,
                new Task<>(name, User.local(), Map.of("greeted", "world")), p -> null);

        assertThat(callable).isInstanceOf(TestFactory.HelloWorld.class);
    }

    @Test
    public void test_refuses_another_class_with_the_simple_name_of_a_factory_task() {
        String name = Sleep.class.getName();

        assertThrows(NoSuchMethodException.class, () -> TaskFactoryHelper.createTaskCallable(factory, name,
                new Task<>(name, User.local(), Map.of("duration", 1)), p -> null));
    }

    /** Same simple name as {@link TestFactory.Sleep}, but a different class: the factory does not build it. */
    public static class Sleep implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }
    }
}
