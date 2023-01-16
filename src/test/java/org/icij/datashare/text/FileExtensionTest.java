package org.icij.datashare.text;

import org.junit.Test;

import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;

public class FileExtensionTest {
    @Test
    public void testReadValue() throws IOException {
        assertThat(FileExtension.get("application/pdf")).isEqualTo("pdf");
    }
}