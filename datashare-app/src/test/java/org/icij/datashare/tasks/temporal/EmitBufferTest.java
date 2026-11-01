package org.icij.datashare.tasks.temporal;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;

public class EmitBufferTest {
    private final List<List<String>> sent = new ArrayList<>();
    private final EmitBuffer<String> buffer = new EmitBuffer<>(3, sent::add);

    @Test
    public void test_nothing_is_sent_below_the_limit() {
        buffer.add(List.of("a"));
        buffer.add(List.of("b"));

        assertThat(sent).isEmpty();
    }

    @Test
    public void test_one_signal_for_everything_emitted_one_by_one() {
        for (int i = 0; i < 7; i++) {
            buffer.add(List.of("doc" + i));
        }
        buffer.flush();

        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).containsExactly("doc0", "doc1", "doc2");
        assertThat(sent.get(1)).containsExactly("doc3", "doc4", "doc5");
        assertThat(sent.get(2)).containsExactly("doc6");
    }

    @Test
    public void test_reaching_the_limit_sends_the_whole_buffer() {
        buffer.add(List.of("a", "b"));
        buffer.add(List.of("c", "d"));

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).containsExactly("a", "b", "c", "d");
    }

    @Test
    public void test_flush_sends_the_rest_once() {
        buffer.add(List.of("a"));

        buffer.flush();
        buffer.flush();

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).containsExactly("a");
    }

    @Test
    public void test_flushing_nothing_sends_nothing() {
        buffer.flush();

        assertThat(sent).isEmpty();
    }

    @Test
    public void test_what_is_sent_does_not_change_when_the_buffer_is_reused() {
        buffer.add(List.of("a"));
        buffer.flush();
        buffer.add(List.of("b"));
        buffer.flush();

        assertThat(sent.get(0)).containsExactly("a");
        assertThat(sent.get(1)).containsExactly("b");
    }
}
