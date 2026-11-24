package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.Interruptions.causedByInterrupt;
import org.junit.Test;

public class InterruptionsTest {
    @Test
    public void test_an_interrupted_exception_is_an_interruption() {
        assertThat(causedByInterrupt(new InterruptedException())).isTrue();
    }

    @Test
    public void test_an_interruption_wrapped_at_any_depth_is_found() {
        assertThat(causedByInterrupt(new RuntimeException(new IllegalStateException(new InterruptedException()))))
                .isTrue();
    }

    @Test
    public void test_another_exception_is_not_an_interruption() {
        assertThat(causedByInterrupt(new RuntimeException(new IllegalStateException("boom")))).isFalse();
    }

    @Test
    public void test_an_exception_that_is_its_own_cause_ends_the_walk() {
        assertThat(causedByInterrupt(new RuntimeException() {
            @Override
            public synchronized Throwable getCause() {return this;}
        })).isFalse();
    }
}
