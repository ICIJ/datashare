package org.icij.datashare.text;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

import java.io.IOException;
import java.nio.file.Path;

public class PathSerializer extends StdScalarSerializer<Path> {
    public PathSerializer() { super(Path.class); }
    @Override
    public void serialize(Path path, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        jsonGenerator.writeString(path.toString());
    }
}
