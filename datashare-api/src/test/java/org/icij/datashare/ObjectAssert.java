package org.icij.datashare;
import org.fest.assertions.GenericAssert;


import java.lang.reflect.Method;
import java.util.Objects;

public class ObjectAssert<T> extends GenericAssert<ObjectAssert<T>, T> {

    @SuppressWarnings("unchecked")
    ObjectAssert(T actual) {
        super((Class<ObjectAssert<T>>) (Object) ObjectAssert.class, actual);
    }

    public static <T> ObjectAssert<T> assertThat(T actual) {
        return new ObjectAssert<>(actual);
    }

    public ObjectAssert<T> isEqualByGetters(T expected) {
        if (actual == null || expected == null) {
            fail("Neither actual nor expected object can be null");
        }

        Class<?> clazz = actual.getClass();
        if (!clazz.equals(expected.getClass())) {
            fail("Objects must be of the same class");
        }

        for (Method method : clazz.getMethods()) {
            if (isGetter(method)) {
                try {
                    Object actualValue = method.invoke(actual);
                    Object expectedValue = method.invoke(expected);
                    if (!Objects.equals(actualValue, expectedValue)) {
                        fail(String.format("Mismatch in getter <%s>: expected <%s> but was <%s>",
                                method.getName(), expectedValue, actualValue));
                    }
                } catch (Exception e) {
                    fail(String.format("Failed to invoke getter <%s>: %s", method.getName(), e.getMessage()));
                }
            }
        }

        return this;
    }

    private boolean isGetter(Method method) {
        return method.getName().startsWith("get")
                && method.getParameterCount() == 0
                && !void.class.equals(method.getReturnType());
    }
}