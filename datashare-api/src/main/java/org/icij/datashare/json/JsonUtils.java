package org.icij.datashare.json;

import java.io.IOException;


/**
 * Created by julien on 6/13/16.
 */
public class JsonUtils {

    public static boolean isValidJson(String json) {
        try {
            JsonObjectMapper.MAPPER.readTree(json);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

}
