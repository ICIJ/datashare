package org.icij.datashare.cli;

public class CliExitException extends RuntimeException {
    private final int exitCode;

    public CliExitException(int exitCode) {
        super("cli exit " + exitCode);
        this.exitCode = exitCode;
    }

    public int exitCode() { return exitCode; }
}
