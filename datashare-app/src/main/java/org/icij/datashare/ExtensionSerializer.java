package org.icij.datashare;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.nio.file.Path;

public class ExtensionSerializer extends JsonSerializer<Extension> {
    private Path extensionsDir;

    public ExtensionSerializer(Path extensionsDir) {
        this.extensionsDir = extensionsDir;
    }

    @Override
    public void serialize(Extension extension, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringField("id",extension.id);
        jsonGenerator.writeStringField("name",extension.name);
        jsonGenerator.writeStringField("version",extension.version);
        jsonGenerator.writeStringField("description",extension.description);
        jsonGenerator.writeStringField("url",extension.url.toString());
        jsonGenerator.writeStringField("type",extension.type.toString());
        jsonGenerator.writeBooleanField("installed",extension.isInstalled(extensionsDir));
        jsonGenerator.writeEndObject();
    }
}
