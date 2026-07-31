package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.MapMaker;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Filesystem-backed manifest persistence. Writes are concurrency-safe (in-JVM ReentrantLock +
 *  cross-process FileLock) and atomic (temp + ATOMIC_MOVE). */
public class FilesystemManifestRepository implements ManifestRepository {
    private static final String LOCK_FILE = ArtifactPath.MANIFEST_FILE + ".lock";
    private static final long LOCK_TIMEOUT_MS = 30_000;
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    // Weak values, because this is keyed per document and hit once per indexed document: a strong map
    // would keep one entry per document ever processed for the life of the JVM. A lock a thread holds
    // is kept alive by that thread's own reference to it, so one dir still maps to one lock for as long
    // as it is in use, which is what the FileLock non-overlap guarantee below relies on.
    private static final ConcurrentMap<String, ReentrantLock> JVM_LOCKS = new MapMaker().weakValues().makeMap();

    @Override
    public ManifestEntry get(Path docArtifactDir, String type) throws IOException {
        Path manifest = docArtifactDir.resolve(ArtifactPath.MANIFEST_FILE);
        // No manifest yet means nothing has been produced for this node; a present manifest
        // without this type's key means the same for that one type. Both read as "not found".
        if (!Files.exists(manifest)) {
            return null;
        }
        JsonNode entry = read(manifest).get(type);
        return entry == null ? null : MAPPER.treeToValue(entry, ManifestEntry.class);
    }

    @Override
    public void put(Path docArtifactDir, String type, ManifestEntry entry) throws IOException {
        inLock(docArtifactDir, () -> {
            mergeEntryIntoManifest(docArtifactDir, type, entry);
            return null;
        });
    }

    @Override
    public <T> T inLock(Path docArtifactDir, ManifestAction<T> action) throws IOException {
        Files.createDirectories(docArtifactDir);
        // Serialise writers within this JVM first: a FileLock is owned per-JVM (not per-thread),
        // so two threads sharing the channel would otherwise collide on the same lock region.
        ReentrantLock jvmLock = lockFor(docArtifactDir);
        jvmLock.lock();
        try {
            // The ReentrantLock is reentrant but a FileLock is not: a second tryLock() on the same
            // region within one JVM throws OverlappingFileLockException. When we already hold the
            // JVM lock (hold count > 1) the cross-process FileLock is already held by this thread's
            // outer inLock, so the nested call runs the action without re-acquiring it.
            if (jvmLock.getHoldCount() > 1) {
                return action.run();
            }
            try (FileChannel channel = FileChannel.open(docArtifactDir.resolve(LOCK_FILE), CREATE, WRITE);
                 FileLock fileLock = acquire(channel)) {
                // Cross-process safety: across hosts sharing the artifactDir, only one writer
                // mutates the manifest at a time while this file lock is held.
                return action.run();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    // Read-modify-write a single type's entry, leaving every other type untouched. At tree level rather
    // than through Map<String, ManifestEntry>: datashare-python owns other types in this file and writes
    // fields this record does not model, which a round-trip through ManifestEntry would destroy (the byte
    // offsets of a docling payload are the only copy there is).
    private void mergeEntryIntoManifest(Path docArtifactDir, String type, ManifestEntry entry) throws IOException {
        Path manifest = docArtifactDir.resolve(ArtifactPath.MANIFEST_FILE);
        ObjectNode currentEntries = Files.exists(manifest) ? read(manifest) : MAPPER.createObjectNode();
        currentEntries.set(type, MAPPER.valueToTree(entry));
        writeAtomically(manifest, currentEntries);
    }

    // Swap the manifest in via a temp file + atomic rename, so a concurrent reader never
    // observes a half-written file.
    private void writeAtomically(Path manifest, ObjectNode entries) throws IOException {
        Path temporaryManifest = manifest.resolveSibling(ArtifactPath.MANIFEST_FILE + ".tmp");
        Files.write(temporaryManifest, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries));
        Files.move(temporaryManifest, manifest, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    private ReentrantLock lockFor(Path docArtifactDir) {
        return JVM_LOCKS.computeIfAbsent(docArtifactDir.toAbsolutePath().toString(), key -> new ReentrantLock());
    }

    private ObjectNode read(Path manifest) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(manifest));
        if (!root.isObject()) {
            throw new IOException(manifest + " is not a JSON object");
        }
        return (ObjectNode) root;
    }

    // Spin-retry rather than block forever: a stale cross-process lock (e.g. a crashed peer)
    // must not hang the worker indefinitely, so give up after LOCK_TIMEOUT_MS.
    private FileLock acquire(FileChannel channel) throws IOException {
        long deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MS;
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("timed out acquiring manifest lock");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted acquiring manifest lock", interruption);
            }
        }
    }
}
