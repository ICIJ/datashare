package org.icij.datashare.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class AtomicDirectorySwapTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private Path parent() {
        return dir.getRoot().toPath();
    }

    private Path target() {
        return parent().resolve("payload");
    }

    private void replaceWith(String... names) throws IOException {
        AtomicDirectorySwap.replace(target(), staging -> {
            for (String name : names) {
                Files.writeString(staging.resolve(name), "new " + name);
            }
        });
    }

    private List<String> targetContents() throws IOException {
        try (Stream<Path> entries = Files.list(target())) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    // Every temporary of this helper is a dotted sibling of the target named after it.
    private List<Path> temporaries() throws IOException {
        try (Stream<Path> entries = Files.list(parent())) {
            return entries.filter(entry -> entry.getFileName().toString().startsWith(".payload-")).toList();
        }
    }

    @Test
    public void test_writes_the_new_contents_when_nothing_is_there_yet() throws Exception {
        replaceWith("one.txt");

        assertThat(Files.readString(target().resolve("one.txt"))).isEqualTo("new one.txt");
    }

    @Test
    public void test_creates_the_parent_the_staging_directory_needs() throws Exception {
        // The first artifact of a document has no dir to write into yet, so the helper makes it rather
        // than handing every caller a NoSuchFileException on a fresh artifactDir.
        Path nested = parent().resolve("a-document").resolve("payload");

        AtomicDirectorySwap.replace(nested, staging -> Files.writeString(staging.resolve("one.txt"), "new"));

        assertThat(Files.readString(nested.resolve("one.txt"))).isEqualTo("new");
    }

    @Test
    public void test_the_target_holds_only_what_the_writer_wrote() throws Exception {
        // A shorter payload than last time must not leave the previous run's extra files behind.
        Files.createDirectories(target());
        Files.writeString(target().resolve("stale.txt"), "from a previous, longer run");

        replaceWith("one.txt");

        assertThat(targetContents()).isEqualTo(List.of("one.txt"));
    }

    @Test
    public void test_replaces_a_plain_file_sitting_at_the_target_path() throws Exception {
        // Another producer can own the same name as a single file (docling writes a flat structure file).
        Files.writeString(target(), "a flat file, not a directory");

        replaceWith("one.txt");

        assertThat(Files.isDirectory(target())).isTrue();
        assertThat(targetContents()).isEqualTo(List.of("one.txt"));
    }

    @Test
    public void test_leaves_siblings_of_the_target_alone() throws Exception {
        Files.writeString(parent().resolve("raw"), "raw bytes");

        replaceWith("one.txt");

        assertThat(Files.readString(parent().resolve("raw"))).isEqualTo("raw bytes");
    }

    @Test
    public void test_leaves_no_temporary_behind_after_a_successful_replace() throws Exception {
        Files.createDirectories(target());
        Files.writeString(target().resolve("old.txt"), "the contents being replaced");

        replaceWith("one.txt");

        assertThat(temporaries()).isEmpty();
    }

    @Test
    public void test_reclaims_a_holding_pen_a_previous_run_could_not_delete() throws Exception {
        // The pen is named uniquely per invocation, so nothing else would reclaim one a failed delete left
        // behind: the target would cost a full extra copy on every rewrite.
        Path leftover = parent().resolve(".payload-0dd0dd.replaced");
        Files.createDirectories(leftover);
        Files.writeString(leftover.resolve("one.txt"), "contents nothing reads");

        replaceWith("one.txt");

        assertThat(temporaries()).isEmpty();
        assertThat(Files.readString(target().resolve("one.txt"))).isEqualTo("new one.txt");
    }

    @Test
    public void test_swaps_in_the_new_contents_when_the_previous_ones_cannot_be_deleted() throws Exception {
        // A nested, separately-locked subdir makes the previous contents undeletable while the target itself
        // stays writable, ruling out a pass that only means the new file could not be written. Relies on the
        // test process not running as root, which ignores the unwritable parent (CI and the devenv are both
        // non-root).
        Path lockedSubdir = target().resolve("locked");
        Files.createDirectories(lockedSubdir);
        Files.writeString(target().resolve("old.txt"), "old contents");
        Files.writeString(lockedSubdir.resolve("leftover.txt"), "stale file from a previous run");
        // Deleting a directory entry needs write permission on its parent, not on the entry itself.
        lockedSubdir.toFile().setWritable(false);

        try {
            replaceWith("one.txt");

            // The previous contents are renamed aside, not deleted, so an undeletable leftover cannot fail
            // the caller.
            assertThat(targetContents()).isEqualTo(List.of("one.txt"));
        } finally {
            // Not lockedSubdir: replace() renamed the directory holding it aside, so a chmod of the original
            // path silently does nothing and leaves a tree TemporaryFolder cannot delete.
            restoreWritePermissions(parent());
        }
    }

    @Test
    public void test_keeps_the_previous_contents_when_the_new_ones_cannot_be_written() throws Exception {
        Files.createDirectories(target());
        Files.writeString(target().resolve("old.txt"), "old contents");
        // An unwritable parent: no staging dir can be created in it, so replace() fails before it touches
        // what is already there.
        dir.getRoot().setWritable(false);

        try {
            replaceWith("one.txt");
            org.junit.Assert.fail("expected an IOException");
        } catch (IOException expected) {
            // the new contents never made it to disk: the previous ones must survive untouched
        } finally {
            dir.getRoot().setWritable(true);
        }
        assertThat(Files.readString(target().resolve("old.txt"))).isEqualTo("old contents");
    }

    @Test
    public void test_no_staging_directory_survives_an_error_while_writing() throws Exception {
        // An OutOfMemoryError mid-write is a real failure mode for big payloads, and the staging name is
        // unique per invocation: what it leaves behind would never be reclaimed by anything.
        try {
            AtomicDirectorySwap.replace(target(), staging -> {
                throw new OutOfMemoryError("boom");
            });
            org.junit.Assert.fail("expected the error to propagate");
        } catch (OutOfMemoryError expected) {
            // the cause the operator needs to see must not be replaced by a cleanup failure
        }
        assertThat(temporaries()).isEmpty();
        assertThat(Files.exists(target())).isFalse();
    }

    @Test
    public void test_the_target_is_as_readable_as_the_directories_around_it() throws Exception {
        // The staging directory is renamed into place, so its mode is the mode the target ships with, and on
        // a shared parent an owner-only directory is EACCES for every other uid.
        replaceWith("one.txt");

        Path createdThePlainWay = Files.createDirectory(parent().resolve("control"));
        assertThat(modeOf(target())).isEqualTo(modeOf(createdThePlainWay));
    }

    @Test
    public void test_discard_removes_a_tree_and_says_nothing_when_there_is_none() throws Exception {
        replaceWith("one.txt");

        AtomicDirectorySwap.discard(target());
        AtomicDirectorySwap.discard(parent().resolve("never-existed"));

        assertThat(Files.exists(target())).isFalse();
    }

    private static String modeOf(Path directory) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(directory));
    }

    private static void restoreWritePermissions(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            entries.filter(Files::isDirectory).forEach(entry -> entry.toFile().setWritable(true));
        }
    }
}
