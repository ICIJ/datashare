package org.icij.datashare;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Path;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;

public class ExtensionSerializerTest {
    @Rule public TemporaryFolder extensionsDir = new TemporaryFolder();

    @Test
    public void test_serialize() throws Exception{
        ExtensionSerializer extensionSerializer = new ExtensionSerializer(extensionsDir.getRoot().toPath());
        extensionsDir.newFile("extension-1.0.0.jar");
        URL extensionPath = extensionsDir.getRoot().toPath().resolve("extension-1.0.0.jar").toUri().toURL();
        Extension extension = new Extension(extensionPath);
        Writer jsonWriter = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(jsonWriter);
        extensionSerializer.serialize(extension, jsonGenerator, new ObjectMapper().getSerializerProvider());
        jsonGenerator.flush();
        assertThat(jsonWriter.toString()).isEqualTo(
                format("{\"id\":\"extension\",\"name\":extension,\"version\":\"1.0.0\",\"description\":null,\"url\":\"%s\",\"type\":\"UNKNOWN\",\"installed\":true}",extensionPath));
    }

    @Test
    public void test_with_null_extension_dir() throws Exception{
        URL extensionPath = extensionsDir.getRoot().toPath().resolve("extension-1.0.0.jar").toUri().toURL();
        Extension extension = new Extension(extensionPath);
        Writer jsonWriter = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(jsonWriter);
        new ExtensionSerializer((Path)null).serialize(extension, jsonGenerator, new ObjectMapper().getSerializerProvider());
        jsonGenerator.flush();
        assertThat(jsonWriter.toString()).isEqualTo(
                format("{\"id\":\"extension\",\"name\":extension,\"version\":\"1.0.0\",\"description\":null,\"url\":\"%s\",\"type\":\"UNKNOWN\",\"installed\":false}",extensionPath));
    }
}