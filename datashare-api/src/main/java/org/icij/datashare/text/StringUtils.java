package org.icij.datashare.text;

import me.xuender.unidecode.Unidecode;

public class StringUtils {
    public static String normalize(String unicoded) {
        return Unidecode.decode(unicoded).trim().replaceAll("(\\s+)", " ").toLowerCase();
    }
}
