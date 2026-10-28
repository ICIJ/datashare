package org.icij.datashare.asynctasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.icij.datashare.text.Project;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class ScopedStageContextTest {
    private final List<String> closed = new ArrayList<>();
    private final ScopedStageContext<String> ctx = new ScopedStageContext<>() {
        public Project project() {return null;}

        public Map<String, Object> options() {return Map.of();}

        public boolean isCancelled() {return false;}

        public void emit(List<String> out) {}
    };

    @Test
    public void test_a_resource_is_built_once_and_shared() {
        AtomicInteger built = new AtomicInteger();

        Closing first = ctx.resource(Closing.class, () -> {
            built.incrementAndGet();
            return new Closing("a");
        });
        Closing second = ctx.resource(Closing.class, () -> {
            built.incrementAndGet();
            return new Closing("b");
        });

        assertThat(second).isSameAs(first);
        assertThat(built.get()).isEqualTo(1);
    }

    @Test
    public void test_resources_of_different_types_are_distinct() {
        Outer outer = ctx.resource(Outer.class, Outer::new);
        Inner inner = ctx.resource(Inner.class, Inner::new);

        assertThat(outer).isNotSameAs(inner);
    }

    @Test
    public void test_a_resource_built_by_a_factory_of_another_is_closed_after_it() {
        ctx.resource(Outer.class, () -> {
            ctx.resource(Inner.class, Inner::new);
            return new Outer();
        });

        ctx.close();

        assertThat(closed).containsExactly("outer", "inner");
    }

    @Test
    public void test_close_closes_the_most_recent_first_and_only_once() {
        ctx.resource(Inner.class, Inner::new);
        ctx.resource(Outer.class, Outer::new);

        ctx.close();
        ctx.close();

        assertThat(closed).containsExactly("outer", "inner");
    }

    @Test
    public void test_a_resource_that_is_not_closeable_is_left_alone() {
        ctx.resource(String.class, () -> "plain");

        ctx.close();
    }

    @Test
    public void test_a_failing_factory_builds_nothing_and_is_retried() {
        assertThrows(IllegalStateException.class, () -> ctx.resource(Closing.class, () -> {
            throw new IllegalStateException("no index");
        }));

        Closing built = ctx.resource(Closing.class, () -> new Closing("retry"));

        assertThat(built).isNotNull();
    }

    @Test
    public void test_a_factory_that_builds_nothing_is_refused() {
        assertThrows(NullPointerException.class, () -> ctx.resource(Closing.class, () -> null));
    }

    @Test
    public void test_every_resource_is_closed_even_if_one_fails_to_close() {
        ctx.resource(Inner.class, Inner::new);
        ctx.resource(Closing.class, () -> new Closing("failing") {
            @Override
            public void close() {
                super.close();
                throw new IllegalStateException("cannot close");
            }
        });

        RuntimeException failure = assertThrows(RuntimeException.class, ctx::close);

        assertThat(closed).containsExactly("failing", "inner");
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
    }

    private class Closing implements AutoCloseable {
        private final String name;

        Closing(String name) {this.name = name;}

        @Override
        public void close() {closed.add(name);}
    }

    private class Outer extends Closing {
        Outer() {super("outer");}
    }

    private class Inner extends Closing {
        Inner() {super("inner");}
    }

}
