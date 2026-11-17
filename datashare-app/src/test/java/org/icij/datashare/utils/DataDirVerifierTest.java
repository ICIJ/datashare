package org.icij.datashare.utils;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class DataDirVerifierTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Mock PropertiesProvider propertiesProvider;

    private DataDirVerifier dataDirVerifier;

    @Before
    public void setUp() {
        initMocks(this);
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

    @Test
    public void test_not_allowed_with_parent_traversal() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        assertFalse(dataDirVerifier.allowed(Paths.get("/path/to/data/../../etc")));
    }

    @Test
    public void test_allowed_with_traversal_staying_inside() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        assertTrue(dataDirVerifier.allowed(Paths.get("/path/to/data/a/../b")));
    }

    @Test
    public void test_not_allowed_with_a_link_leaving_the_data_dir() throws Exception {
        Path dataDir = Files.createDirectory(tmp.getRoot().toPath().resolve("data"));
        Path outside = Files.createDirectory(tmp.getRoot().toPath().resolve("outside"));
        Path link = Files.createSymbolicLink(dataDir.resolve("shared"), outside);
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of(dataDir.toString()));

        assertFalse(dataDirVerifier.allowed(link));
        assertFalse(dataDirVerifier.allowed(link.resolve("not-created-yet")));
        assertFalse(dataDirVerifier.allowed(Paths.get(link + "/../outside")));
    }

    @Test
    public void test_allowed_when_the_data_dir_is_reached_through_a_link() throws Exception {
        Path real = Files.createDirectory(tmp.getRoot().toPath().resolve("real"));
        Path link = Files.createSymbolicLink(tmp.getRoot().toPath().resolve("link"), real);
        Files.createDirectory(real.resolve("project"));
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of(link.toString()));

        assertTrue(dataDirVerifier.allowed(link.resolve("project")));
        assertTrue(dataDirVerifier.allowed(link.resolve("not-created-yet")));
        assertTrue(dataDirVerifier.allowed(real.resolve("project")));
    }

    @Test
    public void test_check_passes_inside_the_data_dir() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        dataDirVerifier.check(Paths.get("/path/to/data/subdirectory"));
    }

    @Test
    public void test_check_names_the_path_and_the_data_dir_when_it_refuses() {
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/path/to/data"));
        IllegalArgumentException refusal =
                assertThrows(IllegalArgumentException.class, () -> dataDirVerifier.check(Paths.get("/different/path")));
        assertEquals("/different/path must be inside the data directory /path/to/data", refusal.getMessage());
    }
}
