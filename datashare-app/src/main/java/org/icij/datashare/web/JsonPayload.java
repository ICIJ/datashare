package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class JsonPayload extends Payload {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonPayload.class);

    private JsonPayload(int code, Object content) {
        this(code, toJson(content));
    }

    public JsonPayload(int code, Record content) {
        this(code, (Object) content);
    }

    public JsonPayload(int code, Map<String, Object> content) {
        this(code, (Object) content);
    }

    public JsonPayload(Record content) {
        this(200, content);
    }

    public JsonPayload(Map<String, Object> content) {
        this(200, content);
    }

    public JsonPayload(int code) {
        this(code, "{}");
    }

    private JsonPayload(int code, String content) {
        super("application/json", content, code);
    }

    private static String toJson(Object content) {
        try {
            if (content == null) return "{}";
            return JsonObjectMapper.MAPPER.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            LOGGER.error("error serializing {}, returning empty object", content, e);
            return "{}";
        }
    }
}
