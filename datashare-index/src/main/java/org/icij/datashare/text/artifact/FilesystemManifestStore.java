package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Filesystem-backed manifest persistence. Writes are concurrency-safe (in-JVM ReentrantLock +
 *  cross-process FileLock) and atomic (temp + ATOMIC_MOVE). */
public class FilesystemManifestStore implements ManifestStore {
    private static final String LOCK_FILE = ArtifactPath.MANIFEST_FILE + ".lock";
    private static final long LOCK_TIMEOUT_MS = 30_000;
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private static final TypeReference<LinkedHashMap<String, ManifestEntry>> MANIFEST_TYPE = new TypeReference<>() {};
    private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    @Override
    public ManifestEntry get(Path docArtifactDir, String type) throws IOException {
        Path manifest = docArtifactDir.resolve(ArtifactPath.MANIFEST_FILE);
        // No manifest yet means nothing has been produced for this node; a present manifest
        // without this type's key means the same for that one type. Both read as "not found".
        if (!Files.exists(manifest)) {
            return null;
        }
        return read(manifest).get(type);
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

    // Read-modify-write a single type's entry, leaving every other type untouched.
    private void mergeEntryIntoManifest(Path docArtifactDir, String type, ManifestEntry entry) throws IOException {
        Path manifest = docArtifactDir.resolve(ArtifactPath.MANIFEST_FILE);
        Map<String, ManifestEntry> currentEntries = Files.exists(manifest) ? read(manifest) : new LinkedHashMap<>();
        currentEntries.put(type, entry);
        writeAtomically(manifest, currentEntries);
    }

    // Swap the manifest in via a temp file + atomic rename, so a concurrent reader never
    // observes a half-written file.
    private void writeAtomically(Path manifest, Map<String, ManifestEntry> entries) throws IOException {
        Path temporaryManifest = manifest.resolveSibling(ArtifactPath.MANIFEST_FILE + ".tmp");
        Files.write(temporaryManifest, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries));
        Files.move(temporaryManifest, manifest, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    private ReentrantLock lockFor(Path docArtifactDir) {
        return JVM_LOCKS.computeIfAbsent(docArtifactDir.toAbsolutePath().toString(), key -> new ReentrantLock());
    }

    private Map<String, ManifestEntry> read(Path manifest) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(manifest), MANIFEST_TYPE);
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
