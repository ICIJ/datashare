package org.icij.datashare.utils;

import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

public class JsonUtils {
    public static Map<String, Object> nodeToMap(ObjectNode node) {
        return new ObjectMapper().convertValue(node, new TypeReference<>() {});
    }
    public static Map<String, JsonData> mapObjectTomapJsonData(Map<String,Object> map) {
        HashMap<String, JsonData> retMap = new HashMap<String, JsonData>();
        map.entrySet().forEach(entry -> retMap.put(entry.getKey(), JsonData.of(entry.getValue())));
        return retMap;
    }
}
