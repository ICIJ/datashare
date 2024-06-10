package org.icij.datashare.text;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathDeserializer extends StdScalarDeserializer<Path> {
    public PathDeserializer() { super(Path.class); }
    @Override
    public Path deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        return Paths.get(jsonParser.getText().trim());
    }
}
