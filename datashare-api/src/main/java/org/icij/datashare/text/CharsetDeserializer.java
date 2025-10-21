package org.icij.datashare.text;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class CharsetDeserializer extends JsonDeserializer<Charset> {
    @Override
    public Charset deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        JsonToken currentToken = jsonParser.getCurrentToken();

        if (currentToken.equals(JsonToken.VALUE_STRING)) {
            String text = jsonParser.getText().trim();
            if ("unknown".equalsIgnoreCase(text)) {
                return getDefault();
            }
            return Charset.forName(text);
        }
        return getDefault();
    }

    private Charset getDefault() { return StandardCharsets.US_ASCII;}
}
