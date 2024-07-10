package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.LoggerFactory;

public class JsonPayload extends Payload {
    public JsonPayload(int code, Record content) {
        this(code, getJson(content));
    }

    public JsonPayload(Record content) {
        this(200, getJson(content));
    }

    public JsonPayload(int code) {
        this(code, "{}");
    }

    private JsonPayload(int code, String content) {
        super("application/json", content, code);
    }

    private static String getJson(Record content) {
        try {
            return JsonObjectMapper.MAPPER.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            // with record this should never happen but just in case we log
            LoggerFactory.getLogger(JsonPayload.class).error(String.format("error serializing %s, returning empty object", content), e);
            return "{}";
        }
    }
}
