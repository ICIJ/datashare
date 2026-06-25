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

/** The only class that reads/writes a document's manifest.json. Writes are concurrency-safe
 *  (in-JVM ReentrantLock + cross-process FileLock) and atomic (temp + ATOMIC_MOVE). */
public class ManifestStore {
    private static final String LOCK_FILE = ArtifactPath.MANIFEST_FILE + ".lock";
    private static final long LOCK_TIMEOUT_MS = 30_000;
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private static final TypeReference<LinkedHashMap<String, ManifestEntry>> MANIFEST_TYPE = new TypeReference<>() {};
    private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    public ManifestEntry get(Path nodeDir, String type) throws IOException {
        Path manifest = nodeDir.resolve(ArtifactPath.MANIFEST_FILE);
        if (!Files.exists(manifest)) {
            return null;
        }
        return read(manifest).get(type);
    }

    public void put(Path nodeDir, String type, ManifestEntry entry) throws IOException {
        Files.createDirectories(nodeDir);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(nodeDir.toAbsolutePath().toString(), k -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(nodeDir.resolve(LOCK_FILE), CREATE, WRITE);
             FileLock fileLock = acquire(channel)) {
            Path manifest = nodeDir.resolve(ArtifactPath.MANIFEST_FILE);
            Map<String, ManifestEntry> all = Files.exists(manifest) ? read(manifest) : new LinkedHashMap<>();
            all.put(type, entry);
            Path tmp = nodeDir.resolve(ArtifactPath.MANIFEST_FILE + ".tmp");
            Files.write(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(all));
            Files.move(tmp, manifest, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            jvmLock.unlock();
        }
    }

    private Map<String, ManifestEntry> read(Path manifest) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(manifest), MANIFEST_TYPE);
    }

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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted acquiring manifest lock", e);
            }
        }
    }
}
