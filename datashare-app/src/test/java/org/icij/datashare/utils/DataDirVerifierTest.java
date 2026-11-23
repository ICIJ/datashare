package org.icij.datashare.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DataDirVerifierTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void test_value() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        Path value = dataDirVerifier.value();
        assertEquals("/path/to/data", value.toString());
    }

    @Test
    public void test_allowed_with_equal_path() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        Path path = Paths.get("/path/to/data");
        assertTrue(dataDirVerifier.allowed(path));
    }

    @Test
    public void test_allowed_with_sub_directory_path() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        Path path = Paths.get("/path/to/data/subdirectory");
        assertTrue(dataDirVerifier.allowed(path));
    }

    @Test
    public void test_allowed_with_different_path() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        Path path = Paths.get("/different/path");
        assertFalse(dataDirVerifier.allowed(path));
    }

    @Test
    public void test_not_allowed_with_parent_traversal() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        assertFalse(dataDirVerifier.allowed(Paths.get("/path/to/data/../../etc")));
    }

    @Test
    public void test_allowed_with_traversal_staying_inside() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        assertTrue(dataDirVerifier.allowed(Paths.get("/path/to/data/a/../b")));
    }

    @Test
    public void test_not_allowed_with_a_link_leaving_the_data_dir() throws Exception {
        Path dataDir = Files.createDirectory(tmp.getRoot().toPath().resolve("data"));
        Path outside = Files.createDirectory(tmp.getRoot().toPath().resolve("outside"));
        Path link = Files.createSymbolicLink(dataDir.resolve("shared"), outside);
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", dataDir.toString())));

        assertFalse(dataDirVerifier.allowed(link));
        assertFalse(dataDirVerifier.allowed(link.resolve("not-created-yet")));
        assertFalse(dataDirVerifier.allowed(Paths.get(link + "/../outside")));
    }

    @Test
    public void test_allowed_when_the_data_dir_is_reached_through_a_link() throws Exception {
        Path real = Files.createDirectory(tmp.getRoot().toPath().resolve("real"));
        Path link = Files.createSymbolicLink(tmp.getRoot().toPath().resolve("link"), real);
        Files.createDirectory(real.resolve("project"));
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", link.toString())));

        assertTrue(dataDirVerifier.allowed(link.resolve("project")));
        assertTrue(dataDirVerifier.allowed(link.resolve("not-created-yet")));
        assertTrue(dataDirVerifier.allowed(real.resolve("project")));
    }

    @Test
    public void test_check_passes_inside_the_data_dir() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        dataDirVerifier.check(Paths.get("/path/to/data/subdirectory"));
    }

    @Test
    public void test_check_names_the_path_and_the_data_dir_when_it_refuses() {
        DataDirVerifier dataDirVerifier =
                DataDirVerifier.from(new PropertiesProvider(Map.of("dataDir", "/path/to/data")));
        IllegalArgumentException refusal =
                assertThrows(IllegalArgumentException.class, () -> dataDirVerifier.check(Paths.get("/different/path")));
        assertEquals("/different/path must be inside the data directory /path/to/data", refusal.getMessage());
    }
}
