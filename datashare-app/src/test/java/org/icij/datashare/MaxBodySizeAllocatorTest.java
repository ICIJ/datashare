package org.icij.datashare;

import org.simpleframework.common.buffer.ArrayAllocator;
import org.simpleframework.common.buffer.Buffer;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class MaxBodySizeAllocatorTest {
    private final MaxBodySizeAllocator allocator = new MaxBodySizeAllocator(new ArrayAllocator(), 10);

    @Test
    public void test_refuses_a_declared_size_above_the_limit() {
        assertThrows(BodyTooLargeException.class, () -> allocator.allocate(11));
    }

    @Test
    public void test_accepts_a_declared_size_at_the_limit() throws Exception {
        Buffer buffer = allocator.allocate(10);
        buffer.append(new byte[10]);
        assertThat(buffer.length()).isEqualTo(10);
    }

    @Test
    public void test_refuses_appends_past_the_limit_when_no_size_is_declared() throws Exception {
        Buffer buffer = allocator.allocate();
        buffer.append(new byte[6]);
        assertThrows(BodyTooLargeException.class, () -> buffer.append(new byte[5]));
    }

    @Test
    public void test_counts_child_buffers_against_the_same_limit() throws Exception {
        Buffer body = allocator.allocate();
        Buffer firstPart = body.allocate();
        Buffer secondPart = body.allocate();
        firstPart.append(new byte[6]);
        assertThrows(BodyTooLargeException.class, () -> secondPart.append(new byte[5]));
    }

    @Test
    public void test_gives_each_body_its_own_limit() throws Exception {
        allocator.allocate().append(new byte[10]);
        Buffer nextBody = allocator.allocate();
        nextBody.append(new byte[10]);
        assertThat(nextBody.length()).isEqualTo(10);
    }
}
