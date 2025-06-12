package org.icij.datashare.text;

import me.xuender.unidecode.Unidecode;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public class StringUtils {
    public static String normalize(String unicoded) {
        return Unidecode.decode(unicoded).trim().replaceAll("(\\s+)", " ").toLowerCase();
    }

    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    public static Object getValue(Object root, String dottedKey) {
        if (root == null || isEmpty(dottedKey)) {
            return null;
        }

        String[] keys = dottedKey.split("\\.");
        Object current = root;

        for (String key : keys) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                current = getBeanPropertyOrField(current, key);
            }
        }

        return current;
    }

    private static Object getBeanPropertyOrField(Object bean, String propertyName) {
        // 1) Try standard JavaBean getter
        try {
            for (PropertyDescriptor pd : Introspector.getBeanInfo(bean.getClass()).getPropertyDescriptors()) {
                if (pd.getName().equals(propertyName) && pd.getReadMethod() != null) {
                    Method getter = pd.getReadMethod();
                    return getter.invoke(bean);
                }
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException ignored) { }

        // 2) Fallback: public field only (no private)
        try {
            Field field = bean.getClass().getField(propertyName);
            return field.get(bean);
        } catch (NoSuchFieldException | IllegalAccessException ignored) { }

        // not found
        return null;
    }
}
