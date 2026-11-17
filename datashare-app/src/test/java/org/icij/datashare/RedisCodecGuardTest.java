package org.icij.datashare;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class RedisCodecGuardTest {
    private static final Pattern FORBIDDEN = Pattern.compile("JsonJacksonCodec|getTopic\\(\\s*[^,()]+\\)");

    @Test
    public void main_sources_have_no_violations() throws IOException {
        List<String> found = new ArrayList<>();
        Path modulesRoot = Paths.get("..").toAbsolutePath().normalize();
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(modulesRoot)) {
            javaFiles = walk.filter(p -> p.toString().contains("/src/main/java/") && p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                    Path relative = modulesRoot.relativize(file);
                    found.add(relative + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
