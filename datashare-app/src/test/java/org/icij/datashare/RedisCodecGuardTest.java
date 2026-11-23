package org.icij.datashare;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class RedisCodecGuardTest {
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(JsonJacksonCodec|SerializationCodec|MarshallingCodec|Kryo5?Codec|FstCodec)"
            + "|\\.get(Topic|ReliableTopic|ShardedTopic|PatternTopic|Map|MapCache|Bucket|Queue|BlockingQueue|Deque|List|Set|SetCache)\\(\\s*[^,]*?\\)");

    @Test
    public void main_sources_have_no_violations() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> scanned = new ArrayList<>();
        Path root = Paths.get("..").toAbsolutePath().normalize();
        List<Path> sourceDirs = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            List<Path> modules = children.collect(Collectors.toList());
            for (Path module : modules) {
                Path sourceDir = module.resolve("src/main/java");
                if (Files.isDirectory(sourceDir)) {
                    sourceDirs.add(sourceDir);
                }
            }
        }
        for (Path sourceDir : sourceDirs) {
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(sourceDir)) {
                javaFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            }
            for (Path file : javaFiles) {
                Path relative = root.relativize(file);
                scanned.add(relative.toString());
                String content = Files.readString(file);
                String collapsed = content.replaceAll("\\s+", " ");
                Matcher matcher = FORBIDDEN.matcher(collapsed);
                while (matcher.find()) {
                    found.add(relative + ": " + matcher.group());
                }
            }
        }
        assertThat(scanned).contains("datashare-tasks/src/main/java/org/icij/datashare/asynctasks/TaskManagerRedis.java");
        assertThat(found).isEmpty();
    }
}
