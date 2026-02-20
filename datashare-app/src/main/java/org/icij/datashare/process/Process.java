package org.icij.datashare.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Class to launch a process originally from
 * cf <a href="https://github.com/owenthereal/gaffer">owenthereal/gaffer</a>
 */
public class Process {
    private final class StdoutRunnable implements Runnable {
        private final InputStream src;

        private StdoutRunnable(final InputStream src) {
            this.src = src;
        }

        @Override
        public void run() {
            final Scanner sc = new Scanner(src);
            try {
                while (sc.hasNextLine()) {
                    logger.info(sc.nextLine());
                }
            } finally {
                sc.close();
            }
        }
    }

    private final String dir;
    private final String name;
    private final String[] cmd;

    private java.lang.Process p;
    private final Logger logger;
    private final ExecutorService pool;
    private final Map<String, String> envVars;

    public Process(final String dir, final String name, final String[] cmd, final int port) {
        this(dir, name, cmd, Map.of("PORT", String.valueOf(port)));
    }

    public Process(final String dir, final String name, final String[] cmd, final Map<String, String> envVars) {
        this.dir = dir;
        this.name = name;
        this.cmd = cmd;
        this.envVars = envVars;
        this.logger = LoggerFactory.getLogger(getName());
        this.pool = Executors.newCachedThreadPool();
    }

    public void start() throws ProcessException {
        final ProcessBuilder pb = new ProcessBuilder(cmd);

        final Map<String, String> env = pb.environment();
        env.putAll(envVars);

        pb.directory(new File(dir));
        pb.redirectInput(Redirect.INHERIT);
        pb.redirectErrorStream();

        try {
            p = pb.start();
            pool.execute(new StdoutRunnable(new BufferedInputStream(p.getInputStream())));
            pool.execute(new StdoutRunnable(new BufferedInputStream(p.getErrorStream())));
        } catch (final Exception e) {
            throw new ProcessException(e);
        } finally {
            pool.shutdown();
        }
    }

    public void waitFor() throws ProcessException {
        if (isAlive()) {
            try {
                p.waitFor();
            } catch (final InterruptedException e) {
                throw new ProcessException(e.getMessage());
            }
        }
    }

    public boolean isAlive() {
        if (p == null) {
            return false;
        }

        try {
            p.exitValue();
        } catch (final IllegalThreadStateException e) {
            return true;
        }

        return false;
    }

    public boolean exitWithError() {
        return !isAlive() && p.exitValue() != 0;
    }

    public void kill() {
        if (isAlive()) {
            p.destroy();
        }
    }

    public String getName() {
        return name;
    }

    public int getPort() {
        return Integer.parseInt(envVars.getOrDefault("PORT", "0"));
    }

    public static void main(String[] args) {
        new Process(System.getProperty("user.dir"), "java.Process", args, 12345).start();
    }
}