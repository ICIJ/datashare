package org.icij.datashare.utils;

import static java.lang.System.getProperty;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class ProcessHandler {

    public static void dumpPid(File pidFile, Long pid) throws IOException {
        boolean ignored = pidFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(pidFile)) {
            fos.write(pid.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static boolean isProcessRunning(Path pidPath, int timeout, TimeUnit timeunit) throws IOException, InterruptedException {
        try (Stream<String> lines = Files.lines(pidPath)) {
            Long pid = Long.parseLong(
                lines.findFirst()
                    .orElseThrow(() -> new RuntimeException("PID file is empty"))
                    .strip()
            );
            return isProcessRunning(pid, timeout, timeunit);
        }
    }

    public static boolean isProcessRunning(Long pid, int timeout, TimeUnit timeunit) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        if (getProperty("os.name").toLowerCase().contains("windows")) {
            builder.command("ps", "-p", pid.toString());
        } else {
            builder.command("ps", "-p", pid.toString());
        }
        Process process = builder.start();
        process.waitFor(timeout, timeunit);
        return process.exitValue() == 0;
    }

    public static void killProcessById(Long pid) {
        killProcessById(pid, false);
    }

    public static void killProcessById(Long pid, boolean force) {
        Stream<ProcessHandle> liveProcesses = ProcessHandle.allProcesses();
        liveProcesses
            .filter(handle -> handle.isAlive() && pid.equals(handle.pid()))
            .findFirst()
            .ifPresent(parent -> {
                    parent.descendants()
                        .forEach(child -> {
                            if (force) {
                                child.destroyForcibly();
                            } else {
                                child.destroy();
                            }
                        });
                    if (force) {
                        parent.destroyForcibly();
                    } else {
                        parent.destroy();
                    }
                }
            );
    }

    public static List<Path> findPidPaths(String pattern, Path dir) throws IOException {
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher(pattern);
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(file -> !Files.isDirectory(file) && pathMatcher.matches(file.getFileName())).toList();
        }
    }
}
