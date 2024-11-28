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

    public static boolean isProcessRunning(Path pidPath, int timeout, TimeUnit timeunit) {
        try (Stream<String> lines = Files.lines(pidPath)) {
            Long pid = Long.parseLong(
                lines.findFirst()
                    .orElseThrow(() -> new RuntimeException("PID file is empty"))
                    .strip()
            );
            return isProcessRunning(pid, timeout, timeunit);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PID file", e);
        }
    }

    public static boolean isProcessRunning(Long pid, int timeout, TimeUnit timeunit) {
        ProcessBuilder builder = new ProcessBuilder();
        if (getProperty("os.name").toLowerCase().contains("windows")) {
            builder.command("ps", "-p", pid.toString());
        } else {
            builder.command("ps", "-p", pid.toString());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build ps command", e);
        }
        try {
            process.waitFor(timeout, timeunit);
        } catch (InterruptedException e) {
            throw new RuntimeException(
                "Failed to execute ps command for process "
                    + pid
                    + " in less than "
                    + timeout
                    + timeunit.toString(), e);
        }
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

    public static List<Path> findPidPaths(String pattern, Path dir) {
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher(pattern);
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(file -> !Files.isDirectory(file) && pathMatcher.matches(file.getFileName())).toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list files in " + pattern, e);
        }
    }
}
