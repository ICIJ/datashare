package org.icij.datashare;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;


import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

public class PathSerializer extends JsonSerializer<Path> {
    @Override
    public void serialize(Path path, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        jsonGenerator.writeString(path.toString() );

    }
}
