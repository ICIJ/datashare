package org.icij.datashare.cli;

public enum Mode {
    LOCAL(true), SERVER(true), CLI(false), NER(true), TASK_RUNNER(false), EMBEDDED(true);
    private final boolean webServer;

    Mode(boolean webServer) {
        this.webServer = webServer;
    }

    public boolean isWebServer() { return webServer;}
}
