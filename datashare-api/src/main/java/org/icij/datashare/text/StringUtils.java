package org.icij.datashare.text;

import me.xuender.unidecode.Unidecode;

import java.util.List;
import java.util.Map;

public class StringUtils {
    public static String normalize(String unicoded) {
        return Unidecode.decode(unicoded).trim().replaceAll("(\\s+)", " ").toLowerCase();
    }

    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    public static Object getValue(Map<String, Object> map, String dottedKey) {
        List<String> jsonKeys = List.of(dottedKey.split("\\."));
        Map<String, Object> node = map;
        for (String key: jsonKeys)  {
            Object o = node.get(key);
            if (o instanceof Map<?,?>) {
                node = (Map<String, Object>) o;
            } else if (jsonKeys.indexOf(key) == jsonKeys.size() -1) {
                return o;
            }
        }
        return null;
    }
}
