package org.icij.datashare.extract;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.fest.assertions.Assertions.assertThat;

public class ScannerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void test_scan_calls_callback_for_each_file() throws Exception {
        ScanOptions options = ScanOptions.defaultValues();
        Files.createFile(folder.getRoot().toPath().resolve("foo.txt"));
        Files.createFile(folder.getRoot().toPath().resolve("bar.txt"));
        Path subdir = Files.createDirectory(folder.getRoot().toPath().resolve("subdir"));
        Files.createFile(subdir.resolve("baz.txt"));

        Set<Path> visited = new HashSet<>();
        Scanner scanner = new Scanner(options, visited::add);

        Long scanned = scanner.scan(folder.getRoot().toPath());

        assertThat(scanned).isEqualTo(3L);
        assertThat(visited).hasSize(3);
        assertThat(visited).contains(
                folder.getRoot().toPath().resolve("foo.txt"),
                folder.getRoot().toPath().resolve("bar.txt"),
                subdir.resolve("baz.txt"));
    }

    @Test
    public void test_include_pattern_filters_files() throws Exception {
        ScanOptions options = ScanOptions.builder().includePattern("**/*.txt").build();
        Files.createFile(folder.getRoot().toPath().resolve("foo.txt"));
        Files.createFile(folder.getRoot().toPath().resolve("bar.pdf"));

        Set<Path> visited = new HashSet<>();
        Scanner scanner = new Scanner(options, visited::add);

        Long scanned = scanner.scan(folder.getRoot().toPath());

        assertThat(scanned).isEqualTo(1L);
        assertThat(visited).containsOnly(folder.getRoot().toPath().resolve("foo.txt"));
    }

    @Test
    public void test_exclude_pattern_filters_files() throws Exception {
        ScanOptions options = ScanOptions.builder().excludePattern("**/*.pdf").build();
        Files.createFile(folder.getRoot().toPath().resolve("foo.txt"));
        Files.createFile(folder.getRoot().toPath().resolve("bar.pdf"));

        Set<Path> visited = new HashSet<>();
        Scanner scanner = new Scanner(options, visited::add);

        Long scanned = scanner.scan(folder.getRoot().toPath());

        assertThat(scanned).isEqualTo(1L);
        assertThat(visited).containsOnly(folder.getRoot().toPath().resolve("foo.txt"));
    }

    @Test
    public void test_ignore_hidden_files_true_excludes_hidden_files() throws Exception {
        ScanOptions options = ScanOptions.builder().includeHiddenFiles(false).build();

        Files.createFile(folder.getRoot().toPath().resolve("foo.txt"));
        Files.createFile(folder.getRoot().toPath().resolve(".hidden.txt"));

        Set<Path> visited = new HashSet<>();
        Scanner scanner = new Scanner(options, visited::add);

        Long scanned = scanner.scan(folder.getRoot().toPath());

        assertThat(scanned).isEqualTo(1L);
        assertThat(visited).containsOnly(folder.getRoot().toPath().resolve("foo.txt"));
    }

    @Test
    public void test_max_depth_limits_recursion() throws Exception {
        ScanOptions options = ScanOptions.builder().maxDepth(1).build();

        Files.createFile(folder.getRoot().toPath().resolve("foo.txt"));
        Path subdir = Files.createDirectory(folder.getRoot().toPath().resolve("subdir"));
        Files.createFile(subdir.resolve("nested.txt"));

        Set<Path> visited = new HashSet<>();
        Scanner scanner = new Scanner(options, visited::add);

        Long scanned = scanner.scan(folder.getRoot().toPath());

        assertThat(scanned).isEqualTo(1L);
        assertThat(visited).containsOnly(folder.getRoot().toPath().resolve("foo.txt"));
    }

    @Test
    public void test_follow_sym_links_true_scans_symlink_targets() throws Exception {
        ScanOptions options = ScanOptions.builder().followSymlinks(true).build();

        Path real = Files.createFile(folder.getRoot().toPath().resolve("real.txt"));
        Path link = folder.getRoot().toPath().resolve("link.txt");
        Files.createSymbolicLink(link, real);

        Set<Path> visited = new HashSet<>();
        Scanner scanner = new Scanner(options,visited::add);

        Long scanned = scanner.scan(folder.getRoot().toPath());

        assertThat(scanned).isEqualTo(2L);
        assertThat(visited).contains(real, link);
    }

    @Test(expected = IOException.class)
    public void test_callback_exception_stops_scan_and_propagates() throws Exception {
        ScanOptions options = ScanOptions.defaultValues();

        Files.createFile(folder.getRoot().toPath().resolve("a.txt"));
        Files.createFile(folder.getRoot().toPath().resolve("b.txt"));

        AtomicInteger calls = new AtomicInteger(0);
        Scanner scanner = new Scanner(options,path -> {
            calls.incrementAndGet();
            throw new IOException("boom");
        });

        try {
            scanner.scan(folder.getRoot().toPath());
        } finally {
            assertThat(calls.get()).isEqualTo(1);
        }
    }
}
