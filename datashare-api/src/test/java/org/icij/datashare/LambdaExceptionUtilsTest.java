package org.icij.datashare;

import org.junit.Test;

import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class LambdaExceptionUtilsTest {
    @Test(expected = MyTestException.class)
    public void testConsumer() throws MyTestException {
        rethrowConsumer(this::checkValue).accept(null);
    }

    private void checkValue(String value) throws MyTestException {
        if(value==null) {
            throw new MyTestException();
        }
    }

    static class MyTestException extends Exception { }

    @Test
    public void testConsumerRaisingExceptionInTheMiddle() {
        MyLongAccumulator accumulator = new MyLongAccumulator();
        try {
            Stream.of(2L, 3L, 4L, null, 5L).forEach(rethrowConsumer(accumulator::add));
            fail();
        } catch (MyTestException e) {
            assertEquals(9L, accumulator.acc);
        }
    }

    private class MyLongAccumulator {
        private long acc = 0;
        public void add(Long value) throws MyTestException {
            if(value==null) {
                throw new MyTestException();
            }
            acc += value;
        }
    }

    @Test
    public void testFunction() throws MyTestException {
        List<Integer> sizes = Stream.of("ciao", "hello").<Integer>map(rethrowFunction(s -> transform(s))).collect(toList());
        assertEquals(2, sizes.size());
        assertEquals(4, sizes.get(0).intValue());
        assertEquals(5, sizes.get(1).intValue());
    }

    private Integer transform(String value) throws MyTestException {
        if(value==null) {
            throw new MyTestException();
        }
        return value.length();
    }

    @Test(expected = MyTestException.class)
    public void testFunctionRaisingException() throws MyTestException {
        Stream.of("ciao", null, "hello").<Integer>map(rethrowFunction(this::transform)).toList();
    }
}