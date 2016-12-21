package org.icij.datashare.util.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Created by julien on 6/13/16.
 */
public class JsonUtils {

    public static boolean isValidJson(String json) {
        try {
            new ObjectMapper().readTree(json);

        } catch (JsonProcessingException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
        return true;
    }

}
