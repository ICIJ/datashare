package org.icij.datashare.utils;

import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class DataDirVerifierTest {
    @Mock PropertiesProvider propertiesProvider;

    private DataDirVerifier dataDirVerifier;

    @Before
    public void setUp() {
        openMocks(this);
        dataDirVerifier = new DataDirVerifier(propertiesProvider);
    }

    @Test
    public void test_value() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        String value = dataDirVerifier.value();
        assertEquals("/path/to/data", value);
    }

    @Test
    public void test_path() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        Path path = dataDirVerifier.path();
        assertEquals(Paths.get("/path/to/data"), path);
    }

    @Test
    public void test_allowed_with_equal_path() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        Path path = Paths.get("/path/to/data");
        assertTrue(dataDirVerifier.allowed(path));
    }

    @Test
    public void test_allowed_with_sub_directory_path() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        Path path = Paths.get("/path/to/data/subdirectory");
        assertTrue(dataDirVerifier.allowed(path));
    }

    @Test
    public void test_allowed_with_different_path() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        Path path = Paths.get("/different/path");
        assertFalse(dataDirVerifier.allowed(path));
    }
}
