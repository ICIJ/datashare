package org.icij.datashare.utils;

import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public class JsonUtils {
    public static Map nodeToMap(ObjectNode node) {
        return new ObjectMapper().convertValue(node, Map.class);
    }
}
