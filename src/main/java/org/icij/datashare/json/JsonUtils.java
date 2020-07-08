package org.icij.datashare.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


public class JsonUtils {

    public static boolean isValidJson(String json) {
        try {
            JsonObjectMapper.MAPPER.readTree(json);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public static Map<String, Object> deserialize(String jsonMap) {
        try {
            return new ObjectMapper().readValue(jsonMap, new TypeReference<HashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String serialize(Map<String, Object> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
